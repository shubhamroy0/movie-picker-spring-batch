package com.moviepicker.batch;

import com.moviepicker.repository.MovieRepository;
import com.moviepicker.service.SyncJobService;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One run with THREE simultaneous problems: two 429s and a 503 from the API (-> client retry with backoff), and two
 * corrupt movies in the feed (-> skip + rejected_record). The job must still COMPLETE with all good data.
 */
@SpringBootTest
@ActiveProfiles({"mock", "test"})
@TestPropertySource(properties = {
		"moviepicker.mock.rotate-data=false",
		"moviepicker.mock.rate-limit-calls=2",
		"moviepicker.mock.server-error-calls=1",
		"moviepicker.mock.corrupt-records=true",
		"moviepicker.tmdb.retry.initial-backoff=PT0.05S"})
class MovieSyncJobFaultToleranceTest {

	@Autowired SyncJobService syncJobService;
	@Autowired MovieRepository movies;
	@Autowired JdbcTemplate jdbc;

	@Test
	void survivesApiThrottlingAndSkipsCorruptRecords() {
		JobExecution execution = syncJobService.startSync("test");

		assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		StepExecution step = StepsOf.fetch(execution);
		assertThat(step.getSkipCount()).isGreaterThanOrEqualTo(2);                 // blank title + rating 42, per list
		assertThat(movies.findByTmdbId(278L)).isEmpty();                          // the corrupt ones never got in ...
		assertThat(movies.findByTmdbId(238L)).isEmpty();
		assertThat(movies.count()).isEqualTo(43);                                  // ... everything else (45 - 2) did

		Integer rejected = jdbc.queryForObject("select count(*) from rejected_record", Integer.class);
		assertThat(rejected).isEqualTo((int) step.getSkipCount());                 // one dead-letter row per skip
		var reasons = jdbc.queryForList("select distinct reason from rejected_record", String.class);
		assertThat(reasons).anyMatch(r -> r.contains("title is blank")).anyMatch(r -> r.contains("out of range"));
		assertThat(jdbc.queryForList("select distinct stage from rejected_record", String.class))
				.containsExactly("PROCESS");
		assertThat(jdbc.queryForObject("select count(*) from rejected_record where job_execution_id = ?",
				Integer.class, execution.getId())).isEqualTo(rejected);
	}
}
