package com.moviepicker.aop;

import java.util.List;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An aspect tested WITHOUT starting Spring: AspectJProxyFactory is the programmatic version of what Spring Boot does for
 * every @Aspect bean (wrap the target in a proxy, apply the advice). Fast, and it shows there is no magic: a proxy is just
 * an object that runs the advice and then delegates.
 */
class ExecutionTimeAspectTest {

	/** Not annotated and not a bean: any plain class with an annotated public method can be advised. */
	public static class Target {

		@LogExecutionTime(slowMs = 10_000)
		public String fast() {
			return "ok";
		}

		@LogExecutionTime(slowMs = 0)
		public String alwaysSlow() {
			return "slow";
		}

		@LogExecutionTime
		public void explode() {
			throw new IllegalStateException("boom");
		}

		/** No annotation: the pointcut must NOT match, so nothing is logged for it. */
		public String untimed() {
			return "untimed";
		}
	}

	private final Logger aspectLogger = (Logger) LoggerFactory.getLogger(ExecutionTimeAspect.class);
	private ListAppender<ILoggingEvent> appender;
	private Target proxy;

	@BeforeEach
	void setUp() {
		appender = new ListAppender<>();
		appender.start();
		aspectLogger.addAppender(appender);

		AspectJProxyFactory factory = new AspectJProxyFactory(new Target());
		factory.addAspect(new ExecutionTimeAspect());
		proxy = factory.getProxy();
	}

	@AfterEach
	void tearDown() {
		aspectLogger.detachAppender(appender);
	}

	private List<String> messages() {
		return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
	}

	@Test
	void aMethodWithTheAnnotationIsTimedAndItsResultIsUntouched() {
		assertThat(proxy.fast()).isEqualTo("ok");

		assertThat(messages()).singleElement().asString().contains("Target.fast()").contains("took");
		assertThat(appender.list.get(0).getLevel().toString()).isEqualTo("INFO");
	}

	@Test
	void aCallOverTheLimitIsLoggedAsSlowAtWarn() {
		proxy.alwaysSlow();

		assertThat(messages()).singleElement().asString().startsWith("SLOW").contains("Target.alwaysSlow()");
		assertThat(appender.list.get(0).getLevel().toString()).isEqualTo("WARN");
	}

	@Test
	void aThrowingMethodIsStillTimedAndTheOriginalExceptionIsRethrownUnchanged() {
		assertThatThrownBy(proxy::explode).isInstanceOf(IllegalStateException.class).hasMessage("boom");

		assertThat(messages()).singleElement().asString().contains("Target.explode()");
	}

	@Test
	void aMethodWithoutTheAnnotationIsNotAdvised() {
		assertThat(proxy.untimed()).isEqualTo("untimed");

		assertThat(messages()).isEmpty();
	}
}
