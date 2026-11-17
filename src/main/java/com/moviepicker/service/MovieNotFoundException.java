package com.moviepicker.service;

public class MovieNotFoundException extends RuntimeException {

	public MovieNotFoundException(long tmdbId) {
		super("No movie with tmdbId " + tmdbId);
	}
}
