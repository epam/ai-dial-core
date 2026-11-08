package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.service.LockService;
import io.vertx.core.Future;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The slot of one export; it is released once, whichever of the paths that end the export reaches it first.
 * An export that has no lock service holds {@link #none()}, which is released from the start.
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

    /**
     * @return a slot that holds nothing
     */
    static ExportSlot none() {
        return new ExportSlot(() -> {
        }, true);
    }

    /**
     * @param lockService the lock service, or {@code null} when there is none, which gives {@link #none()}
     * @throws HttpException 409 if another export holds the slot {@code name}
     */
    static ExportSlot acquire(LockService lockService, String name) {
        if (lockService == null) {
            return none();
        }
        LockService.Lock lock = lockService.tryLock(name);
        if (lock == null) {
            throw new HttpException(HttpStatus.CONFLICT, "Another export of Applications is running, retry later");
        }
        return new ExportSlot(lock);
    }

    boolean isReleased() {
        return isReleased.get();
    }

    /**
     * Only for a task thread that is not interrupted: the release is a blocking call to Redis.
     */
    void release() {
        if (isReleased.compareAndSet(false, true)) {
            try {
                lock.close();
            } catch (RuntimeException e) {
                // the lease of the lock ends it anyway
                log.warn("The slot of the export could not be released: {}", e.getClass().getSimpleName());
            }
        }
    }

    /**
     * Releases the slot on a task thread, and runs {@code next} when that is done, so that a client that has its answer can
     * start the next export at once. A callback runs on the event loop, which must not wait for Redis. If the executor refuses the
     * release, the lease ends the slot by itself and {@code next} still runs.
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
            log.warn("The slot of the export could not be released: {}", e.getClass().getSimpleName());
            next.run();
            return;
        }
        releasing.onComplete(ignored -> next.run());
    }
}
