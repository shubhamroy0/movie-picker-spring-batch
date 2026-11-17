package com.moviepicker.batch;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.moviepicker.config.SyncProperties;
import com.moviepicker.config.TmdbProperties;
import com.moviepicker.domain.Movie;
import com.moviepicker.domain.TrendingSnapshot;
import com.moviepicker.repository.TrendingSnapshotRepository;
import com.moviepicker.tmdb.TmdbClient;
import com.moviepicker.tmdb.TmdbList;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.FlowBuilder;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.database.JpaItemWriter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The whole job, as a flow:
 *
 *   fetchAndUpsertMovies --COMPLETED--> [decider] --ENRICH------> enrichDetails --COMPLETED--+
 *                                           |                                                 v
 *                                           +------SKIP_ENRICH------------------------> snapshotTrending --COMPLETED--> summary
 *   (any step ending FAILED -> the whole job ends FAILED, and a restart resumes at that step)
 *
 * Steps that already COMPLETED in a failed JobInstance are NOT re-run on restart (default), which is exactly why
 * splitting work into steps makes recovery cheap.
 */
@Configuration(proxyBeanMethods = false)
class SyncJobFlowConfig {

	// ------------------------------------------------------------------ step 2: enrichDetails

	/** Page size = chunk size so one query page feeds exactly one chunk. See RollingPageJpaReader for the why. */
	@Bean
	@StepScope
	RollingPageJpaReader enrichReader(EntityManagerFactory emf, SyncProperties sync) throws Exception {
		RollingPageJpaReader reader = new RollingPageJpaReader(emf);
		reader.setName("enrichReader");
		reader.setQueryString("select m from Movie m where m.detailsFetchedAt is null order by m.id");
		reader.setPageSize(sync.chunkSize());
		reader.setTransacted(false);      // read DETACHED copies; the writer merges them back
		reader.setSaveState(false);       // position is meaningless for a rolling window
		reader.afterPropertiesSet();
		return reader;
	}

	@Bean
	Step enrichDetails(JobRepository jobRepository, PlatformTransactionManager transactionManager,
			RollingPageJpaReader enrichReader, EnrichMovieProcessor processor, EntityManagerFactory emf,
			SyncProperties sync) {
		// JpaItemWriter = merge() per item. Fine HERE (a plain UPDATE of existing rows). Contrast with the custom
		// upsert writer of step 1, which needs insert-vs-update counts and "keep runtime/tagline" rules.
		JpaItemWriter<Movie> writer = new JpaItemWriter<>(emf);
		return new StepBuilder(SyncSteps.ENRICH, jobRepository)
				.<Movie, Movie>chunk(sync.chunkSize())
				.transactionManager(transactionManager)
				.reader(enrichReader)
				.processor(processor)
				.writer(writer)
				.build();
	}

	// ------------------------------------------------------------------ step 3: snapshotTrending

	/** Same reader CLASS as step 1, configured for ONE list: reuse instead of a second implementation. */
	@Bean
	@StepScope
	TmdbMovieReader trendingReader(TmdbClient client, TmdbProperties props) {
		return new TmdbMovieReader(client, List.of(TmdbList.TRENDING_DAY), props.pagesPerList());
	}

	@Bean
	@StepScope
	SnapshotProcessor snapshotProcessor(TrendingSnapshotRepository snapshots, Clock clock,
			@Value("#{jobParameters['runAt']}") Long runAt) {
		return new SnapshotProcessor(snapshots, runAt != null ? Instant.ofEpochMilli(runAt) : clock.instant());
	}

	@Bean
	Step snapshotTrending(JobRepository jobRepository, PlatformTransactionManager transactionManager,
			@Qualifier("trendingReader") TmdbMovieReader trendingReader, SnapshotProcessor snapshotProcessor,
			EntityManagerFactory emf, SyncProperties sync) {
		JpaItemWriter<TrendingSnapshot> writer = new JpaItemWriter<>(emf);
		writer.setUsePersist(true);       // always NEW rows: persist(), not merge() (merge would SELECT first)
		return new StepBuilder(SyncSteps.SNAPSHOT, jobRepository)
				.<SourcedMovie, TrendingSnapshot>chunk(sync.chunkSize())
				.transactionManager(transactionManager)
				.reader(trendingReader)
				.processor(snapshotProcessor)
				.writer(writer)
				.build();
	}

	// ------------------------------------------------------------------ step 4: summary (tasklet)

	@Bean
	Step summary(JobRepository jobRepository, PlatformTransactionManager transactionManager, SummaryTasklet tasklet) {
		return new StepBuilder(SyncSteps.SUMMARY, jobRepository).tasklet(tasklet, transactionManager).build();
	}

	// ------------------------------------------------------------------ the job

	@Bean
	Job syncMoviesJob(JobRepository jobRepository, Step fetchAndUpsertMovies, Step enrichDetails,
			Step snapshotTrending, Step summary, EnrichmentDecider decider,
			ObjectProvider<JobExecutionListener> listeners) {
		FlowBuilder<?> unused = null;   // (type only documents that the DSL below is the flow builder)
		var flow = new JobBuilder(SyncSteps.JOB, jobRepository)
				.start(fetchAndUpsertMovies).on("COMPLETED").to(decider)
				.from(fetchAndUpsertMovies).on("*").fail()
				.from(decider).on(EnrichmentDecider.ENRICH).to(enrichDetails)
				.from(decider).on(EnrichmentDecider.SKIP_ENRICH).to(snapshotTrending)
				.from(enrichDetails).on("COMPLETED").to(snapshotTrending)
				.from(enrichDetails).on("*").fail()
				.from(snapshotTrending).on("COMPLETED").to(summary)
				.from(snapshotTrending).on("*").fail()
				.end();
		// every JobExecutionListener bean is attached (SyncRunListener always, MockDataRotationListener in mock)
		listeners.orderedStream().forEach(flow::listener);
		return flow.build();
	}
}
