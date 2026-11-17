package com.moviepicker.tmdb;

import com.moviepicker.config.TmdbProperties;
import com.moviepicker.service.GenreCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Runs once, right after the application context is ready (ApplicationRunner). Fails SOFT: it logs a clear,
 * actionable message instead of crashing the app, because the REST API and H2 console are still useful without TMDB.
 * Never logs the token itself - only whether one is present.
 */
@Component
public class TmdbStartupCheck implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(TmdbStartupCheck.class);

	private final TmdbProperties props;
	private final GenreCatalog genreCatalog;
	private final Environment environment;

	public TmdbStartupCheck(TmdbProperties props, GenreCatalog genreCatalog, Environment environment) {
		this.props = props;
		this.genreCatalog = genreCatalog;
		this.environment = environment;
	}

	@Override
	public void run(ApplicationArguments args) {
		boolean mock = environment.acceptsProfiles(Profiles.of("mock"));
		if (mock) {
			log.info("TMDB mode: MOCK - serving JSON fixtures, no network access, no token needed.");
		}
		else if (!props.hasToken()) {
			log.error("""

					======================================================================
					TMDB_API_TOKEN is not set, so the real TMDB API cannot be used.
					  * Get a token:  https://www.themoviedb.org/settings/api  ("API Read Access Token")
					  * This terminal only:  $env:TMDB_API_TOKEN = "<token>"
					  * Persistent (new terminal needed):  setx TMDB_API_TOKEN "<token>"
					  * Or run without network:  .\\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=mock"
					======================================================================""");
			return;
		}
		else {
			log.info("TMDB mode: REAL API at {} (token present: yes)", props.baseUrl());
		}

		try {
			int count = genreCatalog.refresh();
			log.info("TMDB connectivity check OK: loaded {} genres (lists to sync: {}, {} page(s) each).",
					count, props.lists(), props.pagesPerList());
		}
		catch (RuntimeException e) {
			log.error("TMDB connectivity check FAILED: {}. Check the token/network, or use the mock profile.",
					e.toString());
		}
	}
}
