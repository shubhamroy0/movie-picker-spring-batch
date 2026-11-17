package com.moviepicker.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.moviepicker.domain.Genre;
import com.moviepicker.domain.Movie;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

/**
 * Dynamic WHERE clauses. A Specification is a reusable "predicate builder" (JPA Criteria API): only the filters the
 * caller supplied are added, everything is parameterised (no SQL injection), and the SAME specification serves the page
 * query and its count query. The alternative for many optional filters - one derived-query method per combination -
 * explodes combinatorially.
 */
public final class MovieSpecifications {

	private MovieSpecifications() {
	}

	/** @param genre a TMDB genre id ("878") or a name ("science fiction", case-insensitive); null = any */
	public static Specification<Movie> filter(String genre, Double minRating, Integer year, String language, String q) {
		return (root, query, cb) -> {
			List<Predicate> p = new ArrayList<>();
			p.add(cb.isFalse(root.get("adult")));
			if (genre != null && !genre.isBlank()) {
				Join<Movie, Genre> g = root.join("genres");
				query.distinct(true);                                   // a movie with 2 matching joins must count once
				p.add(genre.chars().allMatch(Character::isDigit)
						? cb.equal(g.get("tmdbGenreId"), Integer.parseInt(genre.trim()))
						: cb.equal(cb.lower(g.get("name")), genre.trim().toLowerCase()));
			}
			if (minRating != null) {
				p.add(cb.greaterThanOrEqualTo(root.get("voteAverage"), minRating));
			}
			if (year != null) {
				p.add(cb.between(root.get("releaseDate"), LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31)));
			}
			if (language != null && !language.isBlank()) {
				p.add(cb.equal(cb.lower(root.get("originalLanguage")), language.trim().toLowerCase()));
			}
			if (q != null && !q.isBlank()) {
				String like = "%" + escapeLike(q.trim().toLowerCase()) + "%";
				p.add(cb.or(cb.like(cb.lower(root.get("title")), like, '\\'),
						cb.like(cb.lower(root.get("originalTitle")), like, '\\')));
			}
			return cb.and(p.toArray(Predicate[]::new));
		};
	}

	/** User text must not act as LIKE wildcards. */
	private static String escapeLike(String s) {
		return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}
}
