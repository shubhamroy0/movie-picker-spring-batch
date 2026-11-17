package com.moviepicker.batch;

/** Names shared by the job config, the summary service and tests (a typo in a step name is a silent bug). */
public final class SyncSteps {

	public static final String JOB = "syncMoviesJob";
	public static final String FETCH = "fetchAndUpsertMovies";
	public static final String ENRICH = "enrichDetails";
	public static final String SNAPSHOT = "snapshotTrending";
	public static final String SUMMARY = "summary";

	private SyncSteps() {
	}
}
