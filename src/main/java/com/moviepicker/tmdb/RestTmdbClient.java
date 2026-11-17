package com.moviepicker.tmdb;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Supplier;

import com.moviepicker.config.TmdbProperties;
import com.moviepicker.tmdb.dto.TmdbGenre;
import com.moviepicker.tmdb.dto.TmdbMovieDetail;
import com.moviepicker.tmdb.dto.TmdbMoviePage;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Real implementation (any profile except "mock"): throttling + translation of HTTP failures into OUR exception
 * types. Retrying is done one layer up by {@link ResilientTmdbClient}.
 *
 * Translation table (this is what makes retry decisions possible without HTTP knowledge elsewhere):
 *   429                  -> TmdbTransientException (+ Retry-After)
 *   5xx                  -> TmdbTransientException
 *   timeout / IO error   -> TmdbTransientException   (RestClient wraps these in ResourceAccessException)
 *   404 on /movie/{id}   -> TmdbNotFoundException
 *   other 4xx (401, 403) -> TmdbPermanentException   (retrying a bad token only hammers the API)
 */
@Component("rawTmdbClient")
@Profile("!mock")
public class RestTmdbClient implements TmdbClient {

	private final TmdbApi api;
	private final TmdbProperties props;

	private long lastCallNanos = 0L;

	public RestTmdbClient(TmdbApi api, TmdbProperties props) {
		this.api = api;
		this.props = props;
	}

	@Override
	public List<TmdbGenre> genres() {
		return call(() -> api.genres(props.language()).genres());
	}

	@Override
	public TmdbMoviePage fetchPage(TmdbList list, int page) {
		String lang = props.language();
		String region = blankToNull(props.region());
		return call(() -> switch (list) {
			case TRENDING_DAY -> api.trendingDay(page, lang);   // trending has no 'region' parameter
			case NOW_PLAYING -> api.nowPlaying(page, lang, region);
			case POPULAR -> api.popular(page, lang, region);
			case TOP_RATED -> api.topRated(page, lang, region);
		});
	}

	@Override
	public TmdbMovieDetail fetchDetail(long tmdbId) {
		try {
			return call(() -> api.movie(tmdbId, props.language()));
		}
		catch (TmdbPermanentException e) {
			if (e.getCause() instanceof HttpClientErrorException.NotFound) {
				throw new TmdbNotFoundException(tmdbId);
			}
			throw e;
		}
	}

	private <T> T call(Supplier<T> request) {
		throttle();
		try {
			return request.get();
		}
		catch (HttpClientErrorException.TooManyRequests e) {
			throw new TmdbTransientException("HTTP 429 Too Many Requests", retryAfter(e.getResponseHeaders()), e);
		}
		catch (HttpServerErrorException e) {
			throw new TmdbTransientException("HTTP " + e.getStatusCode().value() + " from TMDB",
					retryAfter(e.getResponseHeaders()), e);
		}
		catch (ResourceAccessException e) {
			throw new TmdbTransientException("TMDB unreachable or timed out: " + e.getMessage(), null, e);
		}
		catch (RestClientResponseException e) {
			throw new TmdbPermanentException("HTTP " + e.getStatusCode().value() + " from TMDB (not retryable)", e);
		}
	}

	/** Retry-After is either delta-seconds ("120") or an HTTP date. Unparseable/absent -> null (use our backoff). */
	static Duration retryAfter(HttpHeaders headers) {
		String v = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
		if (v == null || v.isBlank()) {
			return null;
		}
		try {
			return Duration.ofSeconds(Long.parseLong(v.trim()));
		}
		catch (NumberFormatException notSeconds) {
			try {
				Duration d = Duration.between(ZonedDateTime.now(),
						ZonedDateTime.parse(v.trim(), DateTimeFormatter.RFC_1123_DATE_TIME));
				return d.isNegative() ? Duration.ZERO : d;
			}
			catch (RuntimeException ignored) {
				return null;
			}
		}
	}

	/**
	 * Keeps a minimum gap between two calls. synchronized because a partitioned step (Phase 8) calls us from several
	 * threads; the gap must be global, otherwise parallelism would multiply our request rate.
	 */
	private synchronized void throttle() {
		Duration gap = props.requestInterval();
		if (gap.isZero()) {
			return;
		}
		long waitNanos = lastCallNanos + gap.toNanos() - System.nanoTime();
		if (lastCallNanos != 0 && waitNanos > 0) {
			try {
				Thread.sleep(Duration.ofNanos(waitNanos));
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while throttling TMDB calls", e);
			}
		}
		lastCallNanos = System.nanoTime();
	}

	private static String blankToNull(String s) {
		return (s == null || s.isBlank()) ? null : s;
	}
}
