package com.moviepicker.scheduler;

import com.moviepicker.service.SyncJobService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Dev convenience: runs one sync right after startup when moviepicker.sync.run-on-startup=true.
 * (Not the same as Boot's spring.batch.job.enabled, which auto-runs EVERY Job bean with no parameter control;
 * we keep that off and launch only through SyncJobService.)
 */
@Component
@ConditionalOnProperty(prefix = "moviepicker.sync", name = "run-on-startup", havingValue = "true")
class StartupSyncRunner implements ApplicationRunner {

	private final SyncJobService syncJobService;

	StartupSyncRunner(SyncJobService syncJobService) {
		this.syncJobService = syncJobService;
	}

	@Override
	public void run(ApplicationArguments args) {
		syncJobService.startSync("startup");
	}
}
