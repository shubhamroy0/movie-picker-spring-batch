package com.moviepicker.repository;

import java.util.Optional;

import com.moviepicker.domain.Genre;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GenreRepository extends JpaRepository<Genre, Long> {

	Optional<Genre> findByTmdbGenreId(int tmdbGenreId);
}
