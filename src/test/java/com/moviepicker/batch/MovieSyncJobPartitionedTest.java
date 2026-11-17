package com.moviepicker.batch;

import java.util.List;

import com.moviepicker.repository.MovieRepository;
import com.moviepicker.service.SyncJobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Same data, same expectations as the non-partitioned job - the point of partitioning is that the RESULT is identical
 * and only the execution shape changes. Repeated because concurrency bugs (two workers inserting the same movie) are
 * timing-dependent: one green run proves little, several do.
 */
@SpringBootTest
@ActiveProfiles({"mock", "test"})
@TestPropertySource(properties = {"moviepicker.mock.rotate-data=false", "moviepicker.sync.partitioned=true"})
class MovieSyncJobPartitionedTest {

	@Autowired SyncJobService syncJobService;
	@Autowired MovieRepository movies;
	@Autowired JdbcTemplate jdbc;

	@BeforeEach
	void clean() {
		movies.deleteAll();
	}

	@RepeatedTest(5)
	void fourWorkersProduceTheSameResultAsOneThread() {
		JobExecution run = syncJobService.startSync("test");

		assertThat(run.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		List<String> names = StepsOf.names(run);
		assertThat(names).contains(SyncSteps.FETCH).contains(
				"fetchWorker:partition-trending_day", "fetchWorker:partition-now_playing",
				"fetchWorker:partition-popular", "fetchWorker:partition-top_rated");

		// No duplicates, every movie exactly once - guaranteed by the unique key + retry, not by luck.
		assertThat(movies.count()).isEqualTo(45);
		assertThat(jdbc.queryForList("select tmdb_id from movie", Long.class)).doesNotHaveDuplicates();

		// The manager aggregates its workers: totals equal the single-threaded run (143 read, 5 filtered).
		var manager = StepsOf.fetch(run);
		assertThat(manager.getReadCount()).isEqualTo(143);
		assertThat(manager.getFilterCount()).isEqualTo(5);
		assertThat(run.getExecutionContext().getInt("writer.inserted")).isEqualTo(45);   // promoted manager totals
		assertThat(StepsOf.step(run, "fetchWorker:partition-popular").getReadCount()).isPositive();

		// The business summary ignores worker rows (":" in the name) so nothing is double counted.
		assertThat(jdbc.queryForObject("select read_count from sync_run order by id desc limit 1", Integer.class))
				.isEqualTo(143);
	}
}
