package com.moviepicker.repository;

import com.moviepicker.domain.RejectedRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RejectedRecordRepository extends JpaRepository<RejectedRecord, Long> {
}
