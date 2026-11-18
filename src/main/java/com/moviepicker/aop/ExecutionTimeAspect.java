package com.moviepicker.aop;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * ADVICE TYPE: @Around - the most powerful one. It receives a {@link ProceedingJoinPoint}; the target method runs ONLY
 * when this advice calls {@code proceed()}. So an @Around advice can run code before and after, change the arguments,
 * change or swallow the result, swallow or translate the exception, or skip the call entirely (that is how caching works).
 * With great power: forget to call proceed() and the real method silently never runs.
 *
 * Here it only measures. Two details that matter:
 *  - the timing is taken in a {@code finally} so a method that THROWS is timed too;
 *  - the original exception is rethrown unchanged (an aspect that wraps exceptions changes the contract of the method).
 *
 * @Order(10): when several aspects (or the transaction interceptor) apply to the same method, the LOWER number is the
 * OUTER layer. 10 wraps the @Transactional proxy (which has the lowest precedence), so the time we log includes opening
 * and committing the transaction. Give this aspect a high number and it would time only the method body inside the tx.
 */
@Aspect
@Component
@Order(10)
public class ExecutionTimeAspect {

	private static final Logger log = LoggerFactory.getLogger(ExecutionTimeAspect.class);

	/**
	 * "@annotation(logExecutionTime)" does two jobs: it selects methods carrying the annotation AND binds that annotation
	 * instance to the parameter of the same name, so we can read slowMs() without any reflection.
	 */
	@Around("@annotation(logExecutionTime)")
	public Object time(ProceedingJoinPoint joinPoint, LogExecutionTime logExecutionTime) throws Throwable {
		long start = System.nanoTime();
		try {
			return joinPoint.proceed();
		}
		finally {
			long millis = (System.nanoTime() - start) / 1_000_000;
			if (millis >= logExecutionTime.slowMs()) {
				log.warn("SLOW {} took {} ms (limit {} ms)", joinPoint.getSignature().toShortString(), millis,
						logExecutionTime.slowMs());
			}
			else {
				log.info("{} took {} ms", joinPoint.getSignature().toShortString(), millis);
			}
		}
	}
}
