package com.hanghae.lemonairstreaming.util;

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

public enum ThreadSchedulers {
	IO(Schedulers.boundedElastic()),
	COMPUTE(Schedulers.parallel());

	private final Scheduler scheduler;

	ThreadSchedulers(Scheduler scheduler) {
		this.scheduler = scheduler;
	}

	public Scheduler scheduler() {
		return scheduler;
	}
}
