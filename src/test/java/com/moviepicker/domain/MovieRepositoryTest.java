package com.moviepicker.domain;

import java.time.Instant;

import com.moviepicker.repository.GenreRepository;
import com.moviepicker.repository.MovieRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @DataJpaTest loads ONLY the JPA slice (entities, repositories, an in-memory DB) - much faster than @SpringBootTest.
 * Each test runs in a transaction that is rolled back afterwards, so tests do not leak data into each other.
 */
@DataJpaTest
class MovieRepositoryTest {

	@Autowired
	MovieRepository movies;

	@Autowired
	GenreRepository genres;

	@Test
	void tmdbIdIsUnique() {
		movies.saveAndFlush(new Movie(550L, "Fight Club", Instant.now()));
		assertThatThrownBy(() -> movies.saveAndFlush(new Movie(550L, "Duplicate", Instant.now())))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void versionIsIncrementedOnUpdate() {
		Movie m = movies.saveAndFlush(new Movie(603L, "The Matrix", Instant.now()));
		assertThat(m.getVersion()).isZero();

		m.setTitle("The Matrix (renamed)");
		movies.saveAndFlush(m);

		assertThat(m.getVersion()).isEqualTo(1);   // Hibernate: UPDATE ... SET version=1 WHERE id=? AND version=0
	}

	@Test
	void manyToManyGenresAreStoredAndFound() {
		Genre action = genres.save(new Genre(28, "Action"));
		Movie m = new Movie(155L, "The Dark Knight", Instant.now());
		m.getGenres().add(action);
		m.getSourceLists().add("popular");
		movies.saveAndFlush(m);

		assertThat(movies.findByTmdbId(155L)).isPresent();
		assertThat(movies.findAllByTmdbIdIn(java.util.List.of(155L, 999L))).hasSize(1);
	}
}
