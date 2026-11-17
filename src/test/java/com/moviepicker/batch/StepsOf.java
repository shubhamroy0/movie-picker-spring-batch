package com.moviepicker.batch;

import java.util.Comparator;
import java.util.List;

import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepExecution;

/** Test helper: find steps of an execution by name, in execution order. */
final class StepsOf {

	private StepsOf() {
	}

	static StepExecution step(JobExecution e, String name) {
		return e.getStepExecutions().stream().filter(s -> s.getStepName().equals(name)).findFirst().orElseThrow();
	}

	static StepExecution fetch(JobExecution e) {
		return step(e, SyncSteps.FETCH);
	}

	static List<String> names(JobExecution e) {
		return e.getStepExecutions().stream().sorted(Comparator.comparing(StepExecution::getId))
				.map(StepExecution::getStepName).toList();
	}
}
