package com.moviepicker.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.BatchSize;

/**
 * A movie as we store it. Two identities, on purpose:
 *
 *  - {@code id}      : internal surrogate key (DB sequence). Never exposed by the REST API.
 *  - {@code tmdbId}  : the BUSINESS key from TMDB (unique). Everything upserts on THIS. Why? Our internal id is only
 *                      known after the first insert; TMDB's id is stable across runs, so "does this movie exist?" is
 *                      answered by tmdbId. A UNIQUE constraint on it also makes duplicates impossible even if two
 *                      runs overlap (the 2nd insert fails instead of silently duplicating).
 *
 * ID GENERATION: SEQUENCE (not IDENTITY). With IDENTITY Hibernate must hit the DB right after every persist() to
 * learn the id, which DISABLES JDBC insert batching. With a sequence (allocationSize=20) Hibernate reserves 20 ids in
 * one call and can batch the INSERTs - matching our chunk size of 20.
 *
 * OPTIMISTIC LOCKING: {@code @Version}. Hibernate adds "WHERE id=? AND version=?" to every UPDATE and bumps the
 * version. If two transactions update the same row, the 2nd one updates 0 rows and Hibernate throws
 * OptimisticLockException instead of silently overwriting (lost update). No DB locks are held while "thinking" - that
 * is why it is "optimistic". It is our safety net if a sync and the enrichment step touch the same movie.
 *
 * FETCH TYPES: both collections are LAZY (the default for *ToMany, stated explicitly). Loading 20 movies does not load
 * their genres until touched. Danger = N+1: a loop calling movie.getGenres() on 20 movies fires 1 + 20 queries.
 * Cure = fetch join / @EntityGraph / batch fetching, used in the read paths (Phase 7).
 */
