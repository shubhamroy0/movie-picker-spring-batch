package com.moviepicker.service;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;

import com.moviepicker.batch.SyncSteps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobExecutionException;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;

/**
 * The ONE place that launches, restarts and stops the sync job. The scheduler, the REST API and tests all use it.
 *
 * JobOperator is Spring Batch 6's launching API (JobLauncher/JobExplorer are deprecated). It is ASYNCHRONOUS here
 * (see BatchExecutorConfig): start() returns the new JobExecution at once; use {@link #awaitCompletion} to wait.
 *
 * WHY A UNIQUE IDENTIFYING PARAMETER (runAt): JobInstance = job name + IDENTIFYING parameters, and the framework refuses
 * to run an instance that already COMPLETED. "Sync every 5 minutes" has no natural parameter, so without one the second
 * launch would be the same instance as the first and be rejected. A timestamp makes every launch a NEW instance;
 * `trigger` is NON-identifying (stored as information only).
 *
 * OVERLAP GUARD: distinct runAt values mean distinct instances, so Batch itself would happily run two syncs at once.
 * We check "is one running?" and launch under one lock. That protects ONE JVM. With several instances (pods) the check
 * must be shared: a DB lock/ShedLock (@SchedulerLock), a unique "lock" row, or a single designated scheduler node.
 */
@Service
public class SyncJobService {

	private static final Logger log = LoggerFactory.getLogger(SyncJobService.class);
	private static final Duration POLL = Duration.ofMillis(50);

	private final JobOperator jobOperator;
	private final JobRepository jobRepository;
	private final Job syncMoviesJob;
	private final Clock clock;
	private final Object launchLock = new Object();

	public SyncJobService(JobOperator jobOperator, JobRepository jobRepository, Job syncMoviesJob, Clock clock) {
		this.jobOperator = jobOperator;
		this.jobRepository = jobRepository;
		this.syncMoviesJob = syncMoviesJob;
		this.clock = clock;
	}

	/** Launch and WAIT for the end (scheduler, tests). @param trigger "scheduler", "manual", "startup" (info only) */
	public JobExecution startSync(String trigger) {
		return awaitCompletion(startSyncAsync(trigger).getId());
	}

	/** Launch and return immediately (REST API). The returned execution is STARTING/STARTED. */
	public JobExecution startSyncAsync(String trigger) {
		JobParameters params = new JobParametersBuilder()
				.addLong("runAt", clock.millis(), true)          // identifying: a new JobInstance per launch
				.addString("trigger", trigger, false)           // non-identifying: just metadata
				.toJobParameters();
		synchronized (launchLock) {
			assertNothingRunning();
			try {
				log.info("Launching {} (trigger={})", SyncSteps.JOB, trigger);
				return jobOperator.start(syncMoviesJob, params);
			}
			catch (JobExecutionException e) {
				throw new SyncLaunchException("Could not launch " + SyncSteps.JOB + ": " + e.getMessage(), e);
			}
		}
	}

	/** Restart a FAILED or STOPPED execution: same JobInstance, a NEW JobExecution that resumes where it stopped. */
	public JobExecution restart(long executionId) {
		JobExecution previous = find(executionId);
		if (previous.getStatus() != BatchStatus.FAILED && previous.getStatus() != BatchStatus.STOPPED) {
			throw new ExecutionStateException("Execution " + executionId + " is " + previous.getStatus()
					+ "; only FAILED or STOPPED executions can be restarted");
		}
		synchronized (launchLock) {
			assertNothingRunning();
			try {
				log.info("Restarting execution {} (JobInstance {})", executionId, previous.getJobInstanceId());
				return jobOperator.restart(previous);
			}
			catch (JobExecutionException e) {
				throw new SyncLaunchException("Could not restart execution " + executionId + ": " + e.getMessage(), e);
			}
		}
	}

	/** Ask a running execution to stop: it finishes the current chunk, commits it, and ends STOPPED (restartable). */
	public void stop(long executionId) {
		JobExecution execution = find(executionId);
		if (!execution.isRunning()) {
			throw new ExecutionStateException("Execution " + executionId + " is " + execution.getStatus() + ", not running");
		}
		try {
			jobOperator.stop(execution);
		}
		catch (JobExecutionException e) {
			throw new ExecutionStateException("Could not stop execution " + executionId + ": " + e.getMessage());
		}
	}

	public JobExecution find(long executionId) {
		try {
			JobExecution execution = jobRepository.getJobExecution(executionId);
			if (execution == null) {
				throw new ExecutionNotFoundException(executionId);
			}
			return execution;
		}
		catch (EmptyResultDataAccessException e) {
			// Batch 6's JDBC repository THROWS for an unknown id instead of returning null.
			throw new ExecutionNotFoundException(executionId);
		}
	}

	/** Polls the JobRepository (the source of truth, shared across threads) until the execution is no longer running. */
	public JobExecution awaitCompletion(long executionId) {
		long deadline = System.nanoTime() + Duration.ofMinutes(30).toNanos();
		JobExecution execution = find(executionId);
		while (execution.isRunning()) {
			if (System.nanoTime() > deadline) {
				throw new IllegalStateException("Timed out waiting for execution " + executionId);
			}
			try {
				Thread.sleep(POLL);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while waiting for execution " + executionId, e);
			}
			execution = find(executionId);
		}
		logSummary(execution);
		return execution;
	}

	private void assertNothingRunning() {
		Set<JobExecution> running = jobRepository.findRunningJobExecutions(SyncSteps.JOB);
		if (!running.isEmpty()) {
			throw new SyncAlreadyRunningException(running.iterator().next().getId());
		}
	}

	private static void logSummary(JobExecution execution) {
		for (StepExecution step : execution.getStepExecutions()) {
			log.debug("Step '{}': status={} read={} filtered={} written={} commits={} rollbacks={} skips={}",
					step.getStepName(), step.getStatus(), step.getReadCount(), step.getFilterCount(),
					step.getWriteCount(), step.getCommitCount(), step.getRollbackCount(), step.getSkipCount());
		}
		log.info("Job execution {} finished: status={} exit={}", execution.getId(), execution.getStatus(),
				execution.getExitStatus().getExitCode());
	}
}
