package com.moviepicker.tmdb.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** TMDB's paging envelope: {@code {"page":1,"results":[...],"total_pages":500,"total_results":10000}}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbMoviePage(
		int page,
		List<TmdbMovieSummary> results,
		@JsonProperty("total_pages") int totalPages,
		@JsonProperty("total_results") int totalResults) {

	public TmdbMoviePage {
		results = results == null ? List.of() : List.copyOf(results);
	}
}
