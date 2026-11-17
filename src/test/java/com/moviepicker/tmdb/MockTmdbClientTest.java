package com.moviepicker.tmdb;

import java.io.IOException;
import java.util.List;

import com.moviepicker.tmdb.dto.TmdbMovieDetail;
import com.moviepicker.tmdb.dto.TmdbMoviePage;
import com.moviepicker.tmdb.dto.TmdbMovieSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Plain unit test (no Spring context): fast, and shows the fixtures deliver "inserts" and "updates" over time. */
class MockTmdbClientTest {

	private MockTmdbClient client;

	@BeforeEach
	void setUp() throws IOException {
		client = new MockTmdbClient(JsonMapper.builder().build(), com.moviepicker.config.MockProperties.none());
	}

	@Test
	void popularListIsPaginatedInPagesOfTwenty() {
		TmdbMoviePage p1 = client.fetchPage(TmdbList.POPULAR, 1);
		TmdbMoviePage p2 = client.fetchPage(TmdbList.POPULAR, 2);
		TmdbMoviePage p3 = client.fetchPage(TmdbList.POPULAR, 3);
		TmdbMoviePage p4 = client.fetchPage(TmdbList.POPULAR, 4);

		assertThat(p1.results()).hasSize(20);
		assertThat(p2.results()).hasSize(20);
		assertThat(p3.results()).hasSize(7);          // 47 visible movies in generation 0
		assertThat(p4.results()).isEmpty();           // past the last page -> empty, like TMDB
		assertThat(p1.totalPages()).isEqualTo(3);
		assertThat(p1.totalResults()).isEqualTo(47);
	}

	@Test
	void everyListIsNonEmptyAndOrderedDifferently() {
		for (TmdbList list : TmdbList.values()) {
			assertThat(client.fetchPage(list, 1).results()).as(list.name()).isNotEmpty();
		}
		List<Long> popular = ids(client.fetchPage(TmdbList.POPULAR, 1));
		List<Long> topRated = ids(client.fetchPage(TmdbList.TOP_RATED, 1));
		assertThat(popular).isNotEqualTo(topRated);
	}

	@Test
	void nextGenerationRevealsNewMoviesAndChangesNumbers() {
		TmdbMoviePage before = client.fetchPage(TmdbList.POPULAR, 1);
		long newestIdBefore = 0;
		assertThat(before.totalResults()).isEqualTo(47);

		client.advanceGeneration();

		TmdbMoviePage after = client.fetchPage(TmdbList.POPULAR, 1);
		assertThat(after.totalResults()).isEqualTo(49);   // +2 INSERTS on the next sync
		TmdbMovieSummary sameMovieBefore = before.results().get(0);
		TmdbMovieSummary sameMovieAfter = client.fetchPage(TmdbList.POPULAR, 1).results().stream()
				.filter(m -> m.id() == sameMovieBefore.id()).findFirst().orElseThrow();
		assertThat(sameMovieAfter.popularity()).isNotEqualTo(sameMovieBefore.popularity());   // UPDATE on the next sync
		assertThat(newestIdBefore).isZero();
	}

	@Test
	void trendingOrderChangesBetweenGenerations() {
		List<Long> gen0 = ids(client.fetchPage(TmdbList.TRENDING_DAY, 1));
		client.advanceGeneration();
		List<Long> gen1 = ids(client.fetchPage(TmdbList.TRENDING_DAY, 1));
		assertThat(gen1).isNotEqualTo(gen0);
	}

	@Test
	void listsContainTheJunkRecordsTheProcessorMustFilter() {
		List<TmdbMovieSummary> all = client.fetchPage(TmdbList.POPULAR, 1).results();
		all = concat(all, client.fetchPage(TmdbList.POPULAR, 2).results());
		all = concat(all, client.fetchPage(TmdbList.POPULAR, 3).results());
		assertThat(all).anyMatch(TmdbMovieSummary::adult);
		assertThat(all).anyMatch(m -> m.releaseDate() == null || m.releaseDate().isBlank());
	}

	@Test
	void detailHasRuntimeAndGenresButListsDoNot() {
		TmdbMovieDetail d = client.fetchDetail(27205);
		assertThat(d.title()).isEqualTo("Inception");
		assertThat(d.runtime()).isEqualTo(148);
		assertThat(d.genres()).extracting("name").contains("Science Fiction");
		assertThatThrownBy(() -> client.fetchDetail(-1)).isInstanceOf(TmdbNotFoundException.class);
	}

	private static List<Long> ids(TmdbMoviePage page) {
		return page.results().stream().map(TmdbMovieSummary::id).toList();
	}

	private static <T> List<T> concat(List<T> a, List<T> b) {
		return java.util.stream.Stream.concat(a.stream(), b.stream()).toList();
	}
}
