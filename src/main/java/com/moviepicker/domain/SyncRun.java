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
 * Business summary of one job execution: how many movies were INSERTED vs UPDATED, how many filtered/skipped.
 *
 * TRADE-OFF - own table vs deriving everything from the BATCH_* tables:
 *  + BATCH_STEP_EXECUTION already has read/write/filter/skip counts, but it cannot tell "inserted" from "updated"
 *    (both are just "writes"), and it is Spring Batch's internal schema (can change between versions, is purged by
 *    some teams). A small table with OUR meaning is stable and cheap to query for the API and health indicator.
 *  - The cost is duplicated information that must be kept consistent (written by the Phase-4 summary tasklet).
 * {@code jobExecutionId} links back to BATCH_JOB_EXECUTION for the technical details.
 */
@Entity
@Table(name = "sync_run")
public class SyncRun {

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "sync_run_seq")
	@SequenceGenerator(name = "sync_run_seq", sequenceName = "sync_run_seq", allocationSize = 1)
	private Long id;

	@Column(name = "job_execution_id", nullable = false)
	private long jobExecutionId;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "finished_at")
	private Instant finishedAt;

	@Column(nullable = false)
	private String status;

	private int inserted;
	private int updated;
	@Column(name = "read_count")
	private int readCount;
	@Column(name = "filtered_count")
	private int filteredCount;
	@Column(name = "skipped_count")
	private int skippedCount;

	@Column(length = 2000)
	private String message;

	protected SyncRun() {
	}

	public SyncRun(long jobExecutionId, String status) {
		this.jobExecutionId = jobExecutionId;
		this.status = status;
	}

	public Long getId() { return id; }
	public long getJobExecutionId() { return jobExecutionId; }
	public Instant getStartedAt() { return startedAt; }
	public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
	public Instant getFinishedAt() { return finishedAt; }
	public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
	public String getStatus() { return status; }
	public void setStatus(String status) { this.status = status; }
	public int getInserted() { return inserted; }
	public void setInserted(int inserted) { this.inserted = inserted; }
	public int getUpdated() { return updated; }
	public void setUpdated(int updated) { this.updated = updated; }
	public int getReadCount() { return readCount; }
	public void setReadCount(int readCount) { this.readCount = readCount; }
	public int getFilteredCount() { return filteredCount; }
	public void setFilteredCount(int filteredCount) { this.filteredCount = filteredCount; }
	public int getSkippedCount() { return skippedCount; }
	public void setSkippedCount(int skippedCount) { this.skippedCount = skippedCount; }
	public String getMessage() { return message; }
	public void setMessage(String message) { this.message = message; }
}
