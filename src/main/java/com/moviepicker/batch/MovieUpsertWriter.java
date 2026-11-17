package com.moviepicker.batch;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.moviepicker.domain.Movie;
import com.moviepicker.repository.MovieRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStream;
import org.springframework.batch.infrastructure.item.ItemWriter;

/**
 * IDEMPOTENT UPSERT keyed on tmdbId: "make the database look like the source" - insert what is new, update what
 * changed, leave the rest alone. Running it twice with the same input leaves exactly the same rows (no duplicates).
 *
 * WHY IDEMPOTENCY MATTERS FOR SCHEDULED JOBS: the job WILL run again - on schedule, after a failure, after a restart
 * that re-reads a few items, or twice by accident. If a write is a plain INSERT, every re-run duplicates data (or hits
 * the unique constraint and fails). An upsert makes "run it again" always safe.
 *
 * THE THREE WAYS TO WRITE (we use #2):
 *  1. JpaItemWriter: calls em.merge()/persist() per item. Zero code, but merge() does a SELECT per item (N+1), cannot
 *     report inserted-vs-updated, and cannot apply "only touch what changed" rules (e.g. keep runtime/tagline).
 *  2. Custom writer (this class): ONE `SELECT ... WHERE tmdb_id IN (...)` for the whole chunk, then decide per item in
 *     memory; Hibernate flushes inserts as a JDBC batch and updates via dirty checking. Full control over rules + counts.
 *  3. JdbcBatchItemWriter with a MERGE statement: fastest (no entities, one round trip), but bypasses Hibernate: no
 *     @Version, no ManyToMany handling (genres need extra statements), and DB-specific SQL (H2 MERGE vs Postgres ON CONFLICT).
 *     The right choice for millions of flat rows; wrong for entities with relations.
 *
 * TRANSACTION: write() runs INSIDE the chunk's transaction (the step uses the JPA transaction manager). Everything
 * below is flushed and committed together with the reader position (update()) and the BATCH_* metadata - or rolled
 * back together. Each chunk gets a fresh persistence context, which is why memory does not grow over thousands of chunks.
 *
 * Also an ItemStream, only to persist its counters in the step ExecutionContext (they survive a restart and the
 * Phase-4 summary tasklet reads them from the StepExecution).
 */
public class MovieUpsertWriter implements ItemWriter<Movie>, ItemStream {

	private static final Logger log = LoggerFactory.getLogger(MovieUpsertWriter.class);

	static final String KEY_INSERTED = "writer.inserted";
	static final String KEY_UPDATED = "writer.updated";
	static final String KEY_UNCHANGED = "writer.unchanged";
	static final String KEY_REPEATS = "writer.repeats";

	private final MovieRepository movies;
	private final Clock clock;
	/** Moment this run started (job parameter runAt). A row touched at/after it was already handled in THIS run. */
	private final Instant runStart;

	private int inserted;
	private int updated;
	private int unchanged;
	/** Movies that showed up again in a later list during the same run (e.g. in popular AND top_rated). */
	private int repeats;

	public MovieUpsertWriter(MovieRepository movies, Clock clock, Instant runStart) {
		this.movies = movies;
		this.clock = clock;
		this.runStart = runStart;
	}

	@Override
	public void write(Chunk<? extends Movie> chunk) {
		// 1) The SAME movie can appear twice inside one chunk (two lists). Merge them first, or we would try to
		//    INSERT it twice. LinkedHashMap keeps the original order.
		Map<Long, Movie> incoming = new LinkedHashMap<>();
		for (Movie m : chunk.getItems()) {
			Movie first = incoming.putIfAbsent(m.getTmdbId(), m);
			if (first != null) {
				first.getSourceLists().addAll(m.getSourceLists());
				repeats++;
			}
		}

		// 2) ONE query for the whole chunk (with genres + sourceLists fetched: see @EntityGraph) - not one per item.
		Map<Long, Movie> existing = movies.findAllByTmdbIdIn(incoming.keySet()).stream()
				.collect(Collectors.toMap(Movie::getTmdbId, Function.identity()));

		Instant now = clock.instant();
		List<Movie> toInsert = new ArrayList<>();
		int chunkInserted = 0;
		int chunkUpdated = 0;
		int chunkUnchanged = 0;
		for (Movie candidate : incoming.values()) {
			Movie row = existing.get(candidate.getTmdbId());
			if (row == null) {
				candidate.setLastSyncedAt(now);
				toInsert.add(candidate);
				chunkInserted++;
			}
			else {
				boolean alreadyTouchedThisRun = !row.getLastSyncedAt().isBefore(runStart);
				boolean changed = row.refreshFrom(candidate);   // modifies the MANAGED entity: dirty checking does the UPDATE
				row.setLastSyncedAt(now);
				if (alreadyTouchedThisRun) {
					repeats++;                                  // seen in another list earlier in this run: not a new action
				}
				else if (changed) {
					chunkUpdated++;
				}
				else {
					chunkUnchanged++;
				}
			}
		}
		movies.saveAll(toInsert);   // persist(): new rows become managed, INSERTs are JDBC-batched at flush/commit

		inserted += chunkInserted;
		updated += chunkUpdated;
		unchanged += chunkUnchanged;
		log.info("Chunk written: {} item(s) -> inserted={} updated={} unchanged={}",
				chunk.size(), chunkInserted, chunkUpdated, chunkUnchanged);
	}

	@Override
	public void open(ExecutionContext ctx) {
		inserted = ctx.getInt(KEY_INSERTED, 0);
		updated = ctx.getInt(KEY_UPDATED, 0);
		unchanged = ctx.getInt(KEY_UNCHANGED, 0);
		repeats = ctx.getInt(KEY_REPEATS, 0);
	}

	@Override
	public void update(ExecutionContext ctx) {
		ctx.putInt(KEY_INSERTED, inserted);
		ctx.putInt(KEY_UPDATED, updated);
		ctx.putInt(KEY_UNCHANGED, unchanged);
		ctx.putInt(KEY_REPEATS, repeats);
	}
}
