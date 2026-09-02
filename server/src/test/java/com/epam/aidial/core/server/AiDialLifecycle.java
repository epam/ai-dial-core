package com.epam.aidial.core.server;

/**
 * {@code AiDial.start()} and {@code stop()} are package-private, and the layout comparison harness lives in a
 * sub-package because it boots the stack itself rather than through {@code ResourceBaseTest}. A test-only
 * bridge keeps that reachable without widening the production API for it.
 */
public final class AiDialLifecycle {

    private AiDialLifecycle() {
    }

    public static void start(AiDial dial) throws Exception {
        dial.start();
    }

    public static void stop(AiDial dial) throws Exception {
        dial.stop();
    }
}
