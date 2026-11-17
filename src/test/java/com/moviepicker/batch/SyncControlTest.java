package com.moviepicker.batch;

import com.moviepicker.repository.MovieRepository;
import com.moviepicker.service.ExecutionNotFoundException;
import com.moviepicker.service.ExecutionStateException;
import com.moviepicker.service.SyncAlreadyRunningException;
import com.moviepicker.service.SyncJobService;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Control plane while a job is running: async launch, overlap refusal, stop, restart. 60 ms latency per fetch = ~1 s runs. */
@SpringBootTest
@ActiveProfiles({"mock", "test"})
@TestPropertySource(properties = {"moviepicker.mock.rotate-data=false", "moviepicker.mock.latency-millis=60"})
class SyncControlTest {

	@Autowired SyncJobService syncJobService;
	@Autowired MovieRepository movies;

	@Test
	void launchIsAsyncAndAConcurrentLaunchIsRefused() {
		JobExecution running = syncJobService.startSyncAsync("test");

		assertThat(running.getId()).isPositive();                                           // id known immediately
		assertThat(syncJobService.find(running.getId()).isRunning()).isTrue();              // ... while still running
		assertThatThrownBy(() -> syncJobService.startSyncAsync("test")).isInstanceOf(SyncAlreadyRunningException.class);
		assertThatThrownBy(() -> syncJobService.stop(999_999)).isInstanceOf(ExecutionNotFoundException.class);

		assertThat(syncJobService.awaitCompletion(running.getId()).getStatus()).isEqualTo(BatchStatus.COMPLETED);
		assertThatThrownBy(() -> syncJobService.stop(running.getId())).isInstanceOf(ExecutionStateException.class);
		assertThat(syncJobService.startSync("test").getStatus()).isEqualTo(BatchStatus.COMPLETED);   // free again
	}

	@Test
	void aStoppedRunKeepsItsCommittedWorkAndCanBeRestarted() throws InterruptedException {
		movies.deleteAll();
		JobExecution running = syncJobService.startSyncAsync("test");
		Thread.sleep(400);                                                                   // let some chunks commit

		syncJobService.stop(running.getId());
		JobExecution stopped = syncJobService.awaitCompletion(running.getId());

		assertThat(stopped.getStatus()).isEqualTo(BatchStatus.STOPPED);                      // graceful: not FAILED
		long partial = movies.count();
		assertThat(partial).isLessThan(45);                                                  // stopped before the end

		JobExecution restarted = syncJobService.awaitCompletion(syncJobService.restart(stopped.getId()).getId());

		assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		assertThat(restarted.getJobInstanceId()).isEqualTo(stopped.getJobInstanceId());
		assertThat(movies.count()).isEqualTo(45);
	}
}
