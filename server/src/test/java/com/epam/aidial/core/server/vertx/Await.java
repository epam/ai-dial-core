package com.epam.aidial.core.server.vertx;

import lombok.experimental.UtilityClass;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

@UtilityClass
class Await {

    /**
     * Polls until the condition holds, failing with the message after 5 s.
     */
    static void until(BooleanSupplier condition, Supplier<String> message) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, message);
            Thread.sleep(10);
        }
    }
}
