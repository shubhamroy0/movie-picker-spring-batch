package com.moviepicker.batch;

import java.time.Instant;

import com.moviepicker.domain.TrendingSnapshot;
import com.moviepicker.repository.TrendingSnapshotRepository;
import org.springframework.batch.infrastructure.item.ItemProcessor;

/**
 * SourcedMovie -> TrendingSnapshot(tmdbId, list, rank, capturedAt). capturedAt is the job's runAt parameter, so every
 * row of one run shares the same timestamp (= "the trending list as of this run") and a RESTART of the same JobInstance
 * reuses it. The exists-check makes the step idempotent: rows already committed before a failure are filtered (null).
 * Created per step execution (see SyncJobFlowConfig) because it holds the late-bound runAt.
 */
public class SnapshotProcessor implements ItemProcessor<SourcedMovie, TrendingSnapshot> {

	private final TrendingSnapshotRepository snapshots;
	private final Instant capturedAt;

	public SnapshotProcessor(TrendingSnapshotRepository snapshots, Instant capturedAt) {
		this.snapshots = snapshots;
		this.capturedAt = capturedAt;
	}

	@Override
	public TrendingSnapshot process(SourcedMovie item) {
		String list = item.list().listName();
		long id = item.movie().id();
		if (snapshots.existsByListNameAndTmdbIdAndCapturedAt(list, id, capturedAt)) {
			return null;
		}
		return new TrendingSnapshot(id, list, item.rank(), capturedAt);
	}
}
