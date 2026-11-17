package com.moviepicker.domain;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/**
 * A TMDB genre (28 = Action, 18 = Drama, ...). Reference data, tiny, refreshed at startup by GenreCatalog and then
 * cached in memory. The Movie <-> Genre relation is MANY-TO-MANY: a movie has several genres and a genre has many
 * movies, stored in the join table {@code movie_genre}.
 */
@Entity
@Table(name = "genre")
public class Genre {

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "genre_seq")
	@SequenceGenerator(name = "genre_seq", sequenceName = "genre_seq", allocationSize = 20)
	private Long id;

	@Column(name = "tmdb_genre_id", nullable = false, unique = true)
	private int tmdbGenreId;

	@Column(nullable = false)
	private String name;

	protected Genre() {
	}

	public Genre(int tmdbGenreId, String name) {
		this.tmdbGenreId = tmdbGenreId;
		this.name = name;
	}

	@Override
	public boolean equals(Object o) {
		return this == o || (o instanceof Genre other && tmdbGenreId == other.tmdbGenreId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(tmdbGenreId);
	}

	public Long getId() { return id; }
	public int getTmdbGenreId() { return tmdbGenreId; }
	public String getName() { return name; }
	public void setName(String name) { this.name = name; }
}
