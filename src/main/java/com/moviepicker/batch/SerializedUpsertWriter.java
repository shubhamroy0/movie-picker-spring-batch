package com.moviepicker.batch;

import java.util.concurrent.locks.ReentrantLock;

import com.moviepicker.domain.Movie;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamWriter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Makes the upsert safe when SEVERAL partitions write the same table at once (a movie in both "popular" and "top_rated").
 *
 * WHAT WENT WRONG WITHOUT IT (observed, not theoretical): two workers both SELECT "tmdbId 157336 does not exist", both
 * INSERT it, the second hits the unique constraint at flush. The step's retry then re-runs write() INSIDE THE SAME
 * transaction (Batch 6 retries the chunk's write in place, it does not roll back first), but that Hibernate session is
 * already poisoned by the failed flush, so every retry fails the same way and the job FAILS. Row-lock timeouts between
 * workers updating the same movies in different orders add to it.
 *
 * THE FIX: parallelise the slow part (HTTP page fetching: the real bottleneck), serialise the fast and contended part
 * (the DB write). Each chunk's upsert runs under a lock, in its OWN short REQUIRES_NEW transaction that commits before
 * the lock is released, so the next worker's SELECT always sees the previous worker's rows. Consequences to be able to
 * explain:
 *  - Atomicity per chunk is now "upsert commits, THEN the step commits its position". A crash in between leaves rows
 *    written but the position not advanced; on restart the chunk is re-processed and the idempotent upsert makes that
 *    harmless (that is the whole reason we designed it as an upsert).
 *  - The lock only protects ONE JVM. With several app instances the database's unique key + a retry that starts from a
 *    clean transaction is the safety net, or - better - partition by DISJOINT keys so workers never collide.
 *  - The lesson: partition by a key that makes slices independent. "One list per worker" is great for READING and a
 *    poor key for WRITING, because the lists overlap.
 */
class SerializedUpsertWriter implements ItemStreamWriter<Movie> {

	/** static: every worker has its own writer instance (@StepScope) but they all must queue on the same lock. */
	private static final ReentrantLock DB_WRITE_LOCK = new ReentrantLock();

	private final MovieUpsertWriter delegate;
	private final TransactionTemplate newTransaction;

	SerializedUpsertWriter(MovieUpsertWriter delegate, PlatformTransactionManager txManager) {
		this.delegate = delegate;
		this.newTransaction = new TransactionTemplate(txManager);
		this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	@Override
	public void write(Chunk<? extends Movie> chunk) {
		DB_WRITE_LOCK.lock();
		try {
			newTransaction.executeWithoutResult(status -> delegate.write(chunk));   // commits before unlock()
		}
		finally {
			DB_WRITE_LOCK.unlock();
		}
	}

	@Override
	public void open(ExecutionContext ctx) {
		delegate.open(ctx);
	}

	@Override
	public void update(ExecutionContext ctx) {
		delegate.update(ctx);
	}

	@Override
	public void close() {
		delegate.close();
	}
}
