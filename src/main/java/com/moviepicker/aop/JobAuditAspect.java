package com.moviepicker.aop;

import java.util.Arrays;
import java.util.stream.Collectors;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Audit trail for "who touched the job": every launch, restart, stop, pause and resume is recorded with its outcome.
 * That is the textbook cross-cutting concern: the business code (SyncJobService, SchedulerControl) contains NO audit
 * code and does not even know it is audited, yet one aspect covers every caller (REST API, scheduler, startup runner).
 *
 * THE SELF-INVOCATION RULE (the most asked Spring AOP question): Spring AOP works with a PROXY wrapped around the bean.
 * A call from ANOTHER bean goes through the proxy and is intercepted. A call from a method to another method of the SAME
 * object ({@code this.startSyncAsync(..)} inside SyncJobService.startSync) goes straight to the target and is NOT
 * intercepted. Here that is useful: the pointcut matches both startSync and startSyncAsync, but a scheduled
 * {@code startSync} calls {@code startSyncAsync} internally, so it produces ONE audit entry, not two. If you ever need the
 * inner call advised too: inject the bean into itself, move the method to another bean (best), or use AspectJ weaving.
 *
 * The "outcome" is SUCCESS when the method returned and FAILURE when it threw (the exception is rethrown untouched, so
 * the REST layer still answers 409/404 exactly as before). A launch that returns a job execution which later FAILS is
 * still a SUCCESSFUL launch: the detail carries the final batch status for the blocking startSync.
 *
 * @Order(30): inside the timing aspect (10) and the request log (20), outside the transaction interceptor.
 */
@Aspect
@Component
@Order(30)
public class JobAuditAspect {

	private static final Logger audit = LoggerFactory.getLogger("AUDIT");

	private final AuditLog auditLog;

	public JobAuditAspect(AuditLog auditLog) {
		this.auditLog = auditLog;
	}

	@Around("com.moviepicker.aop.Pointcuts.jobControl()")
	public Object audit(ProceedingJoinPoint joinPoint) throws Throwable {
		String action = ((MethodSignature) joinPoint.getSignature()).getMethod().getName();
		String argument = Arrays.stream(joinPoint.getArgs()).map(String::valueOf).collect(Collectors.joining(","));
		try {
			Object result = joinPoint.proceed();
			String detail = result instanceof JobExecution execution
					? "executionId=" + execution.getId() + " status=" + execution.getStatus()
					: "";
			auditLog.add(action, argument, "SUCCESS", detail);
			audit.info("{}({}) SUCCESS {}", action, argument, detail);
			return result;
		}
		catch (Throwable failure) {
			String detail = failure.getClass().getSimpleName() + ": " + failure.getMessage();
			auditLog.add(action, argument, "FAILURE", detail);
			audit.warn("{}({}) FAILURE {}", action, argument, detail);
			throw failure;
		}
	}
}
