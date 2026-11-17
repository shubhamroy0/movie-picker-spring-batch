package com.moviepicker.batch;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.moviepicker.tmdb.TmdbList;
import org.springframework.batch.core.partition.Partitioner;
import org.springframework.batch.infrastructure.item.ExecutionContext;

/**
 * PARTITIONING in one picture:  a MANAGER step asks a Partitioner "how do I split the work?" and gets back a map
 * {partitionName -> ExecutionContext}. For each entry it creates a separate WORKER StepExecution (own row in
 * BATCH_STEP_EXECUTION, own ExecutionContext, own transaction/chunk cycle, own restart state) and runs the workers
 * through a TaskExecutor in parallel. The manager finishes when all workers finish, and its status/counters are the
 * aggregate of theirs.
 *
 * Our split: ONE PARTITION PER TMDB LIST (trending / now_playing / popular / top_rated) - a natural, independent key
 * range. The worker's @StepScope reader reads #{stepExecutionContext['listName']} to know which list is "its" list.
 * (gridSize is ignored: the number of lists decides the number of partitions.)
 */
class ListPartitioner implements Partitioner {

	static final String KEY_LIST_NAME = "listName";

	private final List<TmdbList> lists;

	ListPartitioner(List<TmdbList> lists) {
		this.lists = List.copyOf(lists);
	}

	@Override
	public Map<String, ExecutionContext> partition(int gridSize) {
		Map<String, ExecutionContext> partitions = new HashMap<>();
		for (TmdbList list : lists) {
			ExecutionContext ctx = new ExecutionContext();
			ctx.putString(KEY_LIST_NAME, list.listName());
			partitions.put("partition-" + list.listName(), ctx);   // the key becomes part of the worker step name
		}
		return partitions;
	}
}
