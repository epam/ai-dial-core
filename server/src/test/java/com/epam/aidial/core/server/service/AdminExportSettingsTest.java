package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.service.LockService;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class AdminExportSettingsTest {

    @Test
    void testDefaultsAreValidAndBecomeTheLimitsOfTheExport() {
        AdminExportSettings settings = new AdminExportSettings();

        assertDoesNotThrow(settings::validate);
        ExportLimits limits = ExportLimits.of(settings);
        assertEquals(new ExportLimits(100, 500, 10, 10_000, 4L << 20, Duration.ofMillis(180_000)), limits);
    }

    @Test
    void testLimitsFollowTheSettings() throws Exception {
        AdminExportSettings settings = ProxyUtil.MAPPER.readValue(
                "{\"maxRoots\":2,\"maxNodes\":3,\"maxDepth\":4,\"maxEdges\":5,\"maxResourceMiB\":6,\"deadline\":7000}",
                AdminExportSettings.class);

        assertEquals(new ExportLimits(2, 3, 4, 5, 6L << 20, Duration.ofMillis(7000)), ExportLimits.of(settings));
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
    void testEveryBoundMustBePositive() {
        for (int i = 0; i < 7; i++) {
            AdminExportSettings settings = new AdminExportSettings();
            switch (i) {
                case 0 -> settings.setStallTimeout(0);
                case 1 -> settings.setDeadline(0);
                case 2 -> settings.setMaxRoots(0);
                case 3 -> settings.setMaxNodes(0);
                case 4 -> settings.setMaxDepth(0);
                case 5 -> settings.setMaxEdges(0);
                default -> settings.setMaxResourceMiB(0);
            }

            assertThrows(IllegalArgumentException.class, settings::validate, "case " + i);
        }
    }
}
