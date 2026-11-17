package com.moviepicker.tmdb;

/** TMDB (or the mock) has no movie with this id (HTTP 404). Unchecked, like Spring's own exceptions. */
public class TmdbNotFoundException extends RuntimeException {

	public TmdbNotFoundException(long tmdbId) {
		super("TMDB has no movie with id " + tmdbId);
	}
}
