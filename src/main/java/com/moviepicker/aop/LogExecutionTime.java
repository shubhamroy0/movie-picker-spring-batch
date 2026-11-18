package com.moviepicker.aop;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker annotation: "time this method". {@link ExecutionTimeAspect} finds it with the pointcut
 * {@code @annotation(logExecutionTime)}.
 *
 * WHY AN ANNOTATION instead of a package/name pattern: the business class states its own intent right where the method
 * is, the aspect stays generic, and you opt a method in or out by adding or removing one line. This is exactly how
 * Spring's own @Transactional, @Cacheable and @Async work: an annotation plus an aspect (an interceptor) behind it.
 *
 * RUNTIME retention is required: Spring AOP reads it by reflection while the app is running.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LogExecutionTime {

	/** Calls slower than this are logged at WARN instead of INFO. */
	long slowMs() default 500;
}
