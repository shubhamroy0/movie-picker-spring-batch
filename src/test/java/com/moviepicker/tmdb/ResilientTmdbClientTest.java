package com.moviepicker.tmdb;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.moviepicker.config.TmdbProperties;
import com.moviepicker.tmdb.dto.TmdbGenre;
import com.moviepicker.tmdb.dto.TmdbMovieDetail;
import com.moviepicker.tmdb.dto.TmdbMoviePage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Retry/backoff rules in isolation: a fake delegate + a fake sleeper, so the test is instant and exact. */
class ResilientTmdbClientTest {

	private static final TmdbProperties.Retry RETRY =
			new TmdbProperties.Retry(4, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(5));

	/** Fails with the given exceptions in order, then succeeds. */
	static class FlakyClient implements TmdbClient {
		final List<RuntimeException> failures;
		final AtomicInteger calls = new AtomicInteger();

		FlakyClient(RuntimeException... failures) {
			this.failures = new ArrayList<>(List.of(failures));
		}

		@Override
		public TmdbMoviePage fetchPage(TmdbList list, int page) {
			int n = calls.getAndIncrement();
			if (n < failures.size()) {
				throw failures.get(n);
			}
			return new TmdbMoviePage(page, List.of(), 1, 0);
		}

		@Override public List<TmdbGenre> genres() { return List.of(); }
		@Override public TmdbMovieDetail fetchDetail(long id) { throw new UnsupportedOperationException(); }
	}

	private static TmdbTransientException transientError(Duration retryAfter) {
		return new TmdbTransientException("boom", retryAfter, null);
	}

	@Test
	void exponentialBackoffDoublesAndIsCapped() {
		FlakyClient flaky = new FlakyClient(transientError(null), transientError(null), transientError(null));
		List<Duration> sleeps = new ArrayList<>();
		ResilientTmdbClient client = new ResilientTmdbClient(flaky, RETRY, sleeps::add);

		client.fetchPage(TmdbList.POPULAR, 1);

		assertThat(flaky.calls).hasValue(4);
		assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));
		assertThat(client.backoff(10)).isEqualTo(Duration.ofSeconds(5));          // capped at maxBackoff
	}

	@Test
	void retryAfterHeaderWinsOverOurOwnBackoff() {
		FlakyClient flaky = new FlakyClient(transientError(Duration.ofSeconds(30)));
		List<Duration> sleeps = new ArrayList<>();

		new ResilientTmdbClient(flaky, RETRY, sleeps::add).fetchPage(TmdbList.POPULAR, 1);

		assertThat(sleeps).containsExactly(Duration.ofSeconds(30));
	}

	@Test
	void permanentErrorsAreNeverRetried() {
		FlakyClient flaky = new FlakyClient(new TmdbPermanentException("HTTP 401"));
		List<Duration> sleeps = new ArrayList<>();
		ResilientTmdbClient client = new ResilientTmdbClient(flaky, RETRY, sleeps::add);

		assertThatThrownBy(() -> client.fetchPage(TmdbList.POPULAR, 1)).isInstanceOf(TmdbPermanentException.class);

		assertThat(flaky.calls).hasValue(1);
		assertThat(sleeps).isEmpty();
	}

	@Test
	void givesUpAfterMaxAttemptsAndRethrowsTheLastError() {
		FlakyClient flaky = new FlakyClient(transientError(null), transientError(null), transientError(null),
				transientError(null), transientError(null));
		ResilientTmdbClient client = new ResilientTmdbClient(flaky, RETRY, d -> { });

		assertThatThrownBy(() -> client.fetchPage(TmdbList.POPULAR, 1)).isInstanceOf(TmdbTransientException.class);

		assertThat(flaky.calls).hasValue(4);   // maxAttempts = 4 TOTAL calls, not 4 retries
	}
}
