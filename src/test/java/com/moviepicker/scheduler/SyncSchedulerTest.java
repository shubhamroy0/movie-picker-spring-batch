package com.moviepicker.scheduler;

import java.time.Clock;

import com.moviepicker.config.SyncProperties;
import com.moviepicker.service.SyncAlreadyRunningException;
import com.moviepicker.service.SyncJobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Pure unit test of the tick logic (no Spring): pause flag, overlap refusal, and "a failure never kills the schedule". */
class SyncSchedulerTest {

	private SyncJobService service;
	private SchedulerControl control;
	private SyncScheduler scheduler;

	@BeforeEach
	void setUp() {
		service = mock(SyncJobService.class);
		SyncProperties props = new SyncProperties(true, java.time.Duration.ofMinutes(5), java.time.Duration.ZERO, 20,
				false, 10, false, true);
		control = new SchedulerControl(props);
		scheduler = new SyncScheduler(service, control, Clock.systemUTC());
	}

	@Test
	void tickLaunchesASyncWithTheSchedulerTrigger() {
		JobExecution done = new JobExecution(7L, null, null);
		done.setStatus(BatchStatus.COMPLETED);
		when(service.startSync(anyString())).thenReturn(done);

		scheduler.tick();

		verify(service).startSync("scheduler");
		assertThat(control.status().lastOutcome()).contains("execution 7").contains("COMPLETED");
	}

	@Test
	void pausedSchedulerSkipsTheTickUntilResumed() {
		control.pause();
		scheduler.tick();
		verify(service, never()).startSync(anyString());
		assertThat(control.status().paused()).isTrue();
		assertThat(control.status().lastOutcome()).contains("paused");

		control.resume();
		when(service.startSync(anyString())).thenReturn(new JobExecution(8L, null, null));
		scheduler.tick();
		verify(service, times(1)).startSync("scheduler");
	}

	@Test
	void overlapAndFailuresAreAbsorbedSoTheScheduleKeepsRunning() {
		when(service.startSync(anyString())).thenThrow(new SyncAlreadyRunningException(3))
				.thenThrow(new IllegalStateException("db down"));

		assertThatCode(scheduler::tick).doesNotThrowAnyException();
		assertThat(control.status().lastOutcome()).contains("already running");
		assertThatCode(scheduler::tick).doesNotThrowAnyException();
		assertThat(control.status().lastOutcome()).contains("error").contains("db down");
	}
}
