package com.moviepicker.tmdb;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.moviepicker.config.MockProperties;
import com.moviepicker.tmdb.dto.TmdbGenre;
import com.moviepicker.tmdb.dto.TmdbGenreList;
import com.moviepicker.tmdb.dto.TmdbMovieDetail;
import com.moviepicker.tmdb.dto.TmdbMoviePage;
import com.moviepicker.tmdb.dto.TmdbMovieSummary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * "mock" profile: serves TMDB-shaped data from src/main/resources/fixtures/ - no network, no token.
 *
 * DESIGN (deliberate, so you can WATCH inserts vs updates happen): instead of dozens of static page files we keep ONE
 * pool of 54 movies (fixtures/movies.json) and derive every list/page from it at request time. A "generation"
 * counter simulates time passing between sync runs:
 *   - each generation reveals 2 more movies from the pool     -> next sync INSERTS new rows
 *   - each generation jitters popularity / vote counts        -> next sync UPDATES existing rows
 *   - each generation reshuffles the trending order           -> TrendingSnapshot ranks change
 * Generation 0 returns the pool exactly as recorded. The batch job advances the generation once per run (Phase 2/4).
 * The fixture data is illustrative (real-looking titles, approximate numbers, our own one-line overviews), not an
 * authoritative copy of TMDB data. Two junk records (an adult title and one without release date) are included on
 * purpose to exercise the processor's filters.
 */
@Component("rawTmdbClient")
@Profile("mock")
public class MockTmdbClient implements TmdbClient {

	static final int PAGE_SIZE = 20;
	static final int BASE_VISIBLE = 47;          // movies visible in generation 0 (the rest are "new arrivals")
	static final int NEW_PER_GENERATION = 2;
	private static final int TRENDING_SIZE = 30;
	private static final int TOP_RATED_MIN_VOTES = 5000;
	private static final int NOW_PLAYING_FROM_YEAR = 2010;

	/** Shape of one entry in fixtures/movies.json (genre ids only, plus the detail-only fields). */
	@JsonIgnoreProperties(ignoreUnknown = true)
	private record PoolEntry(
			long id, String title,
			@JsonProperty("original_title") String originalTitle,
			String overview,
			@JsonProperty("release_date") String releaseDate,
			@JsonProperty("original_language") String originalLanguage,
			double popularity,
			@JsonProperty("vote_average") double voteAverage,
			@JsonProperty("vote_count") int voteCount,
			@JsonProperty("poster_path") String posterPath,
			boolean adult, Integer runtime, String tagline,
			@JsonProperty("genre_ids") List<Integer> genreIds) {
	}

	private final List<PoolEntry> pool;
	private final List<TmdbGenre> genres;
	private final Map<Integer, String> genreNames;
	private final AtomicInteger generation = new AtomicInteger();
	private final MockProperties faults;
	private final AtomicInteger fetchCalls = new AtomicInteger();
	private final AtomicBoolean failOnceArmed;

	public MockTmdbClient(JsonMapper jsonMapper, MockProperties faults) throws IOException {
		this.faults = faults;
		this.failOnceArmed = new AtomicBoolean(!faults.failOnceAt().isBlank());
		try (InputStream in = new ClassPathResource("fixtures/movies.json").getInputStream()) {
			this.pool = jsonMapper.readValue(in, new TypeReference<List<PoolEntry>>() { });
		}
		try (InputStream in = new ClassPathResource("fixtures/genres.json").getInputStream()) {
			this.genres = jsonMapper.readValue(in, TmdbGenreList.class).genres();
		}
		this.genreNames = genres.stream().collect(Collectors.toMap(TmdbGenre::id, TmdbGenre::name));
	}

	/** Simulates "time passes": the next calls see new movies and changed numbers. */
	public int advanceGeneration() {
		return generation.incrementAndGet();
	}

	/** Pins the simulated "time". Used by MockDataRotationListener so a run's data depends on its JobInstance. */
	public void setGeneration(int gen) {
		generation.set(Math.max(0, gen));
	}

	public int generation() {
		return generation.get();
	}

	@Override
	public List<TmdbGenre> genres() {
		return genres;
	}

	@Override
	public TmdbMoviePage fetchPage(TmdbList list, int page) {
		if (page < 1) {
			throw new IllegalArgumentException("TMDB pages are 1-based, got " + page);
		}
		injectFaults(list, page);
		int gen = generation.get();
		List<PoolEntry> ordered = switch (list) {
			case POPULAR -> visible(gen).stream()
					.sorted(byDescending(e -> adjustedPopularity(e, gen))).toList();
			case TOP_RATED -> visible(gen).stream()
					.filter(e -> adjustedVotes(e, gen) >= TOP_RATED_MIN_VOTES)
					.sorted(byDescending(e -> adjustedRating(e, gen) * 100_000 + adjustedVotes(e, gen))).toList();
			case NOW_PLAYING -> visible(gen).stream()
					.filter(recentOrUndated())
					.sorted(Comparator.comparing((PoolEntry e) -> e.releaseDate() == null ? "" : e.releaseDate())
							.reversed().thenComparingLong(PoolEntry::id)).toList();
			case TRENDING_DAY -> visible(gen).stream()
					.sorted(byDescending(e -> adjustedPopularity(e, gen) * (1 + 0.5 * jitter(e.id(), gen, 3))))
					.limit(TRENDING_SIZE).toList();
		};
		int from = (page - 1) * PAGE_SIZE;
		List<TmdbMovieSummary> results = from >= ordered.size() ? List.of()
				: ordered.subList(from, Math.min(from + PAGE_SIZE, ordered.size())).stream()
						.map(e -> toSummary(e, gen)).toList();
		int totalPages = Math.max(1, (int) Math.ceil(ordered.size() / (double) PAGE_SIZE));
		return new TmdbMoviePage(page, results, totalPages, ordered.size());
	}

