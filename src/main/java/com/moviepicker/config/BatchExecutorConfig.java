package com.moviepicker.config;

import org.springframework.boot.batch.autoconfigure.BatchTaskExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

/**
 * Makes the auto-configured JobOperator ASYNCHRONOUS: start()/restart() create the JobExecution (status STARTING,
 * persisted, id known) and return IMMEDIATELY while the job runs on one of these threads. Without a bean marked
 * {@code @BatchTaskExecutor} Boot's JobOperator runs jobs on the CALLING thread (start() blocks until the job ends).
 *
 * Why we want async: the REST API must answer "202 Accepted + executionId" at once, and stop() only makes sense while
 * the job runs on another thread. The scheduler and tests that WANT to wait use SyncJobService.awaitCompletion().
 * SimpleAsyncTaskExecutor = one new thread per job: right for a handful of long-running jobs, wrong for tiny tasks.
 */
@Configuration(proxyBeanMethods = false)
class BatchExecutorConfig {

	@Bean
	@BatchTaskExecutor
	TaskExecutor batchTaskExecutor() {
		return new SimpleAsyncTaskExecutor("batch-job-");
	}
}
