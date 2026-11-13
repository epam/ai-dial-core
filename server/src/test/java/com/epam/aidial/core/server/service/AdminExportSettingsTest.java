package com.epam.aidial.core.server.service;

import com.epam.aidial.core.storage.service.LockService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class AdminExportSettingsTest {

    @Test
    void testDefaultsAreValid() {
        assertDoesNotThrow(new AdminExportSettings()::validate);
    }

    @Test
    void testStallTimeoutAndDeadlineMustFitInTheLeaseOfTheBucketLocks() {
        long lease = LockService.LOCK_PERIOD.toMillis();
        AdminExportSettings stall = new AdminExportSettings();
        stall.setStallTimeout(lease);
        AdminExportSettings deadline = new AdminExportSettings();
        deadline.setDeadline(lease - deadline.getStallTimeout());
        AdminExportSettings fits = new AdminExportSettings();
        fits.setDeadline(lease - fits.getStallTimeout() - 1);

        assertThrows(IllegalArgumentException.class, stall::validate);
        assertThrows(IllegalArgumentException.class, deadline::validate);
        assertDoesNotThrow(fits::validate);
    }

    @Test
    void testStallTimeoutAndDeadlineMustBePositive() {
        AdminExportSettings stall = new AdminExportSettings();
        stall.setStallTimeout(0);
        AdminExportSettings deadline = new AdminExportSettings();
        deadline.setDeadline(0);

        assertThrows(IllegalArgumentException.class, stall::validate);
        assertThrows(IllegalArgumentException.class, deadline::validate);
    }
}