	@Override
	public TmdbMovieDetail fetchDetail(long tmdbId) {
		int gen = generation.get();
		return visible(gen).stream().filter(e -> e.id() == tmdbId).findFirst()
				.map(e -> toDetail(e, gen))
				.orElseThrow(() -> new TmdbNotFoundException(tmdbId));
	}

	// ---------------------------------------------------------------- helpers

	private List<PoolEntry> visible(int gen) {
		int n = Math.min(pool.size(), BASE_VISIBLE + gen * NEW_PER_GENERATION);
		return pool.subList(0, n);
	}

	private static Predicate<PoolEntry> recentOrUndated() {
		return e -> e.releaseDate() == null || e.releaseDate().isBlank()
				|| Integer.parseInt(e.releaseDate().substring(0, 4)) >= NOW_PLAYING_FROM_YEAR;
	}

	private static Comparator<PoolEntry> byDescending(ToDoubleFunction<PoolEntry> score) {
		return Comparator.comparingDouble(score).reversed().thenComparingLong(PoolEntry::id);
	}

	/** Deterministic pseudo-random value in [-1, 1] for (movie, generation, salt): same inputs -> same output. */
	private static double jitter(long id, int gen, int salt) {
		return new Random(id * 31 + gen * 1_000_003L + salt).nextDouble() * 2 - 1;
	}

	private static double adjustedPopularity(PoolEntry e, int gen) {
		return gen == 0 ? e.popularity() : round1(e.popularity() * (1 + 0.15 * jitter(e.id(), gen, 1)));
	}

	private static double adjustedRating(PoolEntry e, int gen) {
		if (gen == 0) {
			return e.voteAverage();
		}
		double r = e.voteAverage() + 0.1 * Math.round(jitter(e.id(), gen, 2) * 2);
		return Math.max(0, Math.min(10, round1(r)));
	}

	private static int adjustedVotes(PoolEntry e, int gen) {
		return e.voteCount() + (int) Math.round(e.voteCount() * 0.002 * gen);
	}

	private static double round1(double v) {
		return Math.round(v * 10) / 10.0;
	}

	private TmdbMovieSummary toSummary(PoolEntry e, int gen) {
		String title = e.title();
		double rating = adjustedRating(e, gen);
		if (faults.corruptRecords()) {              // simulate bad upstream data (see MockProperties)
			if (e.id() == 278) {
				title = "";
			}
			if (e.id() == 238) {
				rating = 42.0;
			}
		}
		return new TmdbMovieSummary(e.id(), title, e.originalTitle(), e.overview(), e.releaseDate(),
				e.originalLanguage(), adjustedPopularity(e, gen), rating, adjustedVotes(e, gen),
				e.posterPath(), e.adult(), e.genreIds());
	}

	/** Simulated upstream problems, in the order: 429s, then 503s, then the one-shot permanent crash. */
	private void injectFaults(TmdbList list, int page) {
		if (faults.latencyMillis() > 0) {
			try {
				Thread.sleep(faults.latencyMillis());
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while simulating latency", e);
			}
		}
		int call = fetchCalls.incrementAndGet();
		if (call <= faults.rateLimitCalls()) {
			throw new TmdbTransientException("HTTP 429 Too Many Requests (simulated)",
					java.time.Duration.ofMillis(100), null);
		}
		if (call <= faults.rateLimitCalls() + faults.serverErrorCalls()) {
			throw new TmdbTransientException("HTTP 503 Service Unavailable (simulated)", null, null);
		}
		if (failOnceArmed.get() && (list.listName() + ":" + page).equals(faults.failOnceAt())
				&& failOnceArmed.compareAndSet(true, false)) {      // fires ONCE, so a restart succeeds
			throw new TmdbPermanentException("Simulated crash while fetching " + faults.failOnceAt());
		}
	}

	private TmdbMovieDetail toDetail(PoolEntry e, int gen) {
		List<TmdbGenre> g = e.genreIds().stream().map(id -> new TmdbGenre(id, genreNames.get(id))).toList();
		return new TmdbMovieDetail(e.id(), e.title(), e.originalTitle(), e.overview(), e.releaseDate(),
				e.originalLanguage(), adjustedPopularity(e, gen), adjustedRating(e, gen), adjustedVotes(e, gen),
				e.posterPath(), e.adult(), e.runtime(), e.tagline(), g);
	}
}
