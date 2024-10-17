package com.epam.aidial.core.service;

import io.vertx.core.Vertx;

public class VertxScheduledService implements ScheduledService {

    private final Vertx vertx;

    public VertxScheduledService(Vertx vertx) {
        this.vertx = vertx;
    }

    @Override
    public ScheduledTimer scheduleWithFixedDelay(long initialDelay, long delay, Runnable task) {
        long timer = vertx.setPeriodic(initialDelay, delay, event -> vertx.executeBlocking(() -> {
            task.run();
            return null;
        }));
        return new VertxScheduledTimer(vertx, timer);
    }


}
