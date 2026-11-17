package com.moviepicker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Switches that make the MOCK profile misbehave on purpose, so every fault-tolerance feature can be watched live.
 * Set them as env vars in PowerShell, e.g. {@code $env:MOVIEPICKER_MOCK_RATELIMITCALLS = "2"}.
 */
@ConfigurationProperties(prefix = "moviepicker.mock")
public record MockProperties(
		/** Each new JobInstance sees "later" data (new movies, changed numbers). */
		@DefaultValue("true") boolean rotateData,
		/** The first N list/page fetches answer "429 Too Many Requests" (with Retry-After) -> client retry kicks in. */
		@DefaultValue("0") int rateLimitCalls,
		/** The next N fetches answer "503" (no Retry-After) -> exponential backoff. */
		@DefaultValue("0") int serverErrorCalls,
		/** Two movies (ids 278 and 238) come back corrupt (blank title / rating 42) -> processor exception -> SKIP. */
		@DefaultValue("false") boolean corruptRecords,
		/** e.g. "popular:2": fetching that list/page throws a NON-retryable error ONCE -> job FAILED -> restart demo. */
		@DefaultValue("") String failOnceAt,
		/** Artificial delay (ms) per list/page fetch: makes runs slow enough to pause, stop and observe overlap. */
		@DefaultValue("0") long latencyMillis) {

	public static MockProperties none() {
		return new MockProperties(true, 0, 0, false, "", 0);
	}
}
