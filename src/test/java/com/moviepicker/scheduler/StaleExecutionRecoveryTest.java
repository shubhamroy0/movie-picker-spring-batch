package com.moviepicker.scheduler;

import com.moviepicker.batch.SyncSteps;
import com.moviepicker.service.SyncAlreadyRunningException;
import com.moviepicker.service.SyncJobService;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A "zombie" = an execution left STARTED by a process that died. It blocks the overlap guard until recovered. */
@SpringBootTest
@ActiveProfiles({"mock", "test"})
@TestPropertySource(properties = "moviepicker.mock.rotate-data=false")
class StaleExecutionRecoveryTest {

	@Autowired JobRepository jobRepository;
	@Autowired SyncJobService syncJobService;
	@Autowired StaleExecutionRecovery recovery;

	@Test
	void zombieBlocksNewSyncsUntilRecoveredAndThenBecomesRestartable() {
		JobParameters params = new JobParametersBuilder().addLong("runAt", 1L, true).toJobParameters();
		JobInstance instance = jobRepository.createJobInstance(SyncSteps.JOB, params);
		JobExecution zombie = jobRepository.createJobExecution(instance, params, new ExecutionContext());
		zombie.setStatus(BatchStatus.STARTED);                 // as if the JVM died right after starting
		jobRepository.update(zombie);

		assertThatThrownBy(() -> syncJobService.startSyncAsync("test")).isInstanceOf(SyncAlreadyRunningException.class);

		assertThat(recovery.recover()).isEqualTo(1);

		assertThat(jobRepository.getJobExecution(zombie.getId()).getStatus()).isEqualTo(BatchStatus.FAILED);
		assertThat(syncJobService.startSync("test").getStatus()).isEqualTo(BatchStatus.COMPLETED);   // unblocked
	}
}
