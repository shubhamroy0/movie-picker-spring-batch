package com.moviepicker.web;

import java.net.URI;
import java.util.List;

import com.moviepicker.service.JobQueryService;
import com.moviepicker.service.SyncJobService;
import com.moviepicker.web.ApiDtos.JobExecutionDto;
import com.moviepicker.web.ApiDtos.MessageDto;
import com.moviepicker.web.ApiDtos.SyncStartedDto;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Job control. Launching returns 202 Accepted + a Location header, NOT 200/201: the work is not finished (a sync can take
 * minutes), so the client polls GET /api/jobs/executions/{id}. Holding an HTTP request open for a whole batch run would
 * hit proxy/client timeouts and tie up a server thread.
 */
@RestController
@RequestMapping("/api/jobs")
@Validated
public class JobController {

	private final SyncJobService jobs;
	private final JobQueryService queries;

	public JobController(SyncJobService jobs, JobQueryService queries) {
		this.jobs = jobs;
		this.queries = queries;
	}

	@PostMapping("/sync")
	public ResponseEntity<SyncStartedDto> sync() {
		return accepted(jobs.startSyncAsync("manual"));
	}

	@GetMapping("/executions")
	public List<JobExecutionDto> executions(@RequestParam(defaultValue = "10") @Min(1) @Max(100) int limit) {
		return queries.recent(limit);
	}

	@GetMapping("/executions/{id}")
	public JobExecutionDto execution(@PathVariable long id) {
		return queries.get(id);
	}

	@PostMapping("/executions/{id}/restart")
	public ResponseEntity<SyncStartedDto> restart(@PathVariable long id) {
		return accepted(jobs.restart(id));
	}

	@PostMapping("/executions/{id}/stop")
	public ResponseEntity<MessageDto> stop(@PathVariable long id) {
		jobs.stop(id);
		return ResponseEntity.accepted().body(new MessageDto(
				"Stop requested for execution " + id + ": it will end STOPPED after the current chunk commits"));
	}

	private static ResponseEntity<SyncStartedDto> accepted(JobExecution je) {
		String url = "/api/jobs/executions/" + je.getId();
		return ResponseEntity.accepted().location(URI.create(url))
				.body(new SyncStartedDto(je.getId(), je.getStatus().name(), url));
	}
}
