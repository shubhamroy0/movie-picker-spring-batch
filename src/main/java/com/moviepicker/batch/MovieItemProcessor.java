package com.moviepicker.batch;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import com.moviepicker.domain.Movie;
import com.moviepicker.service.GenreCatalog;
import com.moviepicker.tmdb.dto.TmdbMovieSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.stereotype.Component;

/**
 * Transforms one TMDB DTO into a (transient, not yet saved) {@link Movie} "candidate".
 *
 * Three possible outcomes for an item - this is the heart of the processor contract:
 *   1. return an object  -> goes on to the writer
 *   2. return null       -> FILTERED: silently dropped, counted in StepExecution.filterCount. NOT an error.
 *   3. throw             -> a FAILURE (the step fails unless Phase 3's skip policy says this exception may be skipped)
 *
 * Stateless and thread-safe, so a plain singleton is fine (no @StepScope needed). It does no database access except
 * the in-memory genre lookup, so it is cheap and safe to re-run if a chunk is retried.
 */
@Component
public class MovieItemProcessor implements ItemProcessor<SourcedMovie, Movie> {

	private static final Logger log = LoggerFactory.getLogger(MovieItemProcessor.class);

	private final GenreCatalog genreCatalog;
	private final Clock clock;

	public MovieItemProcessor(GenreCatalog genreCatalog, Clock clock) {
		this.genreCatalog = genreCatalog;
		this.clock = clock;
	}

	@Override
	public Movie process(SourcedMovie item) {
		TmdbMovieSummary dto = item.movie();

		// 1) business filters -> null
		if (dto.adult()) {
			log.debug("Filtered adult title {} ({})", dto.id(), dto.title());
			return null;
		}
		if (dto.releaseDate() == null || dto.releaseDate().isBlank()) {
			log.debug("Filtered movie {} ({}): no release date", dto.id(), dto.title());
			return null;
		}

		// 2) validation -> exception
		validate(dto);
		LocalDate releaseDate = parseDate(dto);

		// 3) map
		Movie movie = new Movie(dto.id(), dto.title().trim(), clock.instant());
		movie.setOriginalTitle(dto.originalTitle());
		movie.setOverview(dto.overview());
		movie.setReleaseDate(releaseDate);
		movie.setOriginalLanguage(dto.originalLanguage());
		movie.setPopularity(dto.popularity() == null ? 0 : dto.popularity());
		movie.setVoteAverage(dto.voteAverage() == null ? 0 : dto.voteAverage());
		movie.setVoteCount(dto.voteCount() == null ? 0 : dto.voteCount());
		movie.setPosterPath(dto.posterPath());
		movie.setAdult(false);
		movie.getSourceLists().add(item.list().listName());
		if (dto.genreIds() != null) {
			for (int genreId : dto.genreIds()) {
				// in-memory lookup (GenreCatalog cache): no HTTP, no SQL per item. An unknown id is ignored, not fatal.
				genreCatalog.find(genreId).ifPresentOrElse(movie.getGenres()::add,
						() -> log.debug("Movie {}: unknown genre id {} ignored", dto.id(), genreId));
			}
		}
		return movie;
	}

	private static void validate(TmdbMovieSummary dto) {
		if (dto.id() <= 0) {
			throw new InvalidMovieException(dto.id(), "id must be positive");
		}
		if (dto.title() == null || dto.title().isBlank()) {
			throw new InvalidMovieException(dto.id(), "title is blank");
		}
		if (dto.voteAverage() != null && (dto.voteAverage() < 0 || dto.voteAverage() > 10)) {
			throw new InvalidMovieException(dto.id(), "voteAverage out of range 0..10: " + dto.voteAverage());
		}
		if (dto.popularity() != null && dto.popularity() < 0) {
			throw new InvalidMovieException(dto.id(), "popularity is negative: " + dto.popularity());
		}
	}

	private static LocalDate parseDate(TmdbMovieSummary dto) {
		try {
			return LocalDate.parse(dto.releaseDate());
		}
		catch (DateTimeParseException e) {
			throw new InvalidMovieException(dto.id(), "unparseable release_date '" + dto.releaseDate() + "'");
		}
	}
}
