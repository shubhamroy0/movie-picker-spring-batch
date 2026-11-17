package com.moviepicker.batch;

import com.moviepicker.repository.MovieRepository;
import com.moviepicker.service.SyncJobService;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** Default mock behaviour (rotate-data=true): each new JobInstance sees "later" data -> inserts AND updates appear. */
@SpringBootTest
@ActiveProfiles({"mock", "test"})
class MovieSyncJobRotationTest {

	@Autowired SyncJobService syncJobService;
	@Autowired MovieRepository movies;

	@Test
	void secondRunInsertsTheNewArrivalsAndUpdatesChangedNumbers() {
		JobExecution first = syncJobService.startSync("test");
		long afterFirst = movies.count();

		JobExecution second = syncJobService.startSync("test");

		assertThat(first.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		var ctx = StepsOf.fetch(second).getExecutionContext();
		assertThat(ctx.getInt("writer.inserted")).isEqualTo(2);                // generation 1 reveals 2 new movies
		assertThat(ctx.getInt("writer.updated")).isPositive();                 // popularity/votes were jittered
		assertThat(movies.count()).isEqualTo(afterFirst + 2);
		assertThat(movies.countByDetailsFetchedAtIsNull()).isZero();           // the 2 new ones were enriched too
	}
}
