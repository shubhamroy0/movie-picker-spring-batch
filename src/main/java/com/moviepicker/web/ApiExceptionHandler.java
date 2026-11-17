package com.moviepicker.web;

import java.net.URI;

import com.moviepicker.service.ExecutionNotFoundException;
import com.moviepicker.service.ExecutionStateException;
import com.moviepicker.service.InvalidRequestException;
import com.moviepicker.service.MovieNotFoundException;
import com.moviepicker.service.SyncAlreadyRunningException;
import com.moviepicker.service.SyncLaunchException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * One place that turns exceptions into RFC 9457 ProblemDetail responses (application/problem+json):
 * {type, title, status, detail, instance}. Extending ResponseEntityExceptionHandler already maps Spring MVC's own
 * exceptions (400 missing parameter, 405, 415...) to ProblemDetail; we add our domain exceptions on top.
 * Rule of thumb: 404 = it does not exist, 400 = your input is wrong, 409 = valid request, wrong state right now,
 * 500 = our bug (details logged, never leaked to the client).
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler({ MovieNotFoundException.class, ExecutionNotFoundException.class })
	ProblemDetail notFound(RuntimeException e) {
		return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage());
	}

	@ExceptionHandler({ SyncAlreadyRunningException.class, ExecutionStateException.class })
	ProblemDetail conflict(RuntimeException e) {
		return problem(HttpStatus.CONFLICT, "Conflict with the current state", e.getMessage());
	}

	@ExceptionHandler(InvalidRequestException.class)
	ProblemDetail invalid(InvalidRequestException e) {
		return problem(HttpStatus.BAD_REQUEST, "Invalid request", e.getMessage());
	}

	/** Bean Validation on @RequestParam of a @Validated controller (older style). */
	@ExceptionHandler(ConstraintViolationException.class)
	ProblemDetail constraint(ConstraintViolationException e) {
		return problem(HttpStatus.BAD_REQUEST, "Validation failed", e.getMessage());
	}

	/** Spring 6.1+/7 built-in method validation (constraints on controller parameters). */
	@Override
	protected org.springframework.http.ResponseEntity<Object> handleHandlerMethodValidationException(
			HandlerMethodValidationException ex, org.springframework.http.HttpHeaders headers,
			org.springframework.http.HttpStatusCode status, org.springframework.web.context.request.WebRequest request) {
		StringBuilder sb = new StringBuilder();
		ex.getParameterValidationResults().forEach(r -> r.getResolvableErrors()
				.forEach(err -> sb.append(r.getMethodParameter().getParameterName()).append(": ")
						.append(err.getDefaultMessage()).append("; ")));
		ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "Validation failed", sb.toString().trim());
		return org.springframework.http.ResponseEntity.badRequest().body(pd);
	}

	@ExceptionHandler(SyncLaunchException.class)
	ProblemDetail launchFailed(SyncLaunchException e) {
		log.warn("Job launch failed", e);
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Job could not be launched", e.getMessage());
	}

	@ExceptionHandler(Exception.class)
	ProblemDetail unexpected(Exception e) {
		log.error("Unhandled exception", e);
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error", "Something went wrong; see the server log");
	}

	private static ProblemDetail problem(HttpStatus status, String title, String detail) {
		ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
		pd.setTitle(title);
		pd.setType(URI.create("about:blank"));
		return pd;
	}
}
