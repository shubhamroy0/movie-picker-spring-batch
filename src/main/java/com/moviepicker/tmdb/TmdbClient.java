package com.moviepicker.tmdb;

import java.util.List;

import com.moviepicker.tmdb.dto.TmdbGenre;
import com.moviepicker.tmdb.dto.TmdbMovieDetail;
import com.moviepicker.tmdb.dto.TmdbMoviePage;

/**
 * Our OWN abstraction over "something that serves TMDB data". The rest of the app (batch reader, enrichment step,
 * genre catalog) depends only on this interface, never on HTTP. Two implementations:
 *  - {@link RestTmdbClient}  (profile != mock) real HTTP via RestClient
 *  - {@link MockTmdbClient}  (profile mock)    JSON fixtures, no network
 * That is the Dependency Inversion principle in practice, and it is what makes the app testable without a token.
 */
public interface TmdbClient {

	List<TmdbGenre> genres();

	/** @param page 1-based, like TMDB */
	TmdbMoviePage fetchPage(TmdbList list, int page);

	TmdbMovieDetail fetchDetail(long tmdbId);
}
