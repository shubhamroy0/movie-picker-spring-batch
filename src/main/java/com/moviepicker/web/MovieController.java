package com.moviepicker.web;

import java.util.List;

import com.moviepicker.service.MovieService;
import com.moviepicker.web.ApiDtos.GenreDto;
import com.moviepicker.web.ApiDtos.MovieDto;
import com.moviepicker.web.ApiDtos.PageResponse;
import com.moviepicker.web.ApiDtos.PickDto;
import com.moviepicker.web.ApiDtos.TrendingResponse;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Pageable;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin on purpose: bind + validate HTTP input, delegate to the service, return a DTO. No queries, no mapping here.
 * Pageable is resolved by Spring Data's argument resolver from ?page=&size=&sort=field,dir (size capped by
 * spring.data.web.pageable.max-page-size, so nobody can ask for 1,000,000 rows).
 * NOTE: /trending and /picks are declared next to /{tmdbId}: Spring prefers the literal path over the template, and
 * {tmdbId} is a long, so "trending" could never match it anyway.
 */
@RestController
@RequestMapping("/api")
@Validated
public class MovieController {

	private final MovieService movies;

	public MovieController(MovieService movies) {
		this.movies = movies;
	}

	@GetMapping("/movies")
	public PageResponse<MovieDto> search(
			@RequestParam(required = false) String genre,
			@RequestParam(required = false) @DecimalMin("0") @DecimalMax("10") Double minRating,
			@RequestParam(required = false) @Min(1888) @Max(2200) Integer year,
			@RequestParam(required = false) String language,
			@RequestParam(required = false, name = "q") String title,
			Pageable pageable) {
		return movies.search(genre, minRating, year, language, title, pageable);
	}

	@GetMapping("/movies/{tmdbId}")
	public MovieDto get(@PathVariable long tmdbId) {
		return movies.get(tmdbId);
	}

	@GetMapping("/movies/trending")
	public TrendingResponse trending(@RequestParam(name = "list", defaultValue = "trending_day") String list) {
		return movies.trending(list);
	}

	@GetMapping("/movies/picks")
	public List<PickDto> picks(@RequestParam(required = false) String genre,
			@RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
		return movies.picks(genre, limit);
	}

	@GetMapping("/genres")
	public List<GenreDto> genres() {
		return movies.genres();
	}
}
