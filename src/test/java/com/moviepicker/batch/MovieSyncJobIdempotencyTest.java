package com.moviepicker.batch;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import com.moviepicker.domain.Movie;
import com.moviepicker.repository.MovieRepository;
import com.moviepicker.service.SyncJobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end: real Spring context, real H2 (in memory), real job - only the TMDB client is the mock.
 * rotate-data=false => both runs see IDENTICAL input, so the second run must change nothing but timestamps.
 */
@SpringBootTest
@ActiveProfiles({"mock", "test"})
@TestPropertySource(properties = "moviepicker.mock.rotate-data=false")
class MovieSyncJobIdempotencyTest {

	/** Non-junk movies visible in mock generation 0 (47 visible minus the adult one and the one without date). */
	private static final int EXPECTED_MOVIES = 45;

	@Autowired SyncJobService syncJobService;
	@Autowired MovieRepository movies;
	@Autowired JdbcTemplate jdbc;
	@Autowired JobRepository jobRepository;

	@BeforeEach
	void cleanBusinessTables() {
		movies.deleteAll();
	}

	private static StepExecution fetchStep(JobExecution e) {
		return e.getStepExecutions().stream()
				.filter(s -> s.getStepName().equals(MovieSyncJobConfig.FETCH_STEP_NAME)).findFirst().orElseThrow();
	}

	private int rows(String table) {
		return jdbc.queryForObject("select count(*) from " + table, Integer.class);
	}

	@Test
	void firstRunInsertsEverything_secondRunInsertsNothing() {
		JobExecution first = syncJobService.startSync("test");

		assertThat(first.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		StepExecution step1 = fetchStep(first);
		assertThat(step1.getFilterCount()).isPositive();                       // adult + undated items were filtered
		assertThat(step1.getReadCount()).isEqualTo(step1.getWriteCount() + step1.getFilterCount());
		assertThat(step1.getExecutionContext().getInt("writer.inserted")).isEqualTo(EXPECTED_MOVIES);
		assertThat(step1.getExecutionContext().getInt("writer.updated")).isZero();
		assertThat(movies.count()).isEqualTo(EXPECTED_MOVIES);
		int genreLinks = rows("movie_genre");
		int sourceListRows = rows("movie_source_list");
		assertThat(genreLinks).isPositive();
		assertThat(sourceListRows).isGreaterThan(EXPECTED_MOVIES);              // most movies are in several lists

		JobExecution second = syncJobService.startSync("test");                // NEW JobInstance (new runAt) ...

		assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		assertThat(second.getJobInstanceId()).isNotEqualTo(first.getJobInstanceId());
		StepExecution step2 = fetchStep(second);
		assertThat(step2.getExecutionContext().getInt("writer.inserted")).isZero();      // ... but no duplicates
		assertThat(step2.getExecutionContext().getInt("writer.updated")).isZero();
		assertThat(step2.getExecutionContext().getInt("writer.unchanged")).isEqualTo(EXPECTED_MOVIES);
		assertThat(movies.count()).isEqualTo(EXPECTED_MOVIES);
		assertThat(rows("movie_genre")).isEqualTo(genreLinks);                 // join rows not rewritten/duplicated
		assertThat(rows("movie_source_list")).isEqualTo(sourceListRows);
		List<Long> ids = jdbc.queryForList("select tmdb_id from movie", Long.class);
		assertThat(ids).doesNotHaveDuplicates();
	}

	@Test
	void everySyncTouchesLastSyncedAtAndBumpsTheVersion() {
		syncJobService.startSync("test");
		Movie afterFirst = movies.findByTmdbId(27205L).orElseThrow();
		var firstSync = afterFirst.getLastSyncedAt();
		long v1 = afterFirst.getVersion();

		syncJobService.startSync("test");
		Movie afterSecond = movies.findByTmdbId(27205L).orElseThrow();

		assertThat(afterSecond.getLastSyncedAt()).isAfter(firstSync);
		assertThat(afterSecond.getVersion()).isGreaterThan(v1);               // @Version incremented by the UPDATE
		assertThat(afterSecond.getFirstSeenAt()).isEqualTo(afterFirst.getFirstSeenAt());   // set once, never changed
	}

	@Test
	void readerPositionIsPersistedInTheBatchMetadataTables() {
		JobExecution execution = syncJobService.startSync("test");

		// Idiomatic way: ask the JobRepository, which deserialises the context for us.
		StepExecution persisted = jobRepository.getLastStepExecution(execution.getJobInstance(),
				MovieSyncJobConfig.FETCH_STEP_NAME);
		assertThat(persisted.getExecutionContext().getInt("tmdb.reader.listIndex")).isEqualTo(4);   // all 4 lists consumed
		assertThat(persisted.getExecutionContext().getString("tmdb.reader.list")).isEqualTo("(done)");
		assertThat(persisted.getExecutionContext().getInt("writer.inserted")).isEqualTo(EXPECTED_MOVIES);

		// What is physically in the table: Base64 of a Java-serialised HashMap (NOT readable JSON/text).
		String raw = jdbc.queryForObject(
				"select short_context from batch_step_execution_context where step_execution_id = ?", String.class,
				persisted.getId());
		String decoded = new String(Base64.getDecoder().decode(raw), StandardCharsets.ISO_8859_1);
		assertThat(decoded).contains("tmdb.reader.page").contains("writer.inserted");
	}
}
