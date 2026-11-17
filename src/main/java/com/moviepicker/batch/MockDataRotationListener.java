package com.moviepicker.batch;

import com.moviepicker.tmdb.MockTmdbClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.launch.NoSuchJobException;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Mock profile only: makes "time pass" between runs so you can watch inserts and updates happen.
 *
 * The mock generation = (number of JobInstances of this job) - 1. Why derive it from the BATCH_* metadata instead of
 * an in-memory counter?
 *  - it survives application restarts (a counter would reset to 0 and every sync would look "unchanged");
 *  - a RESTART of a failed execution belongs to the SAME JobInstance, so it sees the SAME data as the failed attempt
 *    - exactly what a restart test needs.
 * Switch off with moviepicker.mock.rotate-data=false (the idempotency test does: identical input, run twice).
 */
@Component
@Profile("mock")
@ConditionalOnProperty(prefix = "moviepicker.mock", name = "rotate-data", havingValue = "true", matchIfMissing = true)
class MockDataRotationListener implements JobExecutionListener {

	private static final Logger log = LoggerFactory.getLogger(MockDataRotationListener.class);

	private final MockTmdbClient client;
	private final JobRepository jobRepository;

	MockDataRotationListener(MockTmdbClient client, JobRepository jobRepository) {
		this.client = client;
		this.jobRepository = jobRepository;
	}

	@Override
	public void beforeJob(JobExecution jobExecution) {
		String jobName = jobExecution.getJobInstance().getJobName();
		try {
			int generation = (int) jobRepository.getJobInstanceCount(jobName) - 1;   // current instance already exists
			client.setGeneration(generation);
			log.info("Mock data generation for this run: {} (JobInstance #{})", generation,
					jobExecution.getJobInstanceId());
		}
		catch (NoSuchJobException e) {
			throw new IllegalStateException("Cannot determine mock generation for " + jobName, e);
		}
	}
}
