package com.epam.aidial.core.server.service;

import io.vertx.core.Vertx;

public class VertxScheduledService implements ScheduledService {

    private final Vertx vertx;

    public VertxScheduledService(Vertx vertx) {
        this.vertx = vertx;
    }

    @Override
    public Timer scheduleWithFixedDelay(long initialDelay, long delay, Runnable task) {
        // vertex timer is called from event loop, so sync is done in worker thread to not block event loop
        long timerId = vertx.setPeriodic(initialDelay, delay, event -> vertx.executeBlocking(() -> {
            task.run();
            return null;
        }));
        return () -> vertx.cancelTimer(timerId);
    }
}
