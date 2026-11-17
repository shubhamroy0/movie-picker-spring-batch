package com.moviepicker.batch;

import java.time.Clock;

import com.moviepicker.domain.Movie;
import com.moviepicker.tmdb.TmdbClient;
import com.moviepicker.tmdb.TmdbNotFoundException;
import com.moviepicker.tmdb.dto.TmdbMovieDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.stereotype.Component;

/**
 * One /movie/{id} call per movie: fills runtime + tagline. The item comes from the reader DETACHED (transacted=false),
 * we change it, and JpaItemWriter merges it back (an UPDATE guarded by @Version).
 * A 404 is not an error for the job: we still stamp detailsFetchedAt so the movie leaves the "to do" set.
 * Transient HTTP errors were already retried by ResilientTmdbClient; if they persist the step fails and a restart
 * continues with whatever is still unprocessed.
 */
@Component
public class EnrichMovieProcessor implements ItemProcessor<Movie, Movie> {

	private static final Logger log = LoggerFactory.getLogger(EnrichMovieProcessor.class);

	private final TmdbClient client;
	private final Clock clock;

	public EnrichMovieProcessor(TmdbClient client, Clock clock) {
		this.client = client;
		this.clock = clock;
	}

	@Override
	public Movie process(Movie movie) {
		try {
			TmdbMovieDetail detail = client.fetchDetail(movie.getTmdbId());
			movie.setRuntime(detail.runtime());
			movie.setTagline(detail.tagline());
		}
		catch (TmdbNotFoundException e) {
			log.warn("No TMDB details for movie {} ({}): marking as fetched anyway", movie.getTmdbId(), movie.getTitle());
		}
		movie.setDetailsFetchedAt(clock.instant());
		return movie;
	}
}
