package com.moviepicker.batch;

import java.util.concurrent.atomic.AtomicInteger;

import com.moviepicker.domain.Movie;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.listener.ChunkListener;
import org.springframework.batch.core.listener.ItemReadListener;
import org.springframework.batch.infrastructure.item.Chunk;

/**
 * Observability only (never changes behaviour): per-item read logging at DEBUG, per-chunk logging at INFO.
 * Listeners are the Batch way to add cross-cutting concerns without touching reader/processor/writer code.
 *   ItemReadListener : before/after every read(), and onReadError
 *   ChunkListener    : before the chunk starts, after it COMMITS, and when it fails (then it rolled back)
 */
public class StepLoggingListener implements ItemReadListener<SourcedMovie>, ChunkListener<SourcedMovie, Movie> {

	private static final Logger log = LoggerFactory.getLogger(StepLoggingListener.class);

	private final AtomicInteger chunkNumber = new AtomicInteger();

	@Override
	public void afterRead(SourcedMovie item) {
		log.debug("read {} #{} -> movie {} '{}'", item.list().listName(), item.rank(), item.movie().id(),
				item.movie().title());
	}

	@Override
	public void onReadError(Exception e) {
		log.error("read failed: {}", e.toString());
	}

	@Override
	public void beforeChunk(Chunk<SourcedMovie> chunk) {
		log.debug("chunk #{} starting", chunkNumber.incrementAndGet());
	}

	@Override
	public void afterChunk(Chunk<Movie> chunk) {
		log.info("chunk #{} COMMITTED ({} item(s) written)", chunkNumber.get(), chunk.size());
	}

	@Override
	public void onChunkError(Exception e, Chunk<Movie> chunk) {
		log.error("chunk #{} FAILED and was rolled back: {}", chunkNumber.get(), e.toString());
	}
}
