package com.moviepicker.batch;

import com.moviepicker.domain.Movie;
import com.moviepicker.service.RejectedRecordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.listener.SkipListener;

/**
 * Called by the framework EVERY TIME an item is skipped, with the item and the exception that caused it. We turn each
 * skip into a rejected_record row (payload + reason + stage) instead of letting the data vanish.
 * Three hooks = three places an item can fail: read (no item yet - only the exception), process, write.
 * Instantiated per step execution (see MovieSyncJobConfig) so it knows the jobExecutionId it belongs to.
 */
public class RejectedItemSkipListener implements SkipListener<SourcedMovie, Movie> {

	private static final Logger log = LoggerFactory.getLogger(RejectedItemSkipListener.class);

	private final RejectedRecordService rejects;
	private final long jobExecutionId;

	public RejectedItemSkipListener(RejectedRecordService rejects, long jobExecutionId) {
		this.rejects = rejects;
		this.jobExecutionId = jobExecutionId;
	}

	@Override
	public void onSkipInRead(Throwable t) {
		log.warn("SKIPPED while reading: {}", t.toString());
		rejects.record("(unreadable input)", t, "READ", jobExecutionId);
	}

	@Override
	public void onSkipInProcess(SourcedMovie item, Throwable t) {
		log.warn("SKIPPED while processing {} / movie {}: {}", item.list().listName(), item.movie().id(), t.getMessage());
		rejects.record(String.valueOf(item), t, "PROCESS", jobExecutionId);
	}

	@Override
	public void onSkipInWrite(Movie item, Throwable t) {
		log.warn("SKIPPED while writing movie {}: {}", item.getTmdbId(), t.getMessage());
		rejects.record("Movie{tmdbId=" + item.getTmdbId() + ", title=" + item.getTitle() + "}", t, "WRITE",
				jobExecutionId);
	}
}
