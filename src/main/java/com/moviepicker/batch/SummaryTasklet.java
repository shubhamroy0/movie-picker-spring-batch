package com.moviepicker.batch;

import com.moviepicker.domain.SyncRun;
import com.moviepicker.service.SyncRunService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

/**
 * TASKLET vs CHUNK: a chunk step is a loop (read -> process -> write, per item, many transactions) for DATA-parallel
 * work on many items. A Tasklet is ONE unit of work in ONE transaction: execute() is called, and returns FINISHED
 * (done) or CONTINUABLE (call me again). Right for "single action" steps: send a report, delete temp files, or, like
 * here, aggregate what the previous steps did.
 */
@Component
public class SummaryTasklet implements Tasklet {

	private static final Logger log = LoggerFactory.getLogger(SummaryTasklet.class);

	private final SyncRunService syncRuns;

	public SummaryTasklet(SyncRunService syncRuns) {
		this.syncRuns = syncRuns;
	}

	@Override
	public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
		var jobExecution = contribution.getStepExecution().getJobExecution();
		SyncRun run = syncRuns.recordCounts(jobExecution);          // persisted with this tasklet's transaction
		log.info("Summary step: {}", run.getMessage());
		return RepeatStatus.FINISHED;
	}
}
