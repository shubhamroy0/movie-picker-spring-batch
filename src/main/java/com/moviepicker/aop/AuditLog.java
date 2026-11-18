package com.moviepicker.aop;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Small in-memory audit trail written by {@link JobAuditAspect}. Bounded (oldest entries drop off) so it can never grow
 * without limit, and it is lost on restart: a real system would write to a table or an audit topic. It lives in memory
 * here so the focus stays on the aspect that fills it.
 */
@Component
public class AuditLog {

	public static final int CAPACITY = 200;

	public record Entry(Instant at, String action, String argument, String outcome, String detail) {
	}

	private final Clock clock;
	private final Deque<Entry> entries = new ArrayDeque<>();

	public AuditLog(Clock clock) {
		this.clock = clock;
	}

	public synchronized void add(String action, String argument, String outcome, String detail) {
		if (entries.size() == CAPACITY) {
			entries.removeFirst();
		}
		entries.addLast(new Entry(clock.instant(), action, argument, outcome, detail));
	}

	/** Newest first. */
	public synchronized List<Entry> recent(int limit) {
		List<Entry> newestFirst = new ArrayList<>(entries);
		java.util.Collections.reverse(newestFirst);
		return newestFirst.size() <= limit ? newestFirst : newestFirst.subList(0, limit);
	}

	public synchronized void clear() {
		entries.clear();
	}
}
