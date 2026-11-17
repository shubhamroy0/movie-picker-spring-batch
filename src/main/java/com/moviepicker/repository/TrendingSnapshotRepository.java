package com.moviepicker.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.moviepicker.domain.TrendingSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TrendingSnapshotRepository extends JpaRepository<TrendingSnapshot, Long> {

	boolean existsByListNameAndTmdbIdAndCapturedAt(String listName, long tmdbId, Instant capturedAt);

	@Query("select max(s.capturedAt) from TrendingSnapshot s where s.listName = :list")
	Optional<Instant> findLatestCapturedAt(@Param("list") String list);

	/** Latest capture strictly before the given one: used to show rank movement. */
	@Query("select max(s.capturedAt) from TrendingSnapshot s where s.listName = :list and s.capturedAt < :before")
	Optional<Instant> findPreviousCapturedAt(@Param("list") String list, @Param("before") Instant before);

	List<TrendingSnapshot> findByListNameAndCapturedAtOrderByRankAsc(String listName, Instant capturedAt);
}
