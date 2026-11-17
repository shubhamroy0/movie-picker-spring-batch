package com.moviepicker.tmdb;

/** A failure that retrying cannot fix (401/403 bad token, 400/422 bad request, a simulated crash). Never retried. */
public class TmdbPermanentException extends RuntimeException {

	public TmdbPermanentException(String message, Throwable cause) {
		super(message, cause);
	}

	public TmdbPermanentException(String message) {
		super(message);
	}
}
