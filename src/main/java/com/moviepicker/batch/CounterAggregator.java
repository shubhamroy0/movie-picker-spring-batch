package com.moviepicker.batch;

import java.util.Collection;
import java.util.List;

import org.springframework.batch.core.partition.StepExecutionAggregator;
import org.springframework.batch.core.partition.support.DefaultStepExecutionAggregator;
import org.springframework.batch.core.step.StepExecution;

/**
 * After all workers finish the manager must report ONE result. The built-in DefaultStepExecutionAggregator already sums
 * the framework counters (read / write / filter / skip / commit / rollback) and combines the exit statuses, but it does
 * NOT touch ExecutionContexts. Our inserted/updated/unchanged numbers live in each worker's context, so this
 * aggregator also sums them into the manager's context, where the promotion listener copies them to the job context
 * (exactly where the decider and the summary expect to find them in the non-partitioned design).
 */
class CounterAggregator implements StepExecutionAggregator {

	private static final List<String> KEYS = List.of(MovieUpsertWriter.KEY_INSERTED, MovieUpsertWriter.KEY_UPDATED,
			MovieUpsertWriter.KEY_UNCHANGED, MovieUpsertWriter.KEY_REPEATS);

	private final StepExecutionAggregator delegate = new DefaultStepExecutionAggregator();

	@Override
	public void aggregate(StepExecution result, Collection<StepExecution> executions) {
		delegate.aggregate(result, executions);
		for (String key : KEYS) {
			int sum = 0;
			for (StepExecution worker : executions) {
				sum += worker.getExecutionContext().getInt(key, 0);
			}
			result.getExecutionContext().putInt(key, sum);
		}
	}
}
