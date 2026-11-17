package com.moviepicker.batch;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import com.moviepicker.config.SyncProperties;
import com.moviepicker.config.TmdbProperties;
import com.moviepicker.domain.Movie;
import com.moviepicker.repository.MovieRepository;
import com.moviepicker.service.RejectedRecordService;
import com.moviepicker.tmdb.TmdbClient;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.builder.SimpleJobBuilder;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.batch.core.listener.ExecutionContextPromotionListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Wires the job:   syncMoviesJob  =  [ fetchAndUpsertMovies ]   (more steps arrive in Phase 4)
 *
 *   Job  = the whole unit of work, identified by name + identifying parameters (-> JobInstance).
 *   Step = one phase of the job. This one is CHUNK-ORIENTED: read items one by one, process each, then write them
 *          together in groups ("chunks") of N inside ONE transaction.
 *
 * Spring Batch 6 vs older tutorials: no JobBuilderFactory/StepBuilderFactory - builders take the JobRepository directly.
 */
@Configuration(proxyBeanMethods = false)
class MovieSyncJobConfig {

	static final String JOB_NAME = SyncSteps.JOB;
	static final String FETCH_STEP_NAME = SyncSteps.FETCH;

	/**
	 * @StepScope = the bean is created LAZILY, once PER STEP EXECUTION (not once per application).
	 *  - The reader keeps mutable state (current list/page): a singleton would carry that state from one run to the
	 *    next. A fresh instance per execution makes that impossible.
	 *  - It enables LATE BINDING: "#{jobParameters['runAt']}" is only known when the job is launched, so the value
	 *    can only be injected at step start (see the writer below).
	 * Internally Spring injects a PROXY; the real object is created on first use inside the running step. That is why
	 * the return type is the concrete class (the proxy must still be an ItemStream so the step can register it).
	 */
	@Bean
	@StepScope
	TmdbMovieReader tmdbMovieReader(TmdbClient client, TmdbProperties props) {
		return new TmdbMovieReader(client, props.lists(), props.pagesPerList());
	}

	@Bean
	@StepScope
	MovieUpsertWriter movieUpsertWriter(MovieRepository movies, Clock clock,
			@Value("#{jobParameters['runAt']}") Long runAt) {
		Instant runStart = runAt != null ? Instant.ofEpochMilli(runAt) : clock.instant();
		return new MovieUpsertWriter(movies, clock, runStart);
	}

	/**
	 * CHUNK-ORIENTED STEP. Per chunk the framework does:
	 *     begin transaction
	 *       loop chunkSize times:  item = reader.read(); if null -> stop;  out = processor.process(item); keep if != null
	 *       writer.write(all kept items)
	 *       update ItemStreams (reader/writer position -> ExecutionContext) + StepExecution counts
	 *     commit   (or rollback everything if anything threw)
	 *
	 * GOTCHA (Batch 6): ChunkOrientedStepBuilder defaults to a ResourcelessTransactionManager, i.e. NO real
	 * transaction. We must pass the JPA transaction manager explicitly, otherwise our JPA writes would not be tied to
	 * the chunk and a failed chunk could not roll back.
	 */
	/** Per-step-execution because it must know WHICH job execution it is recording rejects for. */
	@Bean
	@StepScope
	RejectedItemSkipListener rejectedItemSkipListener(RejectedRecordService rejects,
			@Value("#{stepExecution.jobExecutionId}") Long jobExecutionId) {
		return new RejectedItemSkipListener(rejects, jobExecutionId);
	}

	/**
	 * FAULT TOLERANCE (all three tools, each for a different failure):
	 *  - SKIP  InvalidMovieException (corrupt item): drop THIS item, keep going, record it. Capped by skipLimit: if more
	 *          than N items are bad the feed itself is broken, so the step FAILS rather than hiding it.
	 *  - RETRY transient DB errors (lock timeout, optimistic-lock clash, insert race) with exponential backoff: the same
	 *          chunk is rolled back and re-executed. Uses Spring Framework 7's RetryPolicy (Batch 6 builds on it).
	 *  - FILTER (processor returns null) is not an error at all - see MovieItemProcessor.
	 * Anything else (e.g. TmdbPermanentException) is neither skippable nor retryable -> step FAILED -> restartable.
	 * HTTP-level retry for 429/5xx lives in ResilientTmdbClient (reads are not retried by the step).
	 */
	@Bean
	@ConditionalOnProperty(name = "moviepicker.sync.partitioned", havingValue = "false", matchIfMissing = true)
	Step fetchAndUpsertMovies(JobRepository jobRepository, PlatformTransactionManager transactionManager,
			@Qualifier("tmdbMovieReader") TmdbMovieReader reader, MovieItemProcessor processor, MovieUpsertWriter writer, SyncProperties sync,
			RejectedItemSkipListener skipListener) {
		// PROMOTION: step-context values are private to the step. This listener copies the named keys into the JOB
		// context after the step COMPLETES, so the decider and the summary can read them.
		return faultTolerantFetchStep(FETCH_STEP_NAME, jobRepository, transactionManager, reader, processor, writer, sync,
				skipListener, promotionListener());
	}

	static ExecutionContextPromotionListener promotionListener() {
		ExecutionContextPromotionListener promotion = new ExecutionContextPromotionListener();
		promotion.setKeys(new String[] {MovieUpsertWriter.KEY_INSERTED, MovieUpsertWriter.KEY_UPDATED,
				MovieUpsertWriter.KEY_UNCHANGED, MovieUpsertWriter.KEY_REPEATS});
		return promotion;
	}

	/** Shared by the plain step and by each partition worker (Phase 8): same read/process/write + fault tolerance. */
	static Step faultTolerantFetchStep(String name, JobRepository jobRepository,
			PlatformTransactionManager transactionManager, TmdbMovieReader reader, MovieItemProcessor processor,
			org.springframework.batch.infrastructure.item.ItemWriter<Movie> writer, SyncProperties sync,
			RejectedItemSkipListener skipListener, ExecutionContextPromotionListener promotion) {
		var builder = new StepBuilder(name, jobRepository)
				.<SourcedMovie, Movie>chunk(sync.chunkSize())
				.transactionManager(transactionManager)
				.reader(reader)
				.processor(processor)
				.writer(writer)
				.faultTolerant()
				.skip(InvalidMovieException.class)
				.skipLimit(sync.skipLimit())
				.skipListener(skipListener)
				.retryPolicy(RetryPolicy.builder()
						.includes(TransientDataAccessException.class, OptimisticLockingFailureException.class,
								DataIntegrityViolationException.class)
						.maxRetries(3)
						.delay(Duration.ofMillis(200))
						.multiplier(2.0)
						.build())
				.listener(new StepLoggingListener());
		if (promotion != null) {
			builder.listener(promotion);
		}
		return builder.build();
	}
}
