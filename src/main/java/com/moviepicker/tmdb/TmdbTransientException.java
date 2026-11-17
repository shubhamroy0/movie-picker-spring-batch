package com.moviepicker.tmdb;

import java.time.Duration;

/**
 * A failure that is worth RETRYING: HTTP 429, HTTP 5xx, timeouts, connection resets. {@code retryAfter} carries the
 * server's Retry-After hint when it sent one (null otherwise).
 * Contrast with {@link TmdbPermanentException}: retrying a 401 (bad token) or a 400 can never succeed, so we fail fast.
 */
public class TmdbTransientException extends RuntimeException {

	private final Duration retryAfter;

	public TmdbTransientException(String message, Duration retryAfter, Throwable cause) {
		super(message, cause);
		this.retryAfter = retryAfter;
	}

	public Duration retryAfter() {
		return retryAfter;
	}
}
