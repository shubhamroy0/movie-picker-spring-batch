package com.moviepicker.config;

import java.net.http.HttpClient;

import com.moviepicker.tmdb.TmdbApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * Builds the real HTTP client. Not active in the "mock" profile.
 *
 * WHY RestClient (and not RestTemplate / WebClient)?
 *  - RestTemplate: legacy, in maintenance mode, deprecated for removal in a future Spring release; its API is a pile of
 *    overloads (getForObject, exchange, ...).
 *  - WebClient: reactive (Project Reactor). Excellent when the whole app is non-blocking; here the batch reader is
 *    plain blocking code, so we would just call .block() everywhere and drag in WebFlux for nothing.
 *  - RestClient (Spring 6.1+): synchronous like RestTemplate, but with WebClient's fluent builder style, and it is
 *    what the declarative @HttpExchange interfaces are built on. Right tool for blocking code.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!mock")
public class TmdbClientConfig {

	@Bean
	RestClient tmdbRestClient(RestClient.Builder builder, TmdbProperties props) {
		return buildRestClient(builder, props);
	}

	/** Static so tests can build the exact same client against a local stub server. */
	public static RestClient buildRestClient(RestClient.Builder builder, TmdbProperties props) {
		// Timeouts are NOT optional on outbound calls: without them one hung connection would hang the job forever.
		HttpClient httpClient = HttpClient.newBuilder().connectTimeout(props.connectTimeout()).build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(props.readTimeout());

		RestClient.Builder b = builder.baseUrl(props.baseUrl()).requestFactory(requestFactory);
		if (props.hasToken()) {
			// The secret is read from TMDB_API_TOKEN via properties; it is never logged or hard-coded.
			b.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiToken());
		}
		return b.build();
	}

	/** Turns the TmdbApi interface into a working client (a JDK dynamic proxy that delegates to RestClient). */
	@Bean
	TmdbApi tmdbApi(RestClient tmdbRestClient) {
		return createApi(tmdbRestClient);
	}

	public static TmdbApi createApi(RestClient restClient) {
		return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
				.build()
				.createClient(TmdbApi.class);
	}
}
