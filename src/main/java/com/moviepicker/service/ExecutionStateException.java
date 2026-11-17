package com.moviepicker.service;

/** The requested action is impossible in the execution's current state, e.g. restarting a COMPLETED one (HTTP 409). */
public class ExecutionStateException extends RuntimeException {

	public ExecutionStateException(String message) {
		super(message);
	}
}
