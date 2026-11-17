package com.moviepicker.batch;

import java.time.Clock;
import java.time.Instant;

import com.moviepicker.config.SyncProperties;
import com.moviepicker.config.TmdbProperties;
import com.moviepicker.repository.MovieRepository;
import com.moviepicker.service.RejectedRecordService;
import com.moviepicker.tmdb.TmdbClient;
import com.moviepicker.tmdb.TmdbList;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

/**
 * Phase 8: the PARTITIONED version of the fetch step, enabled with moviepicker.sync.partitioned=true.
 * It defines the same bean name as MovieSyncJobConfig.fetchAndUpsertMovies (only one of the two is active), so the job
 * definition does not change at all: steps are interchangeable pieces.
 *
 *   fetchAndUpsertMovies  (MANAGER, a PartitionStep)
 *      |-- fetchWorker:partition-trending_day   \
 *      |-- fetchWorker:partition-now_playing     |  4 worker StepExecutions, run in parallel threads,
 *      |-- fetchWorker:partition-popular         |  each with its own reader / writer / transactions / restart state
 *      '-- fetchWorker:partition-top_rated      /
 *
 * WHICH SCALING TOOL WHEN (interview classic):
 *  - Multi-threaded step (taskExecutor on a plain step): same reader shared by N threads. Needs a thread-safe reader and
 *    gives up restartability (saveState must be false). Simplest, but fragile.
 *  - Partitioning (this): the INPUT is split into independent slices, each processed by its own complete step.
 *    Restartable per slice (only the FAILED partitions re-run). Local threads here; the same design runs workers on
 *    other machines through a PartitionHandler over messaging (remote partitioning).
 *  - Remote chunking: ONE reader on the manager sends items to remote workers that process+write. Good when the
 *    PROCESSING is the bottleneck and reading is cheap. Needs a durable middleware (JMS/Kafka) and in-order delivery care.
 *  - Parallel steps (split flows): different steps run at once. A different axis (independent steps, not one big one).
 * WHY PARALLELISM IS CAPPED HERE: the TMDB rate limit. Four partitions share ONE throttled client (see
 * RestTmdbClient.throttle), so extra threads would just queue on the rate limit. The gain is overlapping DB writes and
 * network waits; with a real 40 req/s limit and few lists, partitioning is a teaching tool more than a speed-up.
 *
 * CONCURRENCY HAZARD (found by running it, see SerializedUpsertWriter): the same movie appears in several lists, so two
 * workers insert/update the same tmdbId at once. The unique key rejects the loser, and Batch 6's in-place retry cannot
 * recover because the Hibernate session is poisoned. So workers READ in parallel but WRITE one at a time, each chunk in
 * its own short transaction. The unique key stays as the last line of defence.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "moviepicker.sync.partitioned", havingValue = "true")
class PartitionedFetchConfig {

	static final String WORKER_STEP = "fetchWorker";

	/** Late-bound: each worker StepExecution gets its OWN reader, configured with its own list from its own context. */
	@Bean
	@StepScope
	TmdbMovieReader partitionReader(TmdbClient client, TmdbProperties props,
			@Value("#{stepExecutionContext['listName']}") String listName) {
		return new TmdbMovieReader(client, List.of(TmdbList.fromListName(listName)), props.pagesPerList());
	}

	/** Own writer instance per worker: its inserted/updated counters must not be shared between partitions. */
	@Bean
	@StepScope
	MovieUpsertWriter partitionWriter(MovieRepository movies, Clock clock,
			@Value("#{jobParameters['runAt']}") Long runAt) {
		Instant runStart = runAt != null ? Instant.ofEpochMilli(runAt) : clock.instant();
		return new MovieUpsertWriter(movies, clock, runStart);
	}

	@Bean
	Step fetchWorker(JobRepository jobRepository, PlatformTransactionManager transactionManager,
			@Qualifier("partitionReader") TmdbMovieReader reader, MovieItemProcessor processor,
			@Qualifier("partitionWriter") MovieUpsertWriter writer, SyncProperties sync,
			RejectedItemSkipListener skipListener) {
		// No promotion on workers: the manager's aggregator sums their counters and the MANAGER promotes the totals.
		return MovieSyncJobConfig.faultTolerantFetchStep(WORKER_STEP, jobRepository, transactionManager, reader,
				processor, new SerializedUpsertWriter(writer, transactionManager), sync, skipListener, null);
	}

	@Bean
	Step fetchAndUpsertMovies(JobRepository jobRepository, @Qualifier("fetchWorker") Step fetchWorker,
			TmdbProperties props) {
		// One thread per partition; SimpleAsyncTaskExecutor starts a fresh thread per task and has nothing to shut down.
		SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("partition-");
		executor.setConcurrencyLimit(Math.max(1, props.lists().size()));
		return new StepBuilder(SyncSteps.FETCH, jobRepository)
				.partitioner(WORKER_STEP, new ListPartitioner(props.lists()))
				.step(fetchWorker)
				.taskExecutor(executor)
				.aggregator(new CounterAggregator())
				.listener(MovieSyncJobConfig.promotionListener())
				.build();
	}
}
