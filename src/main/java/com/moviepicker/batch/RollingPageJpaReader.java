package com.moviepicker.batch;

import com.moviepicker.domain.Movie;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.batch.infrastructure.item.database.JpaPagingItemReader;

/**
 * PAGING READER vs CURSOR READER, and the classic trap.
 *  - Cursor reader (JpaCursorItemReader/JdbcCursorItemReader): ONE query, rows streamed from an open cursor/connection.
 *    Fast and simple, but holds a connection for the whole step and is not thread-safe.
 *  - Paging reader (JpaPagingItemReader): a NEW query per page (LIMIT/OFFSET); no long-lived cursor, restartable by page.
 *
 * THE TRAP: the enrich step selects "movies WHERE details_fetched_at IS NULL" and the writer then SETS that column, so
 * processed rows LEAVE the result set. A normal paging reader asks for page 1 (OFFSET 20) next - but the former page 2
 * has just slid into page 1, so 20 movies are silently skipped. No error, just missing data.
 *
 * THE FIX used here: the window "rolls" - we always read page 0, because everything already processed has dropped out of
 * the predicate. Preconditions that make this safe: (1) every processed row MUST leave the result set (the processor always
 * sets detailsFetchedAt, even on a 404 - otherwise this loops forever), (2) restart state is meaningless, so saveState
 * is off; a restart simply asks "what is still unprocessed?".
 */
class RollingPageJpaReader extends JpaPagingItemReader<Movie> {

	RollingPageJpaReader(EntityManagerFactory emf) {
		super(emf);
	}

	@Override
	public int getPage() {
		return 0;
	}
}
