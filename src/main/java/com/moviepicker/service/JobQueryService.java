package com.moviepicker.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.moviepicker.domain.SyncRun;
import com.moviepicker.repository.SyncRunRepository;
import com.moviepicker.web.ApiDtos.JobExecutionDto;
import com.moviepicker.web.ApiDtos.StepExecutionDto;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Read model for the job-control API: turns Spring Batch's metadata objects into DTOs. The list comes from our own
 * SyncRun table (one row per JobExecution, written by SyncRunListener), the details from the Batch JobRepository, which
 * is the source of truth for statuses and step counters.
 */
@Service
public class JobQueryService {

	private final SyncJobService jobs;
	private final SyncRunRepository runs;
	private final SyncRunService runService;

	public JobQueryService(SyncJobService jobs, SyncRunRepository runs, SyncRunService runService) {
		this.jobs = jobs;
		this.runs = runs;
		this.runService = runService;
	}

	/** Newest first. Executions that no longer exist in the Batch tables (manual cleanup) are skipped. */
	public List<JobExecutionDto> recent(int limit) {
		return runs.findAllByOrderByIdDesc(PageRequest.of(0, limit)).stream().map(SyncRun::getJobExecutionId)
				.map(id -> {
					try {
						return toDto(jobs.find(id), false);
					}
					catch (ExecutionNotFoundException e) {
						return null;
					}
				}).filter(java.util.Objects::nonNull).toList();
	}

	public JobExecutionDto get(long executionId) {
		return toDto(jobs.find(executionId), true);
	}

	public JobExecutionDto toDto(JobExecution je, boolean withSteps) {
		Instant start = instant(je.getStartTime());
		Instant end = instant(je.getEndTime());
		Map<String, String> params = new TreeMap<>();
		je.getJobParameters().parameters().forEach(p -> params.put(p.name(), String.valueOf(p.value())));
		List<StepExecutionDto> steps = withSteps
				? je.getStepExecutions().stream().sorted(java.util.Comparator.comparing(StepExecution::getId))
						.map(JobQueryService::toDto).toList()
				: List.of();
		return new JobExecutionDto(je.getId(), je.getJobInstanceId(), je.getJobInstance().getJobName(),
				je.getStatus().name(), je.getExitStatus().getExitCode(), firstLine(je.getExitStatus().getExitDescription()),
				start, end, millis(start, end), params, runService.stats(je).oneLine(), steps);
	}

	private static StepExecutionDto toDto(StepExecution se) {
		Instant start = instant(se.getStartTime());
		Instant end = instant(se.getEndTime());
		return new StepExecutionDto(se.getId(), se.getStepName(), se.getStatus().name(), se.getReadCount(),
				se.getFilterCount(), se.getWriteCount(), se.getSkipCount(), se.getCommitCount(), se.getRollbackCount(),
				se.getExitStatus().getExitCode(), firstLine(se.getExitStatus().getExitDescription()), start, end,
				millis(start, end));
	}

	/** Batch stores LocalDateTime (no zone) in the JVM's zone; the API speaks UTC instants. */
	private static Instant instant(LocalDateTime t) {
		return t == null ? null : t.atZone(ZoneId.systemDefault()).toInstant();
	}

	private static Long millis(Instant start, Instant end) {
		return start == null || end == null ? null : Duration.between(start, end).toMillis();
	}

	/**
	 * Exit descriptions are whole stack traces. The first line is usually a framework wrapper ("Unable to process chunk"),
	 * the useful part is the LAST "Caused by:" line = the root cause. Truncated so the response stays small.
	 */
	private static String firstLine(String s) {
		if (s == null || s.isBlank()) {
			return null;
		}
		String best = s.lines().filter(l -> l.startsWith("Caused by:")).reduce((a, b) -> b)
				.orElseGet(() -> s.lines().findFirst().orElse(s));
		return best.length() > 300 ? best.substring(0, 300) + "..." : best;
	}
}
