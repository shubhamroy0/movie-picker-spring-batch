package com.moviepicker.repository;

import java.util.List;
import java.util.Optional;

import com.moviepicker.domain.SyncRun;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncRunRepository extends JpaRepository<SyncRun, Long> {

	Optional<SyncRun> findFirstByOrderByIdDesc();

	Optional<SyncRun> findFirstByJobExecutionIdOrderByIdDesc(long jobExecutionId);

	List<SyncRun> findAllByOrderByIdDesc(Pageable pageable);
}
