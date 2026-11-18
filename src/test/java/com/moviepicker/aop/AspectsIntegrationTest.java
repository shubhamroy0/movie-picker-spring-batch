package com.moviepicker.aop;

import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.moviepicker.scheduler.SchedulerControl;
import com.moviepicker.service.ExecutionNotFoundException;
import com.moviepicker.service.MovieService;
import com.moviepicker.service.SyncJobService;
import com.moviepicker.tmdb.TmdbClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.batch.core.BatchStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The aspects inside the REAL application context: proxies are created by Spring Boot's auto-proxy creator, advice runs
 * for calls that arrive from other beans, and the rules of Spring AOP (self-invocation, double matching) are visible.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"mock", "test"})
@TestPropertySource(properties = "moviepicker.mock.rotate-data=false")
class AspectsIntegrationTest {

	@Autowired SyncJobService sync;
	@Autowired SchedulerControl scheduler;
	@Autowired MovieService movies;
	@Autowired TmdbClient tmdbClient;
	@Autowired AuditLog auditLog;
	@Autowired MeterRegistry meters;
	@Autowired MockMvc mvc;

	private final Logger requestLogger = (Logger) LoggerFactory.getLogger(ApiRequestLogAspect.class);
	private final Logger timingLogger = (Logger) LoggerFactory.getLogger(ExecutionTimeAspect.class);
	private ListAppender<ILoggingEvent> requestLines;
	private ListAppender<ILoggingEvent> timingLines;
	private Level originalRequestLevel;

	@BeforeEach
	void setUp() {
		auditLog.clear();
		requestLines = attach(requestLogger);
		timingLines = attach(timingLogger);
		originalRequestLevel = requestLogger.getLevel();
		requestLogger.setLevel(Level.DEBUG);          // the request log is DEBUG: off unless a profile/test turns it on
	}

	@AfterEach
	void tearDown() {
		requestLogger.detachAppender(requestLines);
		timingLogger.detachAppender(timingLines);
		requestLogger.setLevel(originalRequestLevel);
	}

	private static ListAppender<ILoggingEvent> attach(Logger logger) {
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		return appender;
	}

	private static List<String> lines(ListAppender<ILoggingEvent> appender) {
		return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
	}

	// ---- proxies ---------------------------------------------------------------------------------------------------

	@Test
	void beansThatMatchAPointcutAreReplacedByProxies() {
		// What you @Autowired is NOT the object you wrote; it is a CGLIB subclass that runs the advice, then delegates.
		assertThat(AopUtils.isAopProxy(sync)).isTrue();
		assertThat(AopUtils.isCglibProxy(sync)).isTrue();
		assertThat(AopUtils.isAopProxy(scheduler)).isTrue();
		assertThat(AopUtils.isAopProxy(tmdbClient)).isTrue();
		assertThat(AopUtils.isAopProxy(movies)).isTrue();       // also proxied for @Transactional: ONE proxy, many advisors
		// getClass() lies, AopUtils tells the truth about the real class behind the proxy
		assertThat(AopUtils.getTargetClass(sync).getSimpleName()).isEqualTo("SyncJobService");
	}

	// ---- audit aspect ----------------------------------------------------------------------------------------------

	@Test
	void aLaunchIsAuditedOnceEvenThoughStartSyncCallsStartSyncAsyncInternally() {
		assertThat(sync.startSync("audit-test").getStatus()).isEqualTo(BatchStatus.COMPLETED);

		List<AuditLog.Entry> entries = auditLog.recent(10);
		// SELF-INVOCATION: startSync -> this.startSyncAsync is not routed through the proxy, so it is NOT advised.
		// The pointcut matches both methods; only the outermost call (from the test bean) was intercepted.
		assertThat(entries).singleElement().satisfies(e -> {
			assertThat(e.action()).isEqualTo("startSync");
			assertThat(e.argument()).isEqualTo("audit-test");
			assertThat(e.outcome()).isEqualTo("SUCCESS");
			assertThat(e.detail()).contains("status=COMPLETED").contains("executionId=");
		});
		assertThat(entries).noneMatch(e -> e.action().equals("startSyncAsync"));
	}

	@Test
	void aFailingCallIsAuditedAsFailureAndTheExceptionStillReachesTheCaller() {
		assertThatThrownBy(() -> sync.restart(987_654_321L)).isInstanceOf(ExecutionNotFoundException.class);

		assertThat(auditLog.recent(10)).singleElement().satisfies(e -> {
			assertThat(e.action()).isEqualTo("restart");
			assertThat(e.argument()).isEqualTo("987654321");
			assertThat(e.outcome()).isEqualTo("FAILURE");
			assertThat(e.detail()).startsWith("ExecutionNotFoundException");
		});
	}

	@Test
	void pauseAndResumeAreAuditedToo() {
		scheduler.pause();
		scheduler.resume();

		// newest first
		assertThat(auditLog.recent(10)).extracting(AuditLog.Entry::action).containsExactly("resume", "pause");
	}

	@Test
	void theAuditTrailIsReadableOverRest() throws Exception {
		scheduler.pause();
		scheduler.resume();

		mvc.perform(get("/api/audit?limit=5")).andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].action").value("resume"))
				.andExpect(jsonPath("$[0].outcome").value("SUCCESS"))
				.andExpect(jsonPath("$[1].action").value("pause"));
		mvc.perform(get("/api/audit?limit=0")).andExpect(status().isBadRequest());
	}

	// ---- metrics aspect --------------------------------------------------------------------------------------------

	@Test
	void everyTmdbClientCallIsTimedOnTheOutsideAndOnTheInside() {
		long outerBefore = count("ResilientTmdbClient");
		long innerBefore = count("MockTmdbClient");

		tmdbClient.genres();

		// One logical call, two beans matched by the interface pointcut: the retry decorator (outside) and the mock
		// client it wraps (inside, one per attempt). With no failures the two counts move together.
		assertThat(count("ResilientTmdbClient")).isEqualTo(outerBefore + 1);
		assertThat(count("MockTmdbClient")).isEqualTo(innerBefore + 1);
	}

	private long count(String client) {
		Timer timer = meters.find(TmdbCallMetricsAspect.METER).tag("client", client).tag("operation", "genres")
				.tag("outcome", "success").timer();
		return timer == null ? 0 : timer.count();
	}

	// ---- execution time + request log ------------------------------------------------------------------------------

	@Test
	void anAnnotatedServiceMethodIsTimedWhenCalledThroughTheController() throws Exception {
		mvc.perform(get("/api/movies?size=1")).andExpect(status().isOk());

		assertThat(lines(timingLines)).anyMatch(l -> l.contains("MovieService.search(..) took"));
	}

	@Test
	void theRequestLogSeesTheCallTheResultAndTheRawDomainException() throws Exception {
		mvc.perform(get("/api/movies/999999999")).andExpect(status().isNotFound());

		List<String> log = lines(requestLines);
		assertThat(log).anyMatch(l -> l.startsWith("-> MovieController.get(") && l.contains("999999999"));
		// the advice runs BEFORE @RestControllerAdvice turns the exception into a 404 ProblemDetail
		assertThat(log).anyMatch(l -> l.startsWith("!! MovieController.get(") && l.contains("MovieNotFoundException"));
		assertThat(log).noneMatch(l -> l.startsWith("<- MovieController.get("));

		requestLines.list.clear();
		mvc.perform(get("/api/about")).andExpect(status().isOk());
		assertThat(lines(requestLines)).anyMatch(l -> l.startsWith("<- AboutController.") && l.contains("AboutDto"));
	}
}
