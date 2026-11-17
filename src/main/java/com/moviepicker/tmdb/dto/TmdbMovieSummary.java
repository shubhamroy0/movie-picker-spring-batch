package com.moviepicker.tmdb.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One element of a TMDB list response ({@code results[]}). A record = immutable DTO, no Lombok needed.
 *
 * Notes:
 *  - TMDB uses snake_case; our API uses camelCase, so we map per-field with @JsonProperty instead of changing the
 *    global naming strategy (which would also change what OUR REST API returns).
 *  - {@code releaseDate} is a String on purpose: TMDB sends "" (empty string) for unknown dates, and parsing that
 *    straight into LocalDate would blow up the whole page. The processor parses it and filters bad ones.
 *  - Primitive-wrapper types (Double, Integer) so a missing field becomes null instead of a silent 0.
 *  - ignoreUnknown: TMDB may add fields at any time; we must not break when it does.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbMovieSummary(
		long id,
		String title,
		@JsonProperty("original_title") String originalTitle,
		String overview,
		@JsonProperty("release_date") String releaseDate,
		@JsonProperty("original_language") String originalLanguage,
		Double popularity,
		@JsonProperty("vote_average") Double voteAverage,
		@JsonProperty("vote_count") Integer voteCount,
		@JsonProperty("poster_path") String posterPath,
		boolean adult,
		@JsonProperty("genre_ids") List<Integer> genreIds) {
}
