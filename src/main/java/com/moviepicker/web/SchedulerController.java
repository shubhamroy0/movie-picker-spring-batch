package com.moviepicker.web;

import com.moviepicker.scheduler.SchedulerControl;
import com.moviepicker.web.ApiDtos.MessageDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/scheduler")
public class SchedulerController {

	private final SchedulerControl control;

	public SchedulerController(SchedulerControl control) {
		this.control = control;
	}

	@GetMapping
	public SchedulerControl.Status status() {
		return control.status();
	}

	@PostMapping("/pause")
	public MessageDto pause() {
		control.pause();
		return new MessageDto("Scheduler paused: ticks are skipped; a sync already running is not interrupted");
	}

	@PostMapping("/resume")
	public MessageDto resume() {
		control.resume();
		return new MessageDto("Scheduler resumed");
	}
}
