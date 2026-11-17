package com.moviepicker.service;

import java.time.Clock;

import com.moviepicker.domain.RejectedRecord;
import com.moviepicker.repository.RejectedRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the "dead letter" rows. REQUIRES_NEW = its OWN transaction, committed immediately, independent of the chunk
 * transaction. Why it matters: if the chunk later rolls back (a retry, a failure), the evidence that we rejected an
 * item must not roll back with it - otherwise the rejected_record table would silently lose rows.
 */
@Service
public class RejectedRecordService {

	private static final int MAX_PAYLOAD = 7900;

	private final RejectedRecordRepository repository;
	private final Clock clock;

	public RejectedRecordService(RejectedRecordRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void record(String payload, Throwable reason, String stage, Long jobExecutionId) {
		String p = payload == null ? "(no payload)" : payload;
		if (p.length() > MAX_PAYLOAD) {
			p = p.substring(0, MAX_PAYLOAD) + "...";
		}
		String why = reason.getClass().getSimpleName() + ": " + reason.getMessage();
		repository.save(new RejectedRecord(p, why.length() > 1900 ? why.substring(0, 1900) : why, stage,
				jobExecutionId, clock.instant()));
	}
}
