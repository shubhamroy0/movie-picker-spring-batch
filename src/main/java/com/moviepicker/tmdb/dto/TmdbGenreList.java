package com.moviepicker.tmdb.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Response of {@code /3/genre/movie/list}: {@code {"genres":[{"id":28,"name":"Action"},...]}}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbGenreList(List<TmdbGenre> genres) {

	public TmdbGenreList {
		genres = genres == null ? List.of() : List.copyOf(genres);
	}
}