@Entity
@Table(name = "movie")
public class Movie {

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "movie_seq")
	@SequenceGenerator(name = "movie_seq", sequenceName = "movie_seq", allocationSize = 20)
	private Long id;

	@Column(name = "tmdb_id", nullable = false, unique = true)
	private Long tmdbId;

	@Column(nullable = false)
	private String title;

	@Column(name = "original_title")
	private String originalTitle;

	@Column(length = 4000)
	private String overview;

	@Column(name = "release_date")
	private LocalDate releaseDate;

	@Column(name = "original_language", length = 10)
	private String originalLanguage;

	private double popularity;

	@Column(name = "vote_average")
	private double voteAverage;

	@Column(name = "vote_count")
	private int voteCount;

	@Column(name = "poster_path")
	private String posterPath;

	/** Null until the "enrichDetails" step fetched /movie/{id}. Null runtime == "needs enrichment". */
	private Integer runtime;

	private String tagline;

	private boolean adult;

	/**
	 * Set by the enrichDetails step when /movie/{id} was fetched (even if TMDB answered 404, so we do not retry a
	 * hopeless movie forever). Null == "still needs enrichment". A timestamp instead of "runtime is null" because
	 * runtime can legitimately stay null.
	 */
	@Column(name = "details_fetched_at")
	private Instant detailsFetchedAt;

	// @BatchSize: when a lazy collection is touched, Hibernate loads it for up to 50 movies of the current page in ONE
	// query (WHERE movie_id IN (...)) instead of one query per movie. The cure for N+1 on read paths (REST API).
	@BatchSize(size = 50)
	@ManyToMany(fetch = FetchType.LAZY)
	@JoinTable(name = "movie_genre",
			joinColumns = @JoinColumn(name = "movie_id"),
			inverseJoinColumns = @JoinColumn(name = "genre_id"))
	private Set<Genre> genres = new LinkedHashSet<>();

	/** Which TMDB lists this movie appeared in during syncs (popular, top_rated, ...). */
	@BatchSize(size = 50)
	@ElementCollection(fetch = FetchType.LAZY)
	@CollectionTable(name = "movie_source_list", joinColumns = @JoinColumn(name = "movie_id"))
	@Column(name = "list_name", nullable = false)
	private Set<String> sourceLists = new LinkedHashSet<>();

	@Column(name = "first_seen_at", nullable = false)
	private Instant firstSeenAt;

	@Column(name = "last_synced_at", nullable = false)
	private Instant lastSyncedAt;

	@Version
	private long version;

	/** Required by JPA (Hibernate instantiates entities via reflection); protected so app code uses the real ctor. */
	protected Movie() {
	}

	public Movie(Long tmdbId, String title, Instant now) {
		this.tmdbId = tmdbId;
		this.title = title;
		this.firstSeenAt = now;
		this.lastSyncedAt = now;
	}

	// equals/hashCode on the BUSINESS key. Using the generated id would break Sets: it is null before persist, so
	// the hash would change after saving. Using all fields would break on every update.
	@Override
	public boolean equals(Object o) {
		return this == o || (o instanceof Movie other && tmdbId != null && Objects.equals(tmdbId, other.tmdbId));
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(tmdbId);
	}

	/**
	 * Copies the SYNC-OWNED fields from a freshly-read candidate onto this (managed) row and reports whether anything
	 * actually changed. This is the "update changed fields" half of the idempotent upsert.
	 *
	 * Deliberately NOT touched: runtime and tagline (owned by the enrichDetails step - a sync must never wipe them),
	 * firstSeenAt (set once), lastSyncedAt (the writer touches it on every sync), id/version (Hibernate's).
	 * sourceLists is a UNION over time ("has ever appeared in"), so it can only grow.
	 */
	public boolean refreshFrom(Movie in) {
		boolean changed = false;
		changed |= !Objects.equals(title, in.title);
		title = in.title;
		changed |= !Objects.equals(originalTitle, in.originalTitle);
		originalTitle = in.originalTitle;
		changed |= !Objects.equals(overview, in.overview);
		overview = in.overview;
		changed |= !Objects.equals(releaseDate, in.releaseDate);
		releaseDate = in.releaseDate;
		changed |= !Objects.equals(originalLanguage, in.originalLanguage);
		originalLanguage = in.originalLanguage;
		changed |= Double.compare(popularity, in.popularity) != 0;
		popularity = in.popularity;
		changed |= Double.compare(voteAverage, in.voteAverage) != 0;
		voteAverage = in.voteAverage;
		changed |= voteCount != in.voteCount;
		voteCount = in.voteCount;
		changed |= !Objects.equals(posterPath, in.posterPath);
		posterPath = in.posterPath;
		changed |= adult != in.adult;
		adult = in.adult;

		// Only touch the join table when the SET OF GENRES really differs (clear+addAll would rewrite rows needlessly).
		Set<Integer> current = new java.util.HashSet<>();
		genres.forEach(g -> current.add(g.getTmdbGenreId()));
		Set<Integer> incoming = new java.util.HashSet<>();
		in.genres.forEach(g -> incoming.add(g.getTmdbGenreId()));
		if (!current.equals(incoming)) {
			genres.clear();
			genres.addAll(in.genres);
			changed = true;
		}
		if (sourceLists.addAll(in.sourceLists)) {
			changed = true;
		}
		return changed;
	}

	public Long getId() { return id; }
	public Long getTmdbId() { return tmdbId; }
	public String getTitle() { return title; }
	public void setTitle(String title) { this.title = title; }
	public String getOriginalTitle() { return originalTitle; }
	public void setOriginalTitle(String originalTitle) { this.originalTitle = originalTitle; }
	public String getOverview() { return overview; }
	public void setOverview(String overview) { this.overview = overview; }
	public LocalDate getReleaseDate() { return releaseDate; }
	public void setReleaseDate(LocalDate releaseDate) { this.releaseDate = releaseDate; }
	public String getOriginalLanguage() { return originalLanguage; }
	public void setOriginalLanguage(String originalLanguage) { this.originalLanguage = originalLanguage; }
	public double getPopularity() { return popularity; }
	public void setPopularity(double popularity) { this.popularity = popularity; }
	public double getVoteAverage() { return voteAverage; }
	public void setVoteAverage(double voteAverage) { this.voteAverage = voteAverage; }
	public int getVoteCount() { return voteCount; }
	public void setVoteCount(int voteCount) { this.voteCount = voteCount; }
	public String getPosterPath() { return posterPath; }
	public void setPosterPath(String posterPath) { this.posterPath = posterPath; }
	public Integer getRuntime() { return runtime; }
	public void setRuntime(Integer runtime) { this.runtime = runtime; }
	public String getTagline() { return tagline; }
	public void setTagline(String tagline) { this.tagline = tagline; }
	public boolean isAdult() { return adult; }
	public void setAdult(boolean adult) { this.adult = adult; }
	public Instant getDetailsFetchedAt() { return detailsFetchedAt; }
	public void setDetailsFetchedAt(Instant detailsFetchedAt) { this.detailsFetchedAt = detailsFetchedAt; }
	public Set<Genre> getGenres() { return genres; }
	public Set<String> getSourceLists() { return sourceLists; }
	public Instant getFirstSeenAt() { return firstSeenAt; }
	public Instant getLastSyncedAt() { return lastSyncedAt; }
	public void setLastSyncedAt(Instant lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; }
	public long getVersion() { return version; }
}
