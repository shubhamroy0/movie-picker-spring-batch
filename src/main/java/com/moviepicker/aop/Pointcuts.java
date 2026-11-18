package com.moviepicker.aop;

import org.aspectj.lang.annotation.Pointcut;

/**
 * Named, reusable pointcuts. Aspects refer to them as "com.moviepicker.aop.Pointcuts.name()".
 *
 * VOCABULARY (the five words every AOP interview starts with):
 *  - Aspect    : a class holding cross-cutting behaviour (@Aspect).
 *  - Advice    : the behaviour itself, one method (@Before, @After, @AfterReturning, @AfterThrowing, @Around).
 *  - Pointcut  : an expression that selects WHICH method calls the advice applies to.
 *  - Join point: one concrete method call that a pointcut matched (in Spring AOP always a method EXECUTION).
 *  - Weaving   : connecting aspects to targets. Spring AOP does it at RUNTIME with proxies; full AspectJ does it at
 *                compile/load time by changing bytecode. We use Spring AOP, so only calls that arrive THROUGH THE PROXY
 *                (i.e. from another bean) are intercepted - see the self-invocation note on SyncJobService.
 *
 * This class is not a bean and not an aspect: a holder for pointcut signatures. The method bodies are empty on
 * purpose, only the annotation expression matters.
 */
public final class Pointcuts {

	private Pointcuts() {
	}

	/** Every public method of every class annotated with @RestController (our controllers, not the advice class). */
	@Pointcut("within(@org.springframework.web.bind.annotation.RestController *)")
	public void inWebLayer() {
	}

	/**
	 * Any method declared by the TmdbClient interface, on whatever bean implements it. Two beans match
	 * (ResilientTmdbClient and the raw Rest/Mock client it wraps), so one logical call is seen TWICE:
	 * once from the outside (includes retries and back-off) and once per attempt on the inside.
	 */
	@Pointcut("execution(* com.moviepicker.tmdb.TmdbClient.*(..))")
	public void tmdbClientCall() {
	}

	/** The operations somebody would want an audit trail for: launching, restarting, stopping, pausing, resuming. */
	@Pointcut("execution(public * com.moviepicker.service.SyncJobService.start*(..))"
			+ " || execution(public * com.moviepicker.service.SyncJobService.restart(..))"
			+ " || execution(public * com.moviepicker.service.SyncJobService.stop(..))"
			+ " || execution(public * com.moviepicker.scheduler.SchedulerControl.pause())"
			+ " || execution(public * com.moviepicker.scheduler.SchedulerControl.resume())")
	public void jobControl() {
	}
}
