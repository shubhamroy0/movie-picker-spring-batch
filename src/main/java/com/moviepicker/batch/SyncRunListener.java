package com.moviepicker.batch;

import java.time.Duration;
import java.time.LocalDateTime;

import com.moviepicker.domain.SyncRun;
import com.moviepicker.service.SyncRunService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.stereotype.Component;

/**
 * JobExecutionListener = hooks around the WHOLE job. Two jobs here:
 *  - beforeJob: open the SyncRun row (status RUNNING), so a run is visible in the API while it is still going;
 *  - afterJob : close it (also for FAILED runs - the summary tasklet never runs then) and log the ONE summary line.
 * afterJob runs in every case: success, failure, stop. That is why timing/auditing belongs in a listener, not a step.
 */
@Component
public class SyncRunListener implements JobExecutionListener {

	private static final Logger log = LoggerFactory.getLogger(SyncRunListener.class);

	private final SyncRunService syncRuns;

	public SyncRunListener(SyncRunService syncRuns) {
		this.syncRuns = syncRuns;
	}

	@Override
	public void beforeJob(JobExecution jobExecution) {
		syncRuns.start(jobExecution);
	}

	@Override
	public void afterJob(JobExecution jobExecution) {
		SyncRun run = syncRuns.finish(jobExecution);
		LocalDateTime start = jobExecution.getStartTime();
		long ms = start == null ? -1 : Duration.between(start, LocalDateTime.now()).toMillis();
		log.info("SYNC RUN {} {} in {} ms: {}", jobExecution.getId(), run.getStatus(), ms, run.getMessage());
	}
}
