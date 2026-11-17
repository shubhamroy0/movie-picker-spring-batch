package com.moviepicker.scheduler;

import java.time.Clock;

import com.moviepicker.service.SyncAlreadyRunningException;
import com.moviepicker.service.SyncJobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Launches the sync job on a schedule (the Spring BOOT side; Spring Batch has no scheduler of its own).
 *
 * fixedDelay vs fixedRate vs cron:
 *  - fixedRate : start every N ms measured from the previous START. If a run takes longer than N, runs queue up/overlap.
 *  - fixedDelay: wait N ms after the previous run ENDS. Runs can never overlap and a slow run just delays the next one.
 *  - cron      : wall-clock times ("0 0/5 * * * *"). Right for "at 02:00 every night", not for "keep data fresh".
 * We use fixedDelay: a sync that takes 2 minutes under a 1 minute interval must not pile up behind itself.
 * The interval is an ISO-8601 duration (PT5M) from properties; the "demo" profile sets PT30S.
 *
 * The tick BLOCKS until the job finishes (SyncJobService.startSync waits) - that is what makes fixedDelay measure
 * "end of run to start of next". The overlap guard in SyncJobService additionally protects against a manual/API run
 * that is in flight when a tick arrives.
 *
 * spring.batch.job.enabled=false (application.yml): Boot would otherwise run EVERY Job bean once at startup with no
 * parameters (JobLauncherApplicationRunner). We want launches only from here and from the API, with our own parameters.
 */
@Component
@ConditionalOnProperty(prefix = "moviepicker.sync", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SyncScheduler {

	private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);

	private final SyncJobService syncJobService;
	private final SchedulerControl control;
	private final Clock clock;

	public SyncScheduler(SyncJobService syncJobService, SchedulerControl control, Clock clock) {
		this.syncJobService = syncJobService;
		this.control = control;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${moviepicker.sync.interval:PT5M}",
			initialDelayString = "${moviepicker.sync.initial-delay:PT10S}")
	public void tick() {
		if (control.isPaused()) {
			log.debug("Scheduler paused: skipping this tick");
			control.record(clock.instant(), "skipped: paused");
			return;
		}
		try {
			JobExecution execution = syncJobService.startSync("scheduler");
			control.record(clock.instant(), "execution " + execution.getId() + " " + execution.getStatus());
		}
		catch (SyncAlreadyRunningException e) {
			log.info("Scheduled sync skipped: {}", e.getMessage());
			control.record(clock.instant(), "skipped: " + e.getMessage());
		}
		catch (RuntimeException e) {
			// Never let an exception escape a @Scheduled method's thread of control silently: log it, keep scheduling.
			log.error("Scheduled sync failed to run", e);
			control.record(clock.instant(), "error: " + e.getMessage());
		}
	}
}
