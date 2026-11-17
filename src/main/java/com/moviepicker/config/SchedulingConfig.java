package com.moviepicker.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on @Scheduled processing (a ScheduledAnnotationBeanPostProcessor + a single-threaded TaskScheduler by default).
 * Whether our SyncScheduler bean exists at all is decided by moviepicker.sync.enabled.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfig {
}
