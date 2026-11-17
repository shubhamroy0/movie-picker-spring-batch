package com.moviepicker.service;

/** No job execution with this id (HTTP 404). */
public class ExecutionNotFoundException extends RuntimeException {

	public ExecutionNotFoundException(long id) {
		super("No job execution with id " + id);
	}
}
