package com.moviepicker.tmdb.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response of {@code /3/movie/{id}}: the list fields PLUS details that list endpoints do not return
 * (runtime, tagline). That is exactly why the job has a second "enrichDetails" step.
 * Detail responses carry full {@code genres} objects instead of {@code genre_ids}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbMovieDetail(
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
		Integer runtime,
		String tagline,
		List<TmdbGenre> genres) {
}
