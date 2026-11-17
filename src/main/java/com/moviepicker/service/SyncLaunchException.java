package com.moviepicker.service;

/** The sync job could not be launched (already running, bad parameters, instance already completed...). */
public class SyncLaunchException extends RuntimeException {

	public SyncLaunchException(String message, Throwable cause) {
		super(message, cause);
	}
}
