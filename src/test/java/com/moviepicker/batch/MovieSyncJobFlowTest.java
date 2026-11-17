package com.moviepicker.batch;

import java.util.List;

import com.moviepicker.domain.SyncRun;
import com.moviepicker.repository.MovieRepository;
import com.moviepicker.repository.SyncRunRepository;
import com.moviepicker.service.SyncJobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** The multi-step flow: step order, promotion of counters, the decider's branch, snapshots and the SyncRun summary. */
@SpringBootTest
@ActiveProfiles({"mock", "test"})
@TestPropertySource(properties = "moviepicker.mock.rotate-data=false")
class MovieSyncJobFlowTest {

	@Autowired SyncJobService syncJobService;
	@Autowired MovieRepository movies;
	@Autowired SyncRunRepository syncRuns;
	@Autowired JdbcTemplate jdbc;
	@Autowired JobRepository jobRepository;

	@BeforeEach
	void clean() {
		movies.deleteAll();
		jdbc.update("delete from trending_snapshot");
		syncRuns.deleteAll();
	}

	private int count(String sql) {
		return jdbc.queryForObject(sql, Integer.class);
	}

	@Test
	void firstRunExecutesAllFourStepsAndEnrichesEveryMovie() {
		JobExecution run = syncJobService.startSync("test");

		assertThat(run.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		assertThat(StepsOf.names(run)).containsExactly(SyncSteps.FETCH, SyncSteps.ENRICH, SyncSteps.SNAPSHOT,
				SyncSteps.SUMMARY);
		// promotion: the STEP-level counter is visible in the (persisted) JOB-level context
		var persistedJobContext = jobRepository.getJobExecution(run.getId()).getExecutionContext();
		assertThat(persistedJobContext.getInt("writer.inserted", -1)).isEqualTo(45);

		// enrichment: the rolling reader must not skip rows while the writer shrinks the result set
		assertThat(StepsOf.step(run, SyncSteps.ENRICH).getWriteCount()).isEqualTo(45);
		assertThat(movies.countByDetailsFetchedAtIsNull()).isZero();
		assertThat(count("select count(*) from movie where runtime is null")).isZero();
		assertThat(count("select count(*) from movie where tagline is not null")).isEqualTo(45);

		// snapshot: ranks 1..30 of trending_day, all stamped with the same capture time
		assertThat(count("select count(*) from trending_snapshot")).isEqualTo(30);
		assertThat(count("select count(distinct rank_position) from trending_snapshot")).isEqualTo(30);
		assertThat(count("select min(rank_position) from trending_snapshot")).isEqualTo(1);
		assertThat(count("select count(distinct captured_at) from trending_snapshot")).isEqualTo(1);

		// summary: one SyncRun row with our business counters
		SyncRun summary = syncRuns.findFirstByOrderByIdDesc().orElseThrow();
		assertThat(summary.getStatus()).isEqualTo("COMPLETED");
		assertThat(summary.getJobExecutionId()).isEqualTo(run.getId());
		assertThat(summary.getInserted()).isEqualTo(45);
		assertThat(summary.getFinishedAt()).isNotNull();
		assertThat(summary.getMessage()).contains("inserted=45").contains("enriched=45").contains("snapshots=30");
	}

	@Test
	void deciderSkipsEnrichmentWhenNothingIsNewAndNothingIsPending() {
		syncJobService.startSync("test");

		JobExecution second = syncJobService.startSync("test");

		assertThat(StepsOf.names(second)).containsExactly(SyncSteps.FETCH, SyncSteps.SNAPSHOT, SyncSteps.SUMMARY);
		assertThat(count("select count(*) from trending_snapshot")).isEqualTo(60);        // history grows: 2 captures
		assertThat(count("select count(distinct captured_at) from trending_snapshot")).isEqualTo(2);
		List<SyncRun> runs = syncRuns.findAll();
		assertThat(runs).hasSize(2);
		assertThat(syncRuns.findFirstByOrderByIdDesc().orElseThrow().getInserted()).isZero();
	}

	@Test
	void deciderStillEnrichesLeftoversFromEarlierRuns() {
		syncJobService.startSync("test");
		jdbc.update("update movie set details_fetched_at = null where tmdb_id = 27205");   // simulate a failed enrichment

		JobExecution second = syncJobService.startSync("test");

		assertThat(StepsOf.names(second)).contains(SyncSteps.ENRICH);
		assertThat(StepsOf.step(second, SyncSteps.ENRICH).getWriteCount()).isEqualTo(1);
		assertThat(movies.countByDetailsFetchedAtIsNull()).isZero();
	}
}
