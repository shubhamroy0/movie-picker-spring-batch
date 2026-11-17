package com.moviepicker.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.moviepicker.domain.Movie;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Spring Data generates the implementation at startup from the method names (derived queries).
 */
public interface MovieRepository extends JpaRepository<Movie, Long>, JpaSpecificationExecutor<Movie> {

	Optional<Movie> findByTmdbId(Long tmdbId);

	long countByDetailsFetchedAtIsNull();

	/**
	 * Looks up a WHOLE CHUNK of movies with one SELECT ... IN (...) instead of one SELECT per item.
	 *
	 * {@code @EntityGraph} tells Hibernate to JOIN-FETCH genres and sourceLists in that same query. Without it the
	 * collections are LAZY, and the upsert writer (which compares them) would trigger two extra queries PER MOVIE
	 * = the N+1 problem (1 + 2*20 = 41 queries per chunk instead of 1).
	 */
	@EntityGraph(attributePaths = {"genres", "sourceLists"})
	List<Movie> findAllByTmdbIdIn(Collection<Long> tmdbIds);
}
