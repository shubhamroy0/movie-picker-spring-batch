package com.moviepicker.batch;

import com.moviepicker.service.SyncJobService;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** Skipping is a budget, not a blanket: past skip-limit the step must FAIL so a broken feed is noticed. */
@SpringBootTest
@ActiveProfiles({"mock", "test"})
@TestPropertySource(properties = {
		"moviepicker.mock.rotate-data=false",
		"moviepicker.mock.corrupt-records=true",
		"moviepicker.sync.skip-limit=1"})
class MovieSyncJobSkipLimitTest {

	@Autowired SyncJobService syncJobService;

	@Test
	void exceedingTheSkipLimitFailsTheStepAndTheJob() {
		JobExecution execution = syncJobService.startSync("test");

		assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
		assertThat(StepsOf.names(execution)).containsExactly(SyncSteps.FETCH);   // later steps never started
		String message = StepsOf.fetch(execution).getExitStatus().getExitDescription();
		assertThat(message).contains("SkipLimitExceeded");
	}
}
