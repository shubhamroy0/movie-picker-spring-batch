package com.moviepicker.service;

/** A sync is already running (or starting); a second concurrent run is refused. Mapped to HTTP 409 by the API. */
public class SyncAlreadyRunningException extends RuntimeException {

	public SyncAlreadyRunningException(long runningExecutionId) {
		super("A sync is already running (execution " + runningExecutionId + ")");
	}

	public SyncAlreadyRunningException(String message) {
		super(message);
	}
}
