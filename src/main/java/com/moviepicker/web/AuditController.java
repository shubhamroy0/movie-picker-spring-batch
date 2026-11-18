package com.moviepicker.web;

import java.util.List;

import com.moviepicker.aop.AuditLog;
import com.moviepicker.web.ApiDtos.AuditEntryDto;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Reads the trail that JobAuditAspect writes. Newest entry first. */
@RestController
@RequestMapping("/api/audit")
@Validated
public class AuditController {

	private final AuditLog auditLog;

	public AuditController(AuditLog auditLog) {
		this.auditLog = auditLog;
	}

	@GetMapping
	public List<AuditEntryDto> recent(@RequestParam(defaultValue = "20") @Min(1) @Max(AuditLog.CAPACITY) int limit) {
		return auditLog.recent(limit).stream()
				.map(e -> new AuditEntryDto(e.at(), e.action(), e.argument(), e.outcome(), e.detail()))
				.toList();
	}
}
