package com.moviepicker.config;

import java.time.Duration;
import java.util.List;

import com.moviepicker.tmdb.TmdbList;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Typed, validated view of the {@code moviepicker.tmdb.*} settings.
 *
 * WHY a record + @ConfigurationProperties instead of scattered @Value("${...}"):
 *  - one place lists every setting (discoverable), with types (Duration, enum list) instead of raw strings
 *  - Bean Validation runs at STARTUP: a bad value fails fast with a readable message
 *  - records are immutable, and constructor binding means no setters
 *  - relaxed binding: {@code pages-per-list}, {@code pagesPerList} and env var {@code MOVIEPICKER_TMDB_PAGESPERLIST} all work
 */
@Validated
@ConfigurationProperties(prefix = "moviepicker.tmdb")
public record TmdbProperties(
		@NotBlank String baseUrl,
		/** Bearer token. Blank is legal (mock profile) but the startup check warns when the real API is used. */
		@DefaultValue("") String apiToken,
		@NotBlank String language,
		String region,
		@Min(1) int pagesPerList,
		@NotEmpty List<TmdbList> lists,
		@NotNull Duration connectTimeout,
		@NotNull Duration readTimeout,
		@NotNull Duration requestInterval,
		@Valid @NotNull Retry retry) {

	public boolean hasToken() {
		return apiToken != null && !apiToken.isBlank();
	}

	/** Exponential backoff settings: wait = initialBackoff * multiplier^attempt, capped at maxBackoff. */
	public record Retry(
			@Min(1) int maxAttempts,
			@NotNull Duration initialBackoff,
			@Positive double multiplier,
			@NotNull Duration maxBackoff) {
	}
}
