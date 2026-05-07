package com.epam.aidial.core.mcp.tools;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the spec 09 §6.4 SUMMARY_FIELDS table. Adding a field to a type — or adding a new type —
 * is a deliberate spec change; this test catches drift between {@link ListResourcesTool} and the
 * locked projection table.
 */
class SummaryFieldsTest {

    @Test
    void modelsProjectsDisplayMetadata() {
        assertEquals(List.of("displayName", "displayVersion", "status", "description"),
                ListResourcesTool.summaryFields("models"));
    }

    @Test
    void rolesAndKeysProjectAdminFields() {
        assertEquals(List.of("status", "description"), ListResourcesTool.summaryFields("roles"));
        assertEquals(List.of("role", "status", "description"), ListResourcesTool.summaryFields("keys"));
    }

    @Test
    void routesProjectsRoutingFields() {
        assertEquals(List.of("paths", "methods", "status", "description"),
                ListResourcesTool.summaryFields("routes"));
    }

    @Test
    void filesProjectMetadataFields() {
        assertEquals(List.of("contentType", "size", "description"),
                ListResourcesTool.summaryFields("files"));
    }

    @Test
    void promptsAndConversationsAreSimpleHierarchical() {
        assertEquals(List.of("displayName", "description"), ListResourcesTool.summaryFields("prompts"));
        assertEquals(List.of("displayName", "description"),
                ListResourcesTool.summaryFields("conversations"));
    }

    @Test
    void unknownTypeYieldsEmptyProjection() {
        assertTrue(ListResourcesTool.summaryFields("unknown").isEmpty());
        assertTrue(ListResourcesTool.summaryFields("settings").isEmpty(),
                "settings is singleton — never listed; projection table omits it");
    }
}
