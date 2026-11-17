package com.moviepicker.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.moviepicker.config.SyncProperties;
import org.springframework.stereotype.Component;

/**
 * Runtime switch + status of the scheduler. Pausing does NOT cancel the @Scheduled task (it keeps ticking every
 * interval); each tick just checks the flag and returns. That is the simplest correct pause: no TaskScheduler
 * handles to manage, takes effect on the next tick, and an already-running sync is NOT interrupted.
 * State is in memory: it resets to "running" when the app restarts (store it in the DB if it must survive restarts).
 */
@Component
public class SchedulerControl {

	public record Status(boolean enabled, boolean paused, Duration interval, Instant lastTickAt, String lastOutcome) {
	}

	private final SyncProperties props;
	private final AtomicBoolean paused = new AtomicBoolean(false);
	private final AtomicReference<Instant> lastTickAt = new AtomicReference<>();
	private final AtomicReference<String> lastOutcome = new AtomicReference<>("no tick yet");

	public SchedulerControl(SyncProperties props) {
		this.props = props;
	}

	public void pause() {
		paused.set(true);
	}

	public void resume() {
		paused.set(false);
	}

	public boolean isPaused() {
		return paused.get();
	}

	void record(Instant at, String outcome) {
		lastTickAt.set(at);
		lastOutcome.set(outcome);
	}

	public Status status() {
		return new Status(props.enabled(), paused.get(), props.interval(), lastTickAt.get(), lastOutcome.get());
	}
}
