package com.moviepicker.scheduler;

import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.moviepicker.domain.SyncRun;
import com.moviepicker.repository.SyncRunRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The REAL scheduler on a short interval. Proves: runs happen by themselves, fixedDelay never overlaps runs, and the
 * pause/resume switch really stops/starts launching. @DirtiesContext closes this context afterwards: a cached context
 * would keep scheduling syncs in the background while other test classes run.
 */
@SpringBootTest
@ActiveProfiles({"mock", "test"})
@TestPropertySource(properties = {
		"moviepicker.sync.enabled=true",
		"moviepicker.sync.interval=PT0.3S",
		"moviepicker.sync.initial-delay=PT0.1S",
		"moviepicker.mock.rotate-data=false"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SyncSchedulingIntegrationTest {

	@Autowired SyncRunRepository runs;
	@Autowired SchedulerControl control;

	private static void await(String what, BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("Timed out waiting for: " + what);
			}
			Thread.sleep(100);
		}
	}

	private long completed() {
		return runs.findAll().stream().filter(r -> "COMPLETED".equals(r.getStatus())).count();
	}

	@Test
	void runsOnItsOwnNeverOverlapsAndCanBePausedAndResumed() throws InterruptedException {
		await("3 completed scheduled runs", () -> completed() >= 3);

		// fixedDelay: every run STARTS only after the previous one FINISHED
		List<SyncRun> ordered = runs.findAll().stream().filter(r -> r.getFinishedAt() != null)
				.sorted((a, b) -> a.getStartedAt().compareTo(b.getStartedAt())).toList();
		for (int i = 1; i < ordered.size(); i++) {
			assertThat(ordered.get(i).getStartedAt()).isAfterOrEqualTo(ordered.get(i - 1).getFinishedAt());
		}

		control.pause();
		Thread.sleep(1500);                                   // let a possibly in-flight run finish
		long frozen = runs.count();
		Thread.sleep(1500);                                   // 5 intervals pass...
		assertThat(runs.count()).isEqualTo(frozen);           // ...and nothing was launched

		control.resume();
		await("a new run after resume", () -> runs.count() > frozen);
	}
}
