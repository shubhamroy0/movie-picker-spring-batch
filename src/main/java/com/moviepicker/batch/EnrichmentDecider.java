package com.moviepicker.batch;

import com.moviepicker.repository.MovieRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.flow.FlowExecutionStatus;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.stereotype.Component;

/**
 * A DECIDER is a branching point in the job flow that is not a step: it inspects the execution and returns a status
 * string, and the job definition maps each status to the next step. Here: skip the (HTTP-heavy) enrichment when there is
 * nothing to enrich.
 *
 * The inserted count arrives via the JOB ExecutionContext: the fetch step's ExecutionContextPromotionListener copied
 * "writer.inserted" from the STEP context (private to that step) to the JOB context (visible to later steps/deciders).
 * We also enrich when earlier runs left movies un-enriched (e.g. a previous enrichment failed), otherwise those movies
 * would stay incomplete until the next run happens to insert something new.
 */
@Component
public class EnrichmentDecider implements JobExecutionDecider {

	static final String ENRICH = "ENRICH";
	static final String SKIP_ENRICH = "SKIP_ENRICH";

	private static final Logger log = LoggerFactory.getLogger(EnrichmentDecider.class);

	private final MovieRepository movies;

	public EnrichmentDecider(MovieRepository movies) {
		this.movies = movies;
	}

	@Override
	public FlowExecutionStatus decide(JobExecution jobExecution, StepExecution stepExecution) {
		int inserted = jobExecution.getExecutionContext().getInt(MovieUpsertWriter.KEY_INSERTED, 0);
		long pending = movies.countByDetailsFetchedAtIsNull();
		boolean enrich = inserted > 0 || pending > 0;
		log.info("Enrichment decision: inserted={} pendingDetails={} -> {}", inserted, pending,
				enrich ? "enrich" : "SKIP enrichment");
		return new FlowExecutionStatus(enrich ? ENRICH : SKIP_ENRICH);
	}
}
