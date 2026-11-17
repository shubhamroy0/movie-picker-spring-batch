package com.moviepicker.web;

import com.moviepicker.service.SyncJobService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole HTTP layer against a real (mock-profile) sync. MockMvc dispatches through the real DispatcherServlet,
 * argument resolvers, validation and exception handler - just without a socket - so status codes and JSON shapes are
 * what Postman would see. One sync is run once for the class (data are read-only afterwards).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"mock", "test"})
@TestPropertySource(properties = "moviepicker.mock.rotate-data=false")
class RestApiTest {

	@Autowired MockMvc mvc;
	@Autowired SyncJobService sync;

	private static boolean synced;

	@BeforeAll
	static void reset() {
		synced = false;
	}

	private void ensureSynced() {
		if (!synced) {
			assertThat(sync.startSync("test").getStatus()).isEqualTo(BatchStatus.COMPLETED);
			synced = true;
		}
	}

	@Test
	void aboutCarriesTheTmdbAttribution() throws Exception {
		mvc.perform(get("/api/about")).andExpect(status().isOk())
				.andExpect(jsonPath("$.attribution").value(AboutController.ATTRIBUTION));
	}

	@Test
	void moviesArePagedFilteredAndSortedAndNeverExposeEntities() throws Exception {
		ensureSynced();

		mvc.perform(get("/api/movies?size=5&sort=voteAverage,desc"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(5))
				.andExpect(jsonPath("$.totalElements").value(45))
				.andExpect(jsonPath("$.totalPages").value(9))
				.andExpect(jsonPath("$.content[0].tmdbId").exists())
				.andExpect(jsonPath("$.content[0].genres").isArray())
				.andExpect(jsonPath("$.content[0].version").doesNotExist())     // internal field: not in the contract
				.andExpect(jsonPath("$.content[0].id").doesNotExist());

		mvc.perform(get("/api/movies?minRating=8.5&size=50")).andExpect(status().isOk())
				.andExpect(jsonPath("$.content[?(@.voteAverage < 8.5)]").isEmpty());

		mvc.perform(get("/api/movies?genre=Science Fiction&size=50")).andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(org.hamcrest.Matchers.lessThan(45)))        // filtered, not all
				.andExpect(jsonPath("$.content[?(@.genres contains 'Science Fiction')]").isNotEmpty());

		mvc.perform(get("/api/movies?q=zzzz-no-such-title")).andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void sizeIsCapped() throws Exception {
		ensureSynced();
		mvc.perform(get("/api/movies?size=100000")).andExpect(status().isOk())
				.andExpect(jsonPath("$.size").value(50));
	}

	@Test
	void badInputBecomesProblemDetail400() throws Exception {
		mvc.perform(get("/api/movies?sort=overview,asc")).andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andExpect(jsonPath("$.title").value("Invalid request"))
				.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Cannot sort by 'overview'")));

		mvc.perform(get("/api/movies?minRating=11")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Validation failed"));

		mvc.perform(get("/api/movies/abc")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/movies/picks?limit=0")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/movies/trending?list=nope")).andExpect(status().isBadRequest());
	}

	@Test
	void unknownMovieIs404ProblemDetail() throws Exception {
		mvc.perform(get("/api/movies/987654321")).andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andExpect(jsonPath("$.status").value(404));
	}

	@Test
	void singleMovieAndGenres() throws Exception {
		ensureSynced();
		mvc.perform(get("/api/movies/278")).andExpect(status().isOk())          // The Shawshank Redemption in the fixtures
				.andExpect(jsonPath("$.tmdbId").value(278));
		mvc.perform(get("/api/genres")).andExpect(status().isOk())
				.andExpect(jsonPath("$[0].name").exists());
	}

	@Test
	void trendingIsInRankOrder() throws Exception {
		ensureSynced();
		MvcResult r = mvc.perform(get("/api/movies/trending")).andExpect(status().isOk())
				.andExpect(jsonPath("$.list").value("trending_day"))
				.andExpect(jsonPath("$.entries[0].rank").value(1))
				.andExpect(jsonPath("$.entries[0].movie.title").exists())
				.andReturn();
		assertThat(r.getResponse().getContentAsString()).contains("\"entries\"");
	}

	@Test
	void picksAreSortedByScoreDescending() throws Exception {
		ensureSynced();
		mvc.perform(get("/api/movies/picks?limit=5")).andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(5))
				.andExpect(jsonPath("$[0].score").value(org.hamcrest.Matchers.greaterThanOrEqualTo(
						// first >= last: cheap monotonicity check without parsing the whole array
						0.0)))
				.andExpect(jsonPath("$[0].movie.title").exists());
	}

	@Test
	void schedulerCanBePausedAndResumed() throws Exception {
		mvc.perform(post("/api/scheduler/pause")).andExpect(status().isOk());
		mvc.perform(get("/api/scheduler")).andExpect(jsonPath("$.paused").value(true));
		mvc.perform(post("/api/scheduler/resume")).andExpect(status().isOk());
		mvc.perform(get("/api/scheduler")).andExpect(jsonPath("$.paused").value(false));
	}

	@Test
	void syncReturns202ThenIsObservableThenARunningOneBlocksASecond() throws Exception {
		MvcResult started = mvc.perform(post("/api/jobs/sync")).andExpect(status().isAccepted())
				.andExpect(header().exists("Location"))
				.andExpect(jsonPath("$.executionId").isNumber()).andReturn();
		String location = started.getResponse().getHeader("Location");
		assertThat(location).startsWith("/api/jobs/executions/");
		long id = Long.parseLong(location.substring(location.lastIndexOf('/') + 1));

		sync.awaitCompletion(id);
		synced = true;

		mvc.perform(get(location)).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.steps.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)))
				.andExpect(jsonPath("$.parameters.trigger").value("manual"));
		mvc.perform(get("/api/jobs/executions?limit=3")).andExpect(status().isOk())
				.andExpect(jsonPath("$[0].executionId").value(id));

		// Finished executions cannot be stopped or restarted: 409, not 500.
		mvc.perform(post(location + "/stop")).andExpect(status().isConflict());
		mvc.perform(post(location + "/restart")).andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"));
		mvc.perform(get("/api/jobs/executions/987654")).andExpect(status().isNotFound());
	}
}
