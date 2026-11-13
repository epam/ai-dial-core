package com.epam.aidial.core.server.service;

import java.util.concurrent.TimeUnit;

/** A deadline that does not expire within a test, for tests of the export that are not about time. */
final class TestDeadlines {

    static final ExportLimits.Deadline NEVER = new ExportLimits.Deadline(System.nanoTime() + TimeUnit.DAYS.toNanos(1));

    private TestDeadlines() {
    }
}
