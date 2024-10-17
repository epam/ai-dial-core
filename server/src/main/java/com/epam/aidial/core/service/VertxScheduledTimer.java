package com.epam.aidial.core.service;

import io.vertx.core.Vertx;

public class VertxScheduledTimer implements ScheduledTimer {

    private final Vertx vertx;

    private final long timerId;

    public VertxScheduledTimer(Vertx vertx, long timerId) {
        this.vertx = vertx;
        this.timerId = timerId;
    }

    @Override
    public void cancel() {
        vertx.cancelTimer(timerId);
    }
}
