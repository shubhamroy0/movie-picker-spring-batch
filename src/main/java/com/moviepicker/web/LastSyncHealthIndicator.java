package com.moviepicker.web;

import com.moviepicker.domain.SyncRun;
import com.moviepicker.repository.SyncRunRepository;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Custom Actuator health contributor: shows up under /actuator/health as "lastSync".
 * UP = last sync COMPLETED (or running); DOWN = last sync FAILED; UNKNOWN = never ran. The bean name
 * "lastSyncHealthIndicator" becomes the component name "lastSync" (Boot strips the HealthIndicator suffix).
 * A failing sync turns the overall status DOWN - which is what a Kubernetes readiness/alerting probe would react to,
 * so think about whether that is what you want (the REST API itself still works fine).
 */
@Component
public class LastSyncHealthIndicator implements HealthIndicator {

	private final SyncRunRepository runs;

	public LastSyncHealthIndicator(SyncRunRepository runs) {
		this.runs = runs;
	}

	@Override
	public Health health() {
		return runs.findFirstByOrderByIdDesc().map(LastSyncHealthIndicator::toHealth)
				.orElseGet(() -> Health.unknown().withDetail("lastSync", "no sync has run yet").build());
	}

	private static Health toHealth(SyncRun run) {
		Health.Builder b = switch (run.getStatus()) {
			case "FAILED" -> Health.down();
			case "COMPLETED", "RUNNING", "STARTED", "STARTING" -> Health.up();
			default -> Health.unknown();
		};
		return b.withDetail("jobExecutionId", run.getJobExecutionId())
				.withDetail("status", run.getStatus())
				.withDetail("finishedAt", String.valueOf(run.getFinishedAt()))
				.withDetail("summary", String.valueOf(run.getMessage()))
				.build();
	}
}
