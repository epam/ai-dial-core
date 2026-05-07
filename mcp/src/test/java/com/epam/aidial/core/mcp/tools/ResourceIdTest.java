package com.epam.aidial.core.mcp.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceIdTest {

    @Test
    void parsesPilotModel() {
        ResourceId id = ResourceId.parse("models/public/gpt-4");
        assertEquals("models", id.type());
        assertEquals("public", id.bucket());
        assertEquals("gpt-4", id.name());
    }

    @Test
    void parsesSettingsSingleton() {
        ResourceId id = ResourceId.parse("settings/platform/global");
        assertEquals("settings", id.type());
        assertEquals("platform", id.bucket());
        assertEquals("global", id.name());
    }

    @Test
    void rejectsUnsupportedType() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ResourceId.parse("unknown/public/x"));
        assertTrue(ex.getMessage().contains("dial_describe_schema"));
    }

    @Test
    void rejectsMalformedId() {
        assertThrows(IllegalArgumentException.class, () -> ResourceId.parse("tooFew"));
    }

    @Test
    void toCorePathBuildsRestUrl() {
        ResourceId id = ResourceId.parse("models/public/gpt-4");
        assertEquals("/v1/models/public/gpt-4", id.toCorePath("public"));
    }
}
