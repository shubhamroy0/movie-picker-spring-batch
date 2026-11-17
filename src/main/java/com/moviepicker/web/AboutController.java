package com.moviepicker.web;

import com.moviepicker.web.ApiDtos.AboutDto;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** TMDB's terms require this attribution to be shown wherever its data is used. */
@RestController
public class AboutController {

	public static final String ATTRIBUTION = "This product uses the TMDB API but is not endorsed or certified by TMDB.";

	private final Environment env;

	public AboutController(Environment env) {
		this.env = env;
	}

	@GetMapping("/api/about")
	public AboutDto about() {
		boolean mock = env.matchesProfiles("mock");
		return new AboutDto("Movie Picker (Spring Batch + Spring Boot learning project)",
				mock ? "mock profile: bundled JSON fixtures, no network" : "TMDB API (https://www.themoviedb.org)",
				ATTRIBUTION, "https://www.themoviedb.org",
				"Learning project, not for production use.");
	}
}
