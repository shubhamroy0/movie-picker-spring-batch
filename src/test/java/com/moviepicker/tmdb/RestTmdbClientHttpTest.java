package com.moviepicker.tmdb;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.moviepicker.config.TmdbClientConfig;
import com.moviepicker.config.TmdbProperties;
import com.moviepicker.tmdb.dto.TmdbMoviePage;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the REAL RestClient / @HttpExchange stack over a REAL socket against a stub server.
 *
 * WHY the JDK's built-in com.sun.net.httpserver and not WireMock or MockRestServiceServer?
 *  - MockRestServiceServer replaces the HTTP layer, so it cannot test real timeouts or socket behaviour.
 *  - WireMock is excellent but adds a big dependency whose Boot 4 / Jetty 12 compatibility must be managed.
 *  - The JDK server is zero-dependency, binds a random free port, and is enough for status codes, headers and delays.
 * (MockRestServiceServer remains the right tool for pure "does my request look right" tests.)
 */
class RestTmdbClientHttpTest {

	record Reply(int status, String body, String retryAfter, long delayMillis) {
		static Reply ok(String body) { return new Reply(200, body, null, 0); }
		static Reply status(int s, String retryAfter) { return new Reply(s, "{}", retryAfter, 0); }
	}

	record Seen(String uri, String authorization) { }

	private HttpServer server;
	private final Deque<Reply> replies = new ArrayDeque<>();
	private final List<Seen> seen = new CopyOnWriteArrayList<>();
	private String baseUrl;

	private static final String PAGE_JSON = """
			{"page":2,"total_pages":9,"total_results":180,"unexpected_new_field":true,"results":[
			 {"id":7,"title":"Seven","original_title":"Sept","release_date":"","vote_average":7.5,"vote_count":10,
			  "popularity":1.5,"adult":false,"genre_ids":[28,12]}]}""";

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			seen.add(new Seen(exchange.getRequestURI().toString(), exchange.getRequestHeaders().getFirst("Authorization")));
			Reply r = replies.isEmpty() ? Reply.status(500, null) : replies.poll();
			try {
				if (r.delayMillis() > 0) {
					Thread.sleep(r.delayMillis());
				}
				byte[] bytes = r.body().getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				if (r.retryAfter() != null) {
					exchange.getResponseHeaders().add("Retry-After", r.retryAfter());
				}
				exchange.sendResponseHeaders(r.status(), bytes.length);
				exchange.getResponseBody().write(bytes);
			}
			catch (IOException | InterruptedException ignored) {
				// client gave up (timeout test): nothing to do
			}
			finally {
				exchange.close();
			}
		});
		server.start();
		baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	private RestTmdbClient client(Duration readTimeout) {
		TmdbProperties props = new TmdbProperties(baseUrl, "secret-token", "en-US", "US", 3,
				List.of(TmdbList.POPULAR), Duration.ofSeconds(2), readTimeout, Duration.ZERO,
				new TmdbProperties.Retry(3, Duration.ofMillis(10), 2.0, Duration.ofMillis(100)));
		RestClient rc = TmdbClientConfig.buildRestClient(RestClient.builder(), props);
		return new RestTmdbClient(TmdbClientConfig.createApi(rc), props);
	}

	@Test
	void sendsBearerTokenAndQueryParametersAndMapsSnakeCaseJson() {
		replies.add(Reply.ok(PAGE_JSON));

		TmdbMoviePage page = client(Duration.ofSeconds(2)).fetchPage(TmdbList.POPULAR, 2);

		assertThat(seen).hasSize(1);
		assertThat(seen.get(0).authorization()).isEqualTo("Bearer secret-token");
		assertThat(seen.get(0).uri()).startsWith("/3/movie/popular")
				.contains("page=2").contains("language=en-US").contains("region=US");
		assertThat(page.totalPages()).isEqualTo(9);                                       // total_pages -> totalPages
		assertThat(page.results().get(0).releaseDate()).isEmpty();                        // "" survives (String field)
		assertThat(page.results().get(0).genreIds()).containsExactly(28, 12);
	}

	@Test
	void status429BecomesTransientWithRetryAfter() {
		replies.add(Reply.status(429, "7"));

		assertThatThrownBy(() -> client(Duration.ofSeconds(2)).fetchPage(TmdbList.POPULAR, 1))
				.isInstanceOfSatisfying(TmdbTransientException.class,
						e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(7)));
	}

	@Test
	void status503BecomesTransientWithoutRetryAfter() {
		replies.add(Reply.status(503, null));

		assertThatThrownBy(() -> client(Duration.ofSeconds(2)).fetchPage(TmdbList.POPULAR, 1))
				.isInstanceOfSatisfying(TmdbTransientException.class, e -> assertThat(e.retryAfter()).isNull());
	}

	@Test
	void status401BecomesPermanent() {
		replies.add(Reply.status(401, null));

		assertThatThrownBy(() -> client(Duration.ofSeconds(2)).fetchPage(TmdbList.POPULAR, 1))
				.isInstanceOf(TmdbPermanentException.class).hasMessageContaining("401");
	}

	@Test
	void detail404BecomesNotFound() {
		replies.add(Reply.status(404, null));

		assertThatThrownBy(() -> client(Duration.ofSeconds(2)).fetchDetail(123))
				.isInstanceOf(TmdbNotFoundException.class);
	}

	@Test
	void readTimeoutBecomesTransient() {
		replies.add(new Reply(200, PAGE_JSON, null, 1500));      // server answers after 1.5 s, client waits 300 ms

		assertThatThrownBy(() -> client(Duration.ofMillis(300)).fetchPage(TmdbList.POPULAR, 1))
				.isInstanceOf(TmdbTransientException.class).hasMessageContaining("timed out");
	}

	@Test
	void resilientClientRecoversFromA429OverARealSocket() {
		replies.add(Reply.status(429, "0"));                      // Retry-After: 0 -> retry at once
		replies.add(Reply.ok(PAGE_JSON));
		List<Duration> sleeps = new ArrayList<>();
		ResilientTmdbClient resilient = new ResilientTmdbClient(client(Duration.ofSeconds(2)),
				new TmdbProperties.Retry(3, Duration.ofMillis(10), 2.0, Duration.ofMillis(100)), sleeps::add);

		TmdbMoviePage page = resilient.fetchPage(TmdbList.POPULAR, 2);

		assertThat(page.results()).hasSize(1);
		assertThat(seen).hasSize(2);                              // 1st attempt 429, 2nd attempt 200
		assertThat(sleeps).containsExactly(Duration.ZERO);
	}
}
