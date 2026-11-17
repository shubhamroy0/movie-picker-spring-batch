package com.moviepicker.config;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings for the scheduled sync job ({@code moviepicker.sync.*}).
 * The "demo" profile only overrides {@code interval} to PT30S - a good example of why profiles + typed properties
 * beat if/else code.
 */
@Validated
@ConfigurationProperties(prefix = "moviepicker.sync")
public record SyncProperties(
		boolean enabled,
		@NotNull Duration interval,
		@NotNull Duration initialDelay,
		/** Items per chunk = items per transaction. Also what hibernate.jdbc.batch_size is aligned to. */
		@Min(1) int chunkSize,
		/** Dev convenience: launch one sync right after startup (before the Phase-5 scheduler exists / in addition). */
		@DefaultValue("false") boolean runOnStartup,
		/** More skipped (corrupt) items than this in one step execution -> the step FAILS instead of hiding a bad feed. */
		@DefaultValue("10") @Min(0) int skipLimit,
		/** Phase 8: run the fetch step as one partition per TMDB list on a thread pool. */
		@DefaultValue("false") boolean partitioned,
		/** On startup, mark executions left STARTED by a dead process as FAILED. Safe ONLY with a single instance. */
		@DefaultValue("true") boolean recoverStaleOnStartup) {
}
