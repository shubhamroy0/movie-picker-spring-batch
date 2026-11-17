package com.moviepicker.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/**
 * A "dead letter" row: an item the job SKIPPED because it was bad (invalid data, unparseable JSON...), kept with the
 * raw payload and the reason so a human can inspect it later instead of the data silently disappearing.
 * Written by the SkipListener in Phase 3.
 */
@Entity
@Table(name = "rejected_record")
public class RejectedRecord {

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "rejected_seq")
	@SequenceGenerator(name = "rejected_seq", sequenceName = "rejected_seq", allocationSize = 1)
	private Long id;

	/** Raw item (JSON/toString) as it looked when it was rejected. */
	@Column(length = 8000)
	private String payload;

	@Column(nullable = false, length = 2000)
	private String reason;

	/** Where it happened: "READ", "PROCESS" or "WRITE" (the skip phase). */
	@Column(length = 20)
	private String stage;

	@Column(name = "job_execution_id")
	private Long jobExecutionId;

	@Column(name = "rejected_at", nullable = false)
	private Instant rejectedAt;

	protected RejectedRecord() {
	}

	public RejectedRecord(String payload, String reason, String stage, Long jobExecutionId, Instant rejectedAt) {
		this.payload = payload;
		this.reason = reason;
		this.stage = stage;
		this.jobExecutionId = jobExecutionId;
		this.rejectedAt = rejectedAt;
	}

	public Long getId() { return id; }
	public String getPayload() { return payload; }
	public String getReason() { return reason; }
	public String getStage() { return stage; }
	public Long getJobExecutionId() { return jobExecutionId; }
	public Instant getRejectedAt() { return rejectedAt; }
}
