package com.moviepicker.batch;

import com.moviepicker.repository.MovieRepository;
import com.moviepicker.repository.SyncRunRepository;
import com.moviepicker.service.ExecutionStateException;
import com.moviepicker.service.SyncJobService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RESTARTABILITY. The mock throws a non-retryable error ONCE while fetching page 2 of "popular" -> the step FAILS,
 * the job FAILS. Restarting that execution must create a NEW JobExecution of the SAME JobInstance, resume from the
 * position saved at the last committed chunk, and end with exactly the same data as an uninterrupted run (no duplicates).
 */
@SpringBootTest
@ActiveProfiles({"mock", "test"})
@TestPropertySource(properties = {
		"moviepicker.mock.rotate-data=false",
		"moviepicker.mock.fail-once-at=popular:2"})
class MovieSyncJobRestartTest {

	@Autowired SyncJobService syncJobService;
	@Autowired JobRepository jobRepository;
	@Autowired MovieRepository movies;
	@Autowired SyncRunRepository syncRuns;
	@Autowired JdbcTemplate jdbc;

	@Test
	void failedRunIsRestartedFromTheSavedPositionWithoutDuplicates() {
		JobExecution failed = syncJobService.startSync("test");

		// ---- what a crash looks like in the metadata
		assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
		assertThat(StepsOf.names(failed)).containsExactly(SyncSteps.FETCH);               // later steps never ran
		var failedStep = StepsOf.fetch(failed);
		assertThat(failedStep.getStatus()).isEqualTo(BatchStatus.FAILED);
		assertThat(failedStep.getExitStatus().getExitDescription()).contains("Simulated crash");
		assertThat(failedStep.getCommitCount()).isPositive();                              // earlier chunks DID commit
		long committedBeforeCrash = movies.count();
		assertThat(committedBeforeCrash).isBetween(1L, 44L);                               // partial data is in the DB
		int savedList = failedStep.getExecutionContext().getInt(TmdbMovieReader.KEY_LIST_INDEX);
		assertThat(savedList).isGreaterThanOrEqualTo(1);                                   // saved position = last COMMIT
		var failedRun = syncRuns.findFirstByJobExecutionIdOrderByIdDesc(failed.getId()).orElseThrow();
		assertThat(failedRun.getStatus()).isEqualTo("FAILED");
		assertThat((long) failedRun.getInserted()).isEqualTo(committedBeforeCrash);        // the summary is not "inserted=0"

		// ---- restart
		JobExecution restarted = syncJobService.awaitCompletion(syncJobService.restart(failed.getId()).getId());

		assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		assertThat(restarted.getId()).isNotEqualTo(failed.getId());                         // new JobExecution ...
		assertThat(restarted.getJobInstanceId()).isEqualTo(failed.getJobInstanceId());      // ... of the SAME JobInstance
		assertThat(jobRepository.getJobExecutions(restarted.getJobInstance())).hasSize(2);
		assertThat(StepsOf.fetch(restarted).getReadCount()).isLessThan(143);               // resumed: did NOT re-read all
		assertThat(StepsOf.names(restarted)).contains(SyncSteps.FETCH, SyncSteps.ENRICH, SyncSteps.SNAPSHOT,
				SyncSteps.SUMMARY);

		// ---- same result as an uninterrupted run
		assertThat(movies.count()).isEqualTo(45);
		assertThat(jdbc.queryForList("select tmdb_id from movie", Long.class)).doesNotHaveDuplicates();
		assertThat(jdbc.queryForObject("select count(*) from batch_job_instance", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from batch_job_execution", Integer.class)).isEqualTo(2);
		assertThat(syncRuns.findFirstByJobExecutionIdOrderByIdDesc(restarted.getId()).orElseThrow().getStatus())
				.isEqualTo("COMPLETED");

		// ---- a COMPLETED execution cannot be restarted again
		assertThatThrownBy(() -> syncJobService.restart(restarted.getId())).isInstanceOf(ExecutionStateException.class);
	}
}
