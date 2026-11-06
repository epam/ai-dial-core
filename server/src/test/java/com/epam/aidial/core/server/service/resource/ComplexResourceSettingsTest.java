package com.epam.aidial.core.server.service.resource;

import com.epam.aidial.core.storage.resource.ResourceTypes;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComplexResourceSettingsTest {

    @Test
    void testSkillUsesDefaults() {
        ComplexResourceService.Limits limits = new ComplexResourceService.Settings().resolveLimits(ResourceTypes.SKILL);

        assertEquals(100, limits.getMaxFiles());
        assertEquals(16L * 1024 * 1024, limits.getMaxTotalBytes());
        assertEquals(1024 * 1024, limits.getMaxFileSizeBytes());
    }

    @Test
    void testProjectHasLargerBuiltInLimits() {
        ComplexResourceService.Limits limits = new ComplexResourceService.Settings().resolveLimits(ResourceTypes.PROJECT);

        assertEquals(1000, limits.getMaxFiles());
        assertEquals(64L * 1024 * 1024, limits.getMaxTotalBytes());
        assertEquals(5L * 1024 * 1024, limits.getMaxFileSizeBytes());
    }

    @Test
    void testPartialOverrideFallsBackToDefaultsForUnsetFields() {
        ComplexResourceService.Settings settings = new ComplexResourceService.Settings();
        settings.setMaxFiles(7);
        settings.setTypes(Map.of("skills", new ComplexResourceService.Limits(null, 500L, null)));

        ComplexResourceService.Limits limits = settings.resolveLimits(ResourceTypes.SKILL);

        assertEquals(7, limits.getMaxFiles());
        assertEquals(500L, limits.getMaxTotalBytes());
        assertEquals(1024 * 1024, limits.getMaxFileSizeBytes());
    }

    @Test
    void testSuppliedTypesReplaceBuiltInProjectOverride() {
        ComplexResourceService.Settings settings = new ComplexResourceService.Settings();
        settings.setTypes(Map.of());

        assertEquals(100, settings.resolveLimits(ResourceTypes.PROJECT).getMaxFiles());
    }

    @Test
    void testOnlySkillAndProjectAreComplex() {
        assertTrue(ResourceTypes.SKILL.isComplex());
        assertTrue(ResourceTypes.PROJECT.isComplex());
        assertFalse(ResourceTypes.PROMPT.isComplex());
        assertFalse(ResourceTypes.FILE.isComplex());
    }
}
