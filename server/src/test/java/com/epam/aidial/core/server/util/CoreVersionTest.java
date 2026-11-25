package com.epam.aidial.core.server.util;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreVersionTest {

    @Test
    void testLaterReleaseComparesGreater() {
        assertTrue(version("0.51.0").compareTo(version("0.50.0")) > 0);
        assertTrue(version("1.0.0").compareTo(version("0.99.99")) > 0);
        assertTrue(version("0.50.10").compareTo(version("0.50.9")) > 0, "parts compare as numbers, not text");
        assertTrue(version("0.50.1").compareTo(version("0.50")) > 0);
    }

    @Test
    void testEarlierReleaseComparesLess() {
        assertTrue(version("0.49.2").compareTo(version("0.50.0")) < 0);
        assertTrue(version("0.50").compareTo(version("0.50.1")) < 0);
    }

    @Test
    void testSuffixAndMissingPartsDoNotMatter() {
        for (String same : List.of("0.50.0", "0.50", "0.50.0.0", "0.50.0-SNAPSHOT", "0.50.0-dev.29")) {
            assertEquals(0, version(same).compareTo(version("0.50.0")), same);
        }
    }

    @Test
    void testDevelopmentBuilds() {
        for (String development : Arrays.asList("0.0.0", "0", "undefined", "", null, "v0.50.0", "99999999999.0")) {
            assertTrue(version(development).isDevelopment(), String.valueOf(development));
        }
        for (String release : List.of("0.0.1", "0.50.0", "1")) {
            assertFalse(version(release).isDevelopment(), release);
        }
    }

    @Test
    void testDevelopmentBuildIsAboveEveryRelease() {
        assertTrue(version("0.0.0").compareTo(version("999.0.0")) > 0);
        assertTrue(version("undefined").compareTo(version("0.50.0")) > 0);
        assertTrue(version("0.50.0").compareTo(version("0.0.0")) < 0);
    }

    @Test
    void testDevelopmentBuildsAreEqual() {
        assertEquals(0, version("0.0.0").compareTo(version("undefined")));
        assertEquals(version("0.0.0"), version("undefined"));
        assertEquals(version("0.0.0").hashCode(), version("undefined").hashCode());
    }

    @Test
    void testEqualsAndHashCodeFollowTheComparison() {
        assertEquals(version("0.50"), version("0.50.0-SNAPSHOT"));
        assertEquals(version("0.50").hashCode(), version("0.50.0-SNAPSHOT").hashCode());
        assertNotEquals(version("0.50.0"), version("0.50.1"));
        assertNotEquals(version("0.50.0"), version("0.0.0"));
    }

    @Test
    void testToStringKeepsTheRawVersion() {
        assertEquals("0.50.0-SNAPSHOT", version("0.50.0-SNAPSHOT").toString());
        assertEquals("null", version(null).toString());
    }

    private static CoreVersion version(String raw) {
        return CoreVersion.parse(raw);
    }
}
