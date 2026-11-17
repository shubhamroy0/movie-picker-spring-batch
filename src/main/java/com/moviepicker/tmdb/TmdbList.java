package com.moviepicker.tmdb;

/**
 * The TMDB movie lists we can sync. {@link #listName()} is the stable string stored in the database
 * ({@code Movie.sourceLists}, {@code TrendingSnapshot.listName}) and used in the REST API (?list=trending_day).
 *
 * Relaxed binding lets application.yml say {@code trending_day} and map it to {@code TRENDING_DAY}.
 */
public enum TmdbList {
	TRENDING_DAY("trending_day"),
	NOW_PLAYING("now_playing"),
	POPULAR("popular"),
	TOP_RATED("top_rated");

	private final String listName;

	TmdbList(String listName) {
		this.listName = listName;
	}

	public String listName() {
		return listName;
	}

	public static TmdbList fromListName(String name) {
		for (TmdbList l : values()) {
			if (l.listName.equalsIgnoreCase(name)) {
				return l;
			}
		}
		throw new IllegalArgumentException("Unknown TMDB list: " + name);
	}
}
