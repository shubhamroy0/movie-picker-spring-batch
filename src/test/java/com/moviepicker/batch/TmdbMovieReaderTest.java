package com.moviepicker.batch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.moviepicker.tmdb.MockTmdbClient;
import com.moviepicker.tmdb.TmdbList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit tests of the reader: no Spring, no database. The restart test is the ItemStream contract in action. */
class TmdbMovieReaderTest {

	private MockTmdbClient client;

	@BeforeEach
	void setUp() throws IOException {
		client = new MockTmdbClient(JsonMapper.builder().build(), com.moviepicker.config.MockProperties.none());
	}

	private TmdbMovieReader reader(int pagesPerList) {
		return new TmdbMovieReader(client, List.of(TmdbList.values()), pagesPerList);
	}

	private static List<SourcedMovie> drain(TmdbMovieReader r) {
		List<SourcedMovie> all = new ArrayList<>();
		SourcedMovie m;
		while ((m = r.read()) != null) {
			all.add(m);
		}
		return all;
	}

	@Test
	void readsEveryListAndPageThenReturnsNull() {
		TmdbMovieReader r = reader(3);
		r.open(new ExecutionContext());

		List<SourcedMovie> all = drain(r);

		// gen 0: popular=47, top_rated=(movies with >=5000 votes), now_playing=(year>=2010 or undated), trending=30
		assertThat(all).isNotEmpty();
		assertThat(all.stream().map(SourcedMovie::list).distinct()).containsExactlyInAnyOrder(TmdbList.values());
		assertThat(all.stream().filter(m -> m.list() == TmdbList.POPULAR)).hasSize(47);
		assertThat(all.stream().filter(m -> m.list() == TmdbList.TRENDING_DAY)).hasSize(30);
		assertThat(r.read()).isNull();   // stays at end of input
	}

	@Test
	void ranksArePositionsWithinTheListAcrossPages() {
		TmdbMovieReader r = reader(3);
		r.open(new ExecutionContext());

		List<Integer> popularRanks = drain(r).stream().filter(m -> m.list() == TmdbList.POPULAR)
				.map(SourcedMovie::rank).toList();

		assertThat(popularRanks).first().isEqualTo(1);
		assertThat(popularRanks).last().isEqualTo(47);                      // page 3 continues from 41
		assertThat(popularRanks).doesNotHaveDuplicates();
	}

	@Test
	void pagesPerListLimitsHowDeepWeGo() {
		TmdbMovieReader r = reader(1);
		r.open(new ExecutionContext());

		List<SourcedMovie> all = drain(r);

		assertThat(all.stream().filter(m -> m.list() == TmdbList.POPULAR)).hasSize(20);   // only page 1
		assertThat(all).allMatch(m -> m.page() == 1);
	}

	@Test
	void restartResumesExactlyWhereTheLastCommittedChunkStopped() {
		// Reference: a complete uninterrupted read.
		TmdbMovieReader whole = reader(3);
		whole.open(new ExecutionContext());
		List<SourcedMovie> expected = drain(whole);

		// "Run 1": read 25 items (crosses a page boundary inside the first list), then the framework calls update()
		// at the chunk commit, then the JVM "crashes" (we simply drop the reader without finishing).
		ExecutionContext saved = new ExecutionContext();
		TmdbMovieReader run1 = reader(3);
		run1.open(saved);
		List<SourcedMovie> firstPart = new ArrayList<>();
		for (int i = 0; i < 25; i++) {
			firstPart.add(run1.read());
		}
		run1.update(saved);
		assertThat(saved.getInt(TmdbMovieReader.KEY_PAGE)).isEqualTo(2);
		assertThat(saved.getInt(TmdbMovieReader.KEY_OFFSET)).isEqualTo(5);

		// "Run 2" (restart): a NEW reader instance opened with the saved context continues with item #26.
		TmdbMovieReader run2 = reader(3);
		run2.open(saved);
		List<SourcedMovie> rest = drain(run2);

		List<SourcedMovie> stitched = new ArrayList<>(firstPart);
		stitched.addAll(rest);
		assertThat(stitched).isEqualTo(expected);   // nothing lost, nothing duplicated
	}
}
