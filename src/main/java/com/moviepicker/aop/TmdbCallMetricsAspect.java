package com.moviepicker.aop;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Times every call made through the TmdbClient interface and publishes it as a Micrometer timer, visible in
 * {@code GET /actuator/metrics/moviepicker.tmdb.calls} (add {@code ?tag=client:MockTmdbClient} to drill down).
 *
 * WHY AN ASPECT and not code inside the client: the real client, the mock client and the retry decorator all implement the
 * same interface; one pointcut on the interface measures all of them, including any implementation added later, and none
 * of them mentions metrics.
 *
 * WHAT THE NUMBERS MEAN - two beans match the pointcut, so you get two series per operation:
 *   client=ResilientTmdbClient : what the batch job experienced, retries and back-off INCLUDED (one per logical call)
 *   client=RestTmdbClient/MockTmdbClient : each individual attempt (a call retried twice shows 3 here, 1 there)
 * Comparing the two counts tells you how often retries happen. {@code joinPoint.getTarget()} is the REAL object behind the
 * proxy, so its class name is the implementation, not the proxy class.
 *
 * Tags are kept low-cardinality on purpose (client, operation, outcome, exception class). Never tag a metric with an id or a
 * page number: every distinct value creates a new time series and memory grows without bound.
 *
 * The registry is fetched lazily from an ObjectProvider: if no registry exists (a slimmed-down test context) the call just
 * proceeds unmeasured instead of failing the application.
 */
@Aspect
@Component
@Order(40)
public class TmdbCallMetricsAspect {

	public static final String METER = "moviepicker.tmdb.calls";

	private final ObjectProvider<MeterRegistry> registry;

	public TmdbCallMetricsAspect(ObjectProvider<MeterRegistry> registry) {
		this.registry = registry;
	}

	@Around("com.moviepicker.aop.Pointcuts.tmdbClientCall()")
	public Object measure(ProceedingJoinPoint joinPoint) throws Throwable {
		MeterRegistry meters = registry.getIfAvailable();
		if (meters == null) {
			return joinPoint.proceed();
		}
		Timer.Sample sample = Timer.start(meters);
		String outcome = "success";
		String exception = "none";
		try {
			return joinPoint.proceed();
		}
		catch (Throwable failure) {
			outcome = "error";
			exception = failure.getClass().getSimpleName();
			throw failure;
		}
		finally {
			sample.stop(Timer.builder(METER)
					.description("Calls through the TmdbClient interface")
					.tag("client", joinPoint.getTarget().getClass().getSimpleName())
					.tag("operation", joinPoint.getSignature().getName())
					.tag("outcome", outcome)
					.tag("exception", exception)
					.register(meters));
		}
	}
}
