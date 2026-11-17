package com.moviepicker.batch;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.moviepicker.domain.Genre;
import com.moviepicker.domain.Movie;
import com.moviepicker.service.GenreCatalog;
import com.moviepicker.tmdb.TmdbList;
import com.moviepicker.tmdb.dto.TmdbMovieSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MovieItemProcessorTest {

	private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

	private MovieItemProcessor processor;

	@BeforeEach
	void setUp() {
		GenreCatalog catalog = Mockito.mock(GenreCatalog.class);
		Mockito.when(catalog.find(28)).thenReturn(Optional.of(new Genre(28, "Action")));
		Mockito.when(catalog.find(878)).thenReturn(Optional.of(new Genre(878, "Science Fiction")));
		Mockito.when(catalog.find(999)).thenReturn(Optional.empty());
		processor = new MovieItemProcessor(catalog, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private static SourcedMovie item(boolean adult, String date, String title, Double voteAverage, List<Integer> genres) {
		return new SourcedMovie(TmdbList.POPULAR, 1, 1, new TmdbMovieSummary(27205, title, "Orig", "Overview", date,
				"en", 70.5, voteAverage, 36000, "/p.jpg", adult, genres));
	}

	@Test
	void mapsFieldsAndResolvesGenres() {
		Movie m = processor.process(item(false, "2010-07-15", " Inception ", 8.4, List.of(28, 878, 999)));

		assertThat(m.getTmdbId()).isEqualTo(27205L);
		assertThat(m.getTitle()).isEqualTo("Inception");                          // trimmed
		assertThat(m.getReleaseDate()).isEqualTo(LocalDate.of(2010, 7, 15));
		assertThat(m.getVoteAverage()).isEqualTo(8.4);
		assertThat(m.getSourceLists()).containsExactly("popular");
		assertThat(m.getGenres()).extracting(Genre::getName).containsExactlyInAnyOrder("Action", "Science Fiction");
		assertThat(m.getFirstSeenAt()).isEqualTo(NOW);                           // unknown genre 999 silently ignored
	}

	@Test
	void adultTitlesAreFilteredByReturningNull() {
		assertThat(processor.process(item(true, "2019-02-14", "X", 5.0, List.of()))).isNull();
	}

	@Test
	void missingOrBlankReleaseDateIsFilteredByReturningNull() {
		assertThat(processor.process(item(false, "", "X", 5.0, List.of()))).isNull();
		assertThat(processor.process(item(false, null, "X", 5.0, List.of()))).isNull();
	}

	@Test
	void invalidDataThrowsInsteadOfFiltering() {
		assertThatThrownBy(() -> processor.process(item(false, "2010-07-15", "  ", 5.0, List.of())))
				.isInstanceOf(InvalidMovieException.class).hasMessageContaining("title is blank");
		assertThatThrownBy(() -> processor.process(item(false, "2010-07-15", "X", 11.0, List.of())))
				.isInstanceOf(InvalidMovieException.class).hasMessageContaining("out of range");
		assertThatThrownBy(() -> processor.process(item(false, "15/07/2010", "X", 5.0, List.of())))
				.isInstanceOf(InvalidMovieException.class).hasMessageContaining("unparseable");
	}
}
