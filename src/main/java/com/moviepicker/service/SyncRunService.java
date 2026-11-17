package com.moviepicker.service;

import java.time.Clock;

import com.moviepicker.batch.SyncSteps;
import com.moviepicker.domain.SyncRun;
import com.moviepicker.repository.SyncRunRepository;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintains the business summary row (SyncRun) of a job execution. Single source of truth for "what did this run do":
 * the Batch metadata gives read/filter/skip per step; inserted/updated come from the counters the writer promoted into
 * the JOB ExecutionContext. Combined here into one row that the API and the health indicator can query cheaply.
 */
@Service
public class SyncRunService {

	public record Stats(int inserted, int updated, int unchanged, long read, long filtered, long skipped,
			long enriched, long snapshots) {

		public String oneLine() {
			return "inserted=" + inserted + " updated=" + updated + " unchanged=" + unchanged + " read=" + read
					+ " filtered=" + filtered + " skipped=" + skipped + " enriched=" + enriched
					+ " snapshots=" + snapshots;
		}
	}

	private final SyncRunRepository runs;
	private final Clock clock;

	public SyncRunService(SyncRunRepository runs, Clock clock) {
		this.runs = runs;
		this.clock = clock;
	}

	@Transactional
	public void start(JobExecution je) {
		SyncRun run = new SyncRun(je.getId(), "RUNNING");
		run.setStartedAt(clock.instant());
		runs.save(run);
	}

	@Transactional
	public SyncRun recordCounts(JobExecution je) {
		SyncRun run = runs.findFirstByJobExecutionIdOrderByIdDesc(je.getId())
				.orElseGet(() -> runs.save(new SyncRun(je.getId(), "RUNNING")));
		Stats s = stats(je);
		run.setInserted(s.inserted());
		run.setUpdated(s.updated());
		run.setReadCount((int) s.read());
		run.setFilteredCount((int) s.filtered());
		run.setSkippedCount((int) s.skipped());
		run.setMessage(s.oneLine());
		return run;
	}

	@Transactional
	public SyncRun finish(JobExecution je) {
		SyncRun run = recordCounts(je);
		run.setStatus(je.getStatus().name());
		run.setFinishedAt(clock.instant());
		if (!"COMPLETED".equals(je.getExitStatus().getExitCode())) {
			String d = je.getExitStatus().getExitDescription();
			run.setMessage(run.getMessage() + " | " + je.getExitStatus().getExitCode()
					+ (d == null || d.isBlank() ? "" : ": " + d.lines().findFirst().orElse("")));
			if (run.getMessage().length() > 1900) {
				run.setMessage(run.getMessage().substring(0, 1900));
			}
		}
		return run;
	}

	public Stats stats(JobExecution je) {
		var ctx = je.getExecutionContext();
		long read = 0, filtered = 0, skipped = 0, enriched = 0, snapshots = 0;
		// The writer counters reach the JOB context only when the fetch step COMPLETES (promotion). For a FAILED run fall
		// back to the step's own context, which holds what the committed chunks wrote: so a failed run still reports
		// the rows it did commit instead of a misleading "inserted=0".
		var fetchCtx = new org.springframework.batch.infrastructure.item.ExecutionContext();
		for (StepExecution se : je.getStepExecutions()) {
			String name = se.getStepName();
			if (name.contains(":")) {
				continue;                                   // partition workers are already aggregated into their manager
			}
			skipped += se.getSkipCount();
			switch (name) {
				case SyncSteps.FETCH -> {
					read = se.getReadCount();
					filtered = se.getFilterCount();
					fetchCtx = se.getExecutionContext();
				}
				case SyncSteps.ENRICH -> enriched = se.getWriteCount();
				case SyncSteps.SNAPSHOT -> snapshots = se.getWriteCount();
				default -> { }
			}
		}
		return new Stats(ctx.getInt("writer.inserted", fetchCtx.getInt("writer.inserted", 0)),
				ctx.getInt("writer.updated", fetchCtx.getInt("writer.updated", 0)),
				ctx.getInt("writer.unchanged", fetchCtx.getInt("writer.unchanged", 0)), read, filtered, skipped,
				enriched, snapshots);
	}
}
