package com.moviepicker.service;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.moviepicker.domain.Genre;
import com.moviepicker.repository.GenreRepository;
import com.moviepicker.tmdb.TmdbClient;
import com.moviepicker.tmdb.dto.TmdbGenre;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cached id -> Genre lookup. Genres change maybe once a year, so we fetch them ONCE (at startup), upsert them into the
 * genre table and keep an in-memory map. The batch processor then resolves {@code genre_ids: [28, 12]} to Genre
 * entities with zero DB or HTTP calls per movie - important inside a hot loop of thousands of items.
 */
@Service
public class GenreCatalog {

	private final TmdbClient tmdbClient;
	private final GenreRepository genreRepository;

	/** volatile: replaced as a whole by refresh(), read by many threads (partitioned step later). Map is immutable. */
	private volatile Map<Integer, Genre> byTmdbId = Map.of();

	public GenreCatalog(TmdbClient tmdbClient, GenreRepository genreRepository) {
		this.tmdbClient = tmdbClient;
		this.genreRepository = genreRepository;
	}

	/**
	 * @Transactional: all upserts commit together, or none. Spring wraps this bean in a proxy, so the annotation only
	 * works when the method is called from ANOTHER bean (self-invocation bypasses the proxy - a classic interview trap).
	 */
	@Transactional
	public int refresh() {
		for (TmdbGenre g : tmdbClient.genres()) {
			genreRepository.findByTmdbGenreId(g.id()).ifPresentOrElse(
					existing -> existing.setName(g.name()),            // dirty checking -> UPDATE at commit, no save() needed
					() -> genreRepository.save(new Genre(g.id(), g.name())));
		}
		this.byTmdbId = genreRepository.findAll().stream()
				.collect(Collectors.toUnmodifiableMap(Genre::getTmdbGenreId, Function.identity()));
		return byTmdbId.size();
	}

	public Optional<Genre> find(int tmdbGenreId) {
		return Optional.ofNullable(byTmdbId.get(tmdbGenreId));
	}

	public int size() {
		return byTmdbId.size();
	}
}
