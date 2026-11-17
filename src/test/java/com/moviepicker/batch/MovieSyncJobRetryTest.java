package com.moviepicker.batch;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import com.moviepicker.repository.MovieRepository;
import com.moviepicker.service.SyncJobService;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The step-level RETRY: the writer's FIRST repository query throws a transient DB error (a lock timeout). The step
 * must retry and the job must still end COMPLETED with every movie present exactly once.
 * (Mockito cannot call real methods on a Spring Data interface proxy, so the fault is injected by wrapping the
 * repository bean in a tiny delegating proxy.)
 */
@SpringBootTest
@ActiveProfiles({"mock", "test"})
@TestPropertySource(properties = "moviepicker.mock.rotate-data=false")
@Import(MovieSyncJobRetryTest.FlakyRepositoryConfig.class)
class MovieSyncJobRetryTest {

	static final AtomicInteger LOOKUPS = new AtomicInteger();

	@TestConfiguration
	static class FlakyRepositoryConfig {
		@Bean
		static BeanPostProcessor flakyMovieRepository() {
			return new BeanPostProcessor() {
				@Override
				public Object postProcessAfterInitialization(Object bean, String beanName) {
					if (!(bean instanceof MovieRepository real)) {
						return bean;
					}
					return Proxy.newProxyInstance(MovieRepository.class.getClassLoader(),
							new Class<?>[] {MovieRepository.class}, (proxy, method, args) -> {
								if (method.getName().equals("findAllByTmdbIdIn") && LOOKUPS.incrementAndGet() == 1) {
									throw new CannotAcquireLockException("simulated lock timeout");
								}
								try {
									return method.invoke(real, args);
								}
								catch (InvocationTargetException e) {
									throw e.getCause();
								}
							});
				}
			};
		}
	}

	@Autowired SyncJobService syncJobService;
	@Autowired MovieRepository movies;

	@Test
	void transientWriteFailureIsRetriedAndTheChunkIsReExecuted() {
		JobExecution execution = syncJobService.startSync("test");

		assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		StepExecution step = StepsOf.fetch(execution);
		// OBSERVED Batch 6 behaviour: the failure happened BEFORE any SQL ran and did not mark the transaction
		// rollback-only, so the ChunkOrientedStep simply re-invoked write() inside the same chunk transaction
		// (rollbackCount stays 0). A failure that poisons the transaction would roll the chunk back first.
		assertThat(step.getSkipCount()).isZero();                                    // retried, not skipped
		assertThat(movies.count()).isEqualTo(45);                                    // nothing lost or doubled
		assertThat(LOOKUPS.get()).isGreaterThanOrEqualTo(9);                         // 8 chunks + at least 1 re-attempt
	}
}
