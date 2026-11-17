package com.moviepicker.service;

/** The request is syntactically fine but semantically unacceptable (unknown list, non-sortable field...): HTTP 400. */
public class InvalidRequestException extends RuntimeException {

	public InvalidRequestException(String message) {
		super(message);
	}
}
