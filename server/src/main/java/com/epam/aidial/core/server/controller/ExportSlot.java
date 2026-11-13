package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.service.LockService;
import io.vertx.core.Future;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The holder of the bucket locks of one export; it is released once, whichever path that ends the export reaches it first.
 * {@link #newReleasedSlot()} holds nothing.
 */
@Slf4j
final class ExportSlot {

    private final LockService.Lock lock;
    private final AtomicBoolean isReleased;

    ExportSlot(LockService.Lock lock) {
        this(lock, false);
    }

    private ExportSlot(LockService.Lock lock, boolean isReleased) {
        this.lock = lock;
        this.isReleased = new AtomicBoolean(isReleased);
    }

    static ExportSlot newReleasedSlot() {
        return new ExportSlot(() -> {
        }, true);
    }

    boolean isReleased() {
        return isReleased.get();
    }

    /** Only for a task thread that is not interrupted: the release is a blocking call to Redis. */
    void release() {
        if (isReleased.compareAndSet(false, true)) {
            try {
                lock.close();
            } catch (RuntimeException e) {
                // the lease of the lock ends it anyway
                log.warn("The lock of the export could not be released: {}", e.getClass().getSimpleName());
            }
        }
    }

    /**
     * Releases on a task thread, then runs {@code next}, so that a client that has its answer can start the next export at once. A callback
     * runs on the event loop, which must not wait for Redis. If the executor refuses the release, the lease ends the lock and {@code next} still runs.
     */
    void releaseThen(AsyncTaskExecutor taskExecutor, Runnable next) {
        if (isReleased()) {
            next.run();
            return;
        }
        Future<?> releasing;
        try {
            releasing = taskExecutor.submit(() -> {
                release();
                return null;
            });
        } catch (RuntimeException e) {
            log.warn("The lock of the export could not be released: {}", e.getClass().getSimpleName());
            next.run();
            return;
        }
        releasing.onComplete(ignored -> next.run());
    }
}
