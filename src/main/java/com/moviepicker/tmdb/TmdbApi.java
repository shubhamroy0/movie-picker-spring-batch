package com.moviepicker.tmdb;

import com.moviepicker.tmdb.dto.TmdbGenreList;
import com.moviepicker.tmdb.dto.TmdbMovieDetail;
import com.moviepicker.tmdb.dto.TmdbMoviePage;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * DECLARATIVE HTTP client: we only describe the endpoints; at startup Spring generates a proxy
 * (HttpServiceProxyFactory) that turns each method call into a RestClient request - like Spring Data does for
 * repositories, or Feign in the Spring Cloud world. See TmdbClientConfig for where the proxy is created.
 *
 * It is deliberately "dumb": no retry, no throttling, no business logic. RestTmdbClient wraps it and adds those.
 * A null @RequestParam(required = false) is simply omitted from the URL.
 */
@HttpExchange(accept = "application/json")
public interface TmdbApi {

	@GetExchange("/3/genre/movie/list")
	TmdbGenreList genres(@RequestParam("language") String language);

	@GetExchange("/3/trending/movie/day")
	TmdbMoviePage trendingDay(@RequestParam("page") int page, @RequestParam("language") String language);

	@GetExchange("/3/movie/now_playing")
	TmdbMoviePage nowPlaying(@RequestParam("page") int page, @RequestParam("language") String language,
			@RequestParam(name = "region", required = false) String region);

	@GetExchange("/3/movie/popular")
	TmdbMoviePage popular(@RequestParam("page") int page, @RequestParam("language") String language,
			@RequestParam(name = "region", required = false) String region);

	@GetExchange("/3/movie/top_rated")
	TmdbMoviePage topRated(@RequestParam("page") int page, @RequestParam("language") String language,
			@RequestParam(name = "region", required = false) String region);

	@GetExchange("/3/movie/{id}")
	TmdbMovieDetail movie(@PathVariable("id") long id, @RequestParam("language") String language);
}
