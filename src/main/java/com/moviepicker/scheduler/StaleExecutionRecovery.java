package com.moviepicker.scheduler;

import java.time.LocalDateTime;

import com.moviepicker.batch.SyncSteps;
import com.moviepicker.config.SyncProperties;
import com.moviepicker.service.SyncRunService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * THE ZOMBIE EXECUTION PROBLEM. If the JVM dies (kill -9, power cut, OOM) while a job runs, BATCH_JOB_EXECUTION keeps a
 * row with status STARTED forever. Spring Batch cannot tell "running in another JVM" from "dead", so it refuses to
 * restart it ("JobExecutionAlreadyRunning") and our overlap guard would block every future sync.
 * At startup we therefore mark such rows FAILED (so they become restartable) and close their SyncRun.
 *
 * ONLY SAFE WITH A SINGLE INSTANCE: with several pods, pod B starting up would wrongly "kill" the job pod A is running.
 * Clustered deployments need heartbeats/lease timestamps instead (or switch this off: recover-stale-on-startup=false).
 */
@Component
@Order(0)
class StaleExecutionRecovery implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(StaleExecutionRecovery.class);

	private final JobRepository jobRepository;
	private final SyncRunService syncRuns;
	private final SyncProperties props;

	StaleExecutionRecovery(JobRepository jobRepository, SyncRunService syncRuns, SyncProperties props) {
		this.jobRepository = jobRepository;
		this.syncRuns = syncRuns;
		this.props = props;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (props.recoverStaleOnStartup()) {
			recover();
		}
	}

	/** @return how many zombie executions were marked FAILED */
	int recover() {
		int n = 0;
		for (JobExecution je : jobRepository.findRunningJobExecutions(SyncSteps.JOB)) {
			for (StepExecution se : je.getStepExecutions()) {
				if (se.getStatus().isRunning()) {
					se.setStatus(BatchStatus.FAILED);
					se.setExitStatus(ExitStatus.FAILED.addExitDescription("Process died while the step was running"));
					se.setEndTime(LocalDateTime.now());
					jobRepository.update(se);
				}
			}
			je.setStatus(BatchStatus.FAILED);
			je.setExitStatus(ExitStatus.FAILED.addExitDescription("Marked FAILED at startup: the process that was running it died"));
			je.setEndTime(LocalDateTime.now());
			jobRepository.update(je);
			syncRuns.finish(je);
			log.warn("Recovered zombie execution {} (was STARTED, process died): now FAILED and restartable", je.getId());
			n++;
		}
		return n;
	}
}
