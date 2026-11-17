package com.moviepicker.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * One row = "movie X was at rank R of list L when we looked at time T". APPEND-ONLY history, so we can see how
 * trending changes between runs.
 *
 * {@code tmdbId} is a plain number, NOT a foreign key / @ManyToOne to Movie: a snapshot is a historical fact that
 * should stay valid even if the Movie row is later changed or removed, and it keeps the snapshot step cheap (no entity
 * loading). The API joins to Movie by tmdbId when it needs the title.
 *
 * The column is called rank_position because RANK is an SQL keyword in many databases.
 */
@Entity
@Table(name = "trending_snapshot",
		indexes = @Index(name = "idx_snapshot_list_captured", columnList = "list_name, captured_at"),
		// A movie can appear once per list per capture: also what makes re-running/restarting the snapshot step safe.
		uniqueConstraints = @UniqueConstraint(name = "uk_snapshot_list_movie_time",
				columnNames = {"list_name", "tmdb_id", "captured_at"}))
public class TrendingSnapshot {

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "snapshot_seq")
	@SequenceGenerator(name = "snapshot_seq", sequenceName = "snapshot_seq", allocationSize = 50)
	private Long id;

	@Column(name = "tmdb_id", nullable = false)
	private long tmdbId;

	@Column(name = "list_name", nullable = false)
	private String listName;

	@Column(name = "rank_position", nullable = false)
	private int rank;

	@Column(name = "captured_at", nullable = false)
	private Instant capturedAt;

	protected TrendingSnapshot() {
	}

	public TrendingSnapshot(long tmdbId, String listName, int rank, Instant capturedAt) {
		this.tmdbId = tmdbId;
		this.listName = listName;
		this.rank = rank;
		this.capturedAt = capturedAt;
	}

	public Long getId() { return id; }
	public long getTmdbId() { return tmdbId; }
	public String getListName() { return listName; }
	public int getRank() { return rank; }
	public Instant getCapturedAt() { return capturedAt; }
}
