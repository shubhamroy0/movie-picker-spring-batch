package com.moviepicker.web;

import java.util.Optional;

import com.moviepicker.domain.SyncRun;
import com.moviepicker.repository.SyncRunRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Pure unit test: the indicator is a function from "last SyncRun" to a Health, no Spring needed. */
class LastSyncHealthIndicatorTest {

	private final SyncRunRepository runs = mock(SyncRunRepository.class);
	private final LastSyncHealthIndicator indicator = new LastSyncHealthIndicator(runs);

	@Test
	void unknownWhenNothingRanYet() {
		when(runs.findFirstByOrderByIdDesc()).thenReturn(Optional.empty());

		assertThat(indicator.health().getStatus()).isEqualTo(Status.UNKNOWN);
	}

	@Test
	void upWhenTheLastRunCompleted() {
		when(runs.findFirstByOrderByIdDesc()).thenReturn(Optional.of(new SyncRun(7, "COMPLETED")));

		var health = indicator.health();

		assertThat(health.getStatus()).isEqualTo(Status.UP);
		assertThat(health.getDetails()).containsEntry("jobExecutionId", 7L).containsEntry("status", "COMPLETED");
	}

	@Test
	void downWhenTheLastRunFailed() {
		when(runs.findFirstByOrderByIdDesc()).thenReturn(Optional.of(new SyncRun(8, "FAILED")));

		assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
	}
}
