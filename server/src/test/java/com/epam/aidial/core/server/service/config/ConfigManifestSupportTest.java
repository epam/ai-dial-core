package com.epam.aidial.core.server.service.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigManifestSupportTest {

    @Test
    void testParseSoftValidation() {
        assertFalse(ConfigManifestSupport.parseSoftValidation(null));
        assertTrue(ConfigManifestSupport.parseSoftValidation("true"));
        assertTrue(ConfigManifestSupport.parseSoftValidation("TRUE"));
        assertFalse(ConfigManifestSupport.parseSoftValidation("false"));
        assertFalse(ConfigManifestSupport.parseSoftValidation("False"));
    }

    @Test
    void testParseSoftValidationRejectsAnythingElse() {
        assertThrows(IllegalArgumentException.class, () -> ConfigManifestSupport.parseSoftValidation(""));
        assertThrows(IllegalArgumentException.class, () -> ConfigManifestSupport.parseSoftValidation("yes"));
        assertThrows(IllegalArgumentException.class, () -> ConfigManifestSupport.parseSoftValidation("1"));
    }
}
