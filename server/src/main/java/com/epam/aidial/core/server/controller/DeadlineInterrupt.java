package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.service.ExportLimits;
import io.vertx.core.Vertx;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Interrupts a thread when the deadline passes, so that a blocked read ends; an operation that ignores the interrupt fails at the next
 * deadline check. After {@link #disarm} the thread is never interrupted again, which matters when a thread is reused.
 */
final class DeadlineInterrupt {

    private static final int ARMED = 0;
    private static final int INTERRUPTING = 1;
    private static final int DONE = 2;

    private final Vertx vertx;
    private final AtomicInteger state = new AtomicInteger(ARMED);
    private final long timer;

    DeadlineInterrupt(Vertx vertx, Thread worker, ExportLimits.Deadline deadline) {
        this.vertx = vertx;
        long millis = deadline.computeRemainingMillis();
        timer = millis < 0 ? -1 : vertx.setTimer(millis, id -> {
            if (state.compareAndSet(ARMED, INTERRUPTING)) {
                worker.interrupt();
                state.set(DONE);
            }
        });
    }

    /** Cancels the timer, waits for an interrupt that is being delivered, and clears the flag it set. */
    void disarm() {
        if (timer >= 0) {
            vertx.cancelTimer(timer);
        }
        if (!state.compareAndSet(ARMED, DONE)) {
            while (state.get() == INTERRUPTING) {
                Thread.onSpinWait();
            }
            Thread.interrupted();
        }
    }
}
