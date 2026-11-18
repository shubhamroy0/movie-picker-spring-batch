package com.moviepicker.aop;

import java.util.Arrays;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * ADVICE TYPES: @Before, @AfterReturning and @AfterThrowing - the three "observer" advices. They cannot stop the call or
 * change its result (that needs @Around); they just watch. Use the weakest advice that does the job: it is harder to
 * get wrong.
 *
 *   @Before          runs before the method; if it THROWS, the method is never called.
 *   @AfterReturning  runs only when the method returned normally; can read the return value.
 *   @AfterThrowing   runs only when the method threw; can read the exception (it is rethrown afterwards regardless).
 *   @After           (not used here) runs in BOTH cases, like a finally block, and sees neither result nor exception.
 *
 * Execution order for one call: Around(before part) -> Before -> METHOD -> AfterReturning/AfterThrowing -> After
 * -> Around(after part).
 *
 * The pointcut selects every controller method. Because the ProblemDetail handler ({@code @RestControllerAdvice}) is NOT a
 * @RestController, an exception thrown by a controller is seen here as the raw domain exception (e.g.
 * MovieNotFoundException), BEFORE it is turned into a 404 body. Bean-validation failures on parameters are raised by
 * Spring MVC before it calls the controller, so they never reach this aspect.
 *
 * DEBUG level on purpose (a log line per request is noise in production); the "demo" profile turns it on.
 * Controller arguments are ids, filters and paging values: nothing secret. Never log arguments blindly in an aspect
 * that also covers a login or payment method.
 */
@Aspect
@Component
@Order(20)
public class ApiRequestLogAspect {

	private static final Logger log = LoggerFactory.getLogger(ApiRequestLogAspect.class);

	@Before("com.moviepicker.aop.Pointcuts.inWebLayer()")
	public void logCall(JoinPoint joinPoint) {
		if (log.isDebugEnabled()) {
			log.debug("-> {} args={}", joinPoint.getSignature().toShortString(), Arrays.toString(joinPoint.getArgs()));
		}
	}

	/** "returning" binds the result to the parameter; the advice only runs if the type matches (Object = anything). */
	@AfterReturning(pointcut = "com.moviepicker.aop.Pointcuts.inWebLayer()", returning = "result")
	public void logResult(JoinPoint joinPoint, Object result) {
		if (log.isDebugEnabled()) {
			log.debug("<- {} returned {}", joinPoint.getSignature().toShortString(),
					result == null ? "null" : result.getClass().getSimpleName());
		}
	}

	@AfterThrowing(pointcut = "com.moviepicker.aop.Pointcuts.inWebLayer()", throwing = "error")
	public void logFailure(JoinPoint joinPoint, Throwable error) {
		if (log.isDebugEnabled()) {
			log.debug("!! {} threw {}: {}", joinPoint.getSignature().toShortString(),
					error.getClass().getSimpleName(), error.getMessage());
		}
	}
}
