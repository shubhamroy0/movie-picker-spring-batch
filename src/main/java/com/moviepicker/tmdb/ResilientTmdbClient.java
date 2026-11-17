package com.moviepicker.tmdb;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.moviepicker.config.TmdbProperties;
import com.moviepicker.tmdb.dto.TmdbGenre;
import com.moviepicker.tmdb.dto.TmdbMovieDetail;
import com.moviepicker.tmdb.dto.TmdbMoviePage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * DECORATOR that adds retry with exponential backoff around ANY raw TmdbClient (real or mock). It is the @Primary
 * TmdbClient, so the rest of the app gets resilience without knowing about it.
 *
 * WHY HERE, and not "retry in the step"? In Batch's chunk model a READ is not replayed: a reader exception either
 * fails the step or is skipped. Retry (the step's retryPolicy) covers the PROCESSOR and WRITER. A flaky HTTP call lives
 * in the reader, so the retry must be inside the client. This is also where the Retry-After header is visible.
 *
 * RULES: retry only {@link TmdbTransientException}; wait = Retry-After if the server sent it, otherwise
 * initialBackoff * multiplier^(attempt-1), capped at maxBackoff; give up after maxAttempts and rethrow the last error.
 * (Spring Framework 7 also ships RetryTemplate; a hand-written loop is used because the delay is dynamic per failure.)
 */
@Primary
@Component
public class ResilientTmdbClient implements TmdbClient {

	private static final Logger log = LoggerFactory.getLogger(ResilientTmdbClient.class);

	private final TmdbClient delegate;
	private final TmdbProperties.Retry retry;
	private final Consumer<Duration> sleeper;

	@Autowired
	public ResilientTmdbClient(@Qualifier("rawTmdbClient") TmdbClient delegate, TmdbProperties props) {
		this(delegate, props.retry(), ResilientTmdbClient::sleep);
	}

	/** Test seam: inject a fake sleeper so tests run instantly and can record the delays. */
	public ResilientTmdbClient(TmdbClient delegate, TmdbProperties.Retry retry, Consumer<Duration> sleeper) {
		this.delegate = delegate;
		this.retry = retry;
		this.sleeper = sleeper;
	}

	@Override
	public List<TmdbGenre> genres() {
		return withRetry("genres", delegate::genres);
	}

	@Override
	public TmdbMoviePage fetchPage(TmdbList list, int page) {
		return withRetry(list.listName() + " page " + page, () -> delegate.fetchPage(list, page));
	}

	@Override
	public TmdbMovieDetail fetchDetail(long tmdbId) {
		return withRetry("detail " + tmdbId, () -> delegate.fetchDetail(tmdbId));
	}

	private <T> T withRetry(String what, Supplier<T> call) {
		for (int attempt = 1; ; attempt++) {
			try {
				return call.get();
			}
			catch (TmdbTransientException e) {
				if (attempt >= retry.maxAttempts()) {
					log.error("TMDB {} failed after {} attempt(s): {}", what, attempt, e.getMessage());
					throw e;
				}
				Duration wait = e.retryAfter() != null ? e.retryAfter() : backoff(attempt);
				log.warn("TMDB {} failed (attempt {}/{}): {} -> retrying in {} ms{}", what, attempt,
						retry.maxAttempts(), e.getMessage(), wait.toMillis(),
						e.retryAfter() != null ? " (Retry-After honoured)" : "");
				sleeper.accept(wait);
			}
		}
	}

	Duration backoff(int attempt) {
		double millis = retry.initialBackoff().toMillis() * Math.pow(retry.multiplier(), attempt - 1);
		return Duration.ofMillis((long) Math.min(millis, retry.maxBackoff().toMillis()));
	}

	private static void sleep(Duration d) {
		try {
			Thread.sleep(d);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while backing off", e);
		}
	}
}
