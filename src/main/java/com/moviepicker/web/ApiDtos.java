package com.moviepicker.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Everything the REST API returns. Records, never JPA entities. WHY entities must not leak:
 *  1. Lazy collections: serialising an entity outside a transaction throws LazyInitializationException, inside one it
 *     silently fires queries per row (N+1) or drags in the whole object graph.
 *  2. Coupling: the JSON contract would change whenever the table does (renamed column = broken Postman/clients).
 *  3. Exposure: internal ids, @Version, relations and future sensitive columns would be published by accident.
 *  4. Cycles: bidirectional relations make Jackson recurse forever.
 * A DTO is a deliberate, versionable contract.
 */
public final class ApiDtos {

	private ApiDtos() {
	}

	public record MovieDto(long tmdbId, String title, String originalTitle, String overview, LocalDate releaseDate,
			String originalLanguage, double popularity, double voteAverage, int voteCount, String posterPath,
			Integer runtime, String tagline, List<String> genres, List<String> sourceLists, Instant firstSeenAt,
			Instant lastSyncedAt) {
	}

	public record GenreDto(int tmdbGenreId, String name) {
	}

	public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages,
			String sort) {
	}

	/** movement: positive = climbed that many places since the previous snapshot, negative = dropped, null = new entry. */
	public record TrendingEntryDto(int rank, Integer previousRank, Integer movement, MovieDto movie) {
	}

	public record TrendingResponse(String list, Instant capturedAt, Instant previousCapturedAt,
			List<TrendingEntryDto> entries) {
	}

	public record PickDto(double score, double weightedRating, MovieDto movie) {
	}

	public record SyncStartedDto(long executionId, String status, String statusUrl) {
	}

	public record StepExecutionDto(long id, String name, String status, long read, long filtered, long written,
			long skipped, long commits, long rollbacks, String exitCode, String exitMessage, Instant startTime,
			Instant endTime, Long durationMillis) {
	}

	public record JobExecutionDto(long executionId, long jobInstanceId, String jobName, String status,
			String exitCode, String exitMessage, Instant startTime, Instant endTime, Long durationMillis,
			Map<String, String> parameters, String summary, List<StepExecutionDto> steps) {
	}

	public record MessageDto(String message) {
	}

	public record AuditEntryDto(Instant at, String action, String argument, String outcome, String detail) {
	}

	public record AboutDto(String application, String dataSource, String attribution, String tmdbUrl, String notice) {
	}
}
