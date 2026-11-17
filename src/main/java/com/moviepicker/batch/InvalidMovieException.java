package com.moviepicker.batch;

/**
 * A record that is structurally present but not acceptable (blank title, rating out of range, unparseable date).
 * Different from a FILTERED item (adult, no release date): filtering is a normal business rule, silent and counted;
 * this is DATA CORRUPTION. Phase 3 makes the step SKIP these and record them in rejected_record.
 */
public class InvalidMovieException extends RuntimeException {

	private final long tmdbId;

	public InvalidMovieException(long tmdbId, String message) {
		super("Movie " + tmdbId + ": " + message);
		this.tmdbId = tmdbId;
	}

	public long tmdbId() {
		return tmdbId;
	}
}
