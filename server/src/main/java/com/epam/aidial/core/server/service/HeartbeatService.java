package com.epam.aidial.core.server.service;

import io.vertx.core.Vertx;
import lombok.extern.slf4j.Slf4j;

import java.io.Closeable;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
public class HeartbeatService implements Closeable {
    private final Vertx vertx;
    private final long heartbeatPeriod;
    private final long timer;
    private final ConcurrentMap<Runnable, Long> subscribers = new ConcurrentHashMap<>();

    public HeartbeatService(Vertx vertx, long heartbeatPeriod) {
        this.vertx = vertx;
        this.heartbeatPeriod = heartbeatPeriod;
        this.timer = vertx.setPeriodic(heartbeatPeriod / 2, ignore -> vertx.executeBlocking(this::sendHeartbeats));
    }

    public void subscribe(Runnable subscriber) {
        log.info("Adding a new subscriber to heartbeat service");
        subscribers.put(subscriber, System.currentTimeMillis());
        log.info("{} subscribers", subscribers.size());
    }

    public void unsubscribe(Runnable subscriber) {
        log.info("Removing a subscriber from heartbeat service");
        subscribers.remove(subscriber);
        log.info("{} subscribers", subscribers.size());
    }

    private Void sendHeartbeats() {
        long now = System.currentTimeMillis();
        for (Runnable subscriber : subscribers.keySet()) {
            Long newValue = subscribers.computeIfPresent(subscriber, (key, previousTime) ->
                    now - previousTime < heartbeatPeriod ? previousTime : now);
            if (Objects.equals(newValue, now)) {
                try {
                    log.info("Sending a heartbeat");
                    subscriber.run();
                } catch (Exception e) {
                    log.warn("Can't send a heartbeat", e);
                }
            }
        }

        return null;
    }

    @Override
    public void close() {
        subscribers.clear();
        vertx.cancelTimer(timer);
    }
}
