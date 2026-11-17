package com.moviepicker.batch;

import com.moviepicker.tmdb.TmdbList;
import com.moviepicker.tmdb.dto.TmdbMovieSummary;

/**
 * What the READER emits: the movie plus WHERE it came from (which list, which page, which rank in that list).
 * We need the provenance downstream: Movie.sourceLists records the list, and the trending snapshot step needs the rank.
 * Rank = position in the list across pages, 1-based. TMDB pages hold 20 results, so rank = (page-1)*20 + index + 1.
 */
public record SourcedMovie(TmdbList list, int page, int rank, TmdbMovieSummary movie) {
}
