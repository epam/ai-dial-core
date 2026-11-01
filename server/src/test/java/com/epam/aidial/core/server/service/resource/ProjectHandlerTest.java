package com.epam.aidial.core.server.service.resource;

import com.epam.aidial.core.storage.resource.ResourceTypes;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProjectHandlerTest {

    private final ProjectHandler handler = new ProjectHandler();

    @Test
    void testResourceType() {
        assertEquals(ResourceTypes.PROJECT, handler.resourceType());
    }

    @Test
    void testValidateAcceptsAnyFileSetWithoutManifest() {
        // no exception expected
        handler.validate(Map.of("data.txt", new byte[] {1}));
        handler.validate(Map.of("PROJECT.md", "not frontmatter".getBytes()));
    }

    @Test
    void testBuildMarkerMetadataIsEmpty() {
        assertEquals(Map.of(), handler.buildMarkerMetadata(Map.of("data.txt", new byte[] {1})));
    }

    @Test
    void testAnyFileMutationAllowed() {
        for (ComplexResourceHandler.FileMutation mutation : ComplexResourceHandler.FileMutation.values()) {
            handler.validateFileMutation("PROJECT.md", mutation);
            handler.validateFileMutation("scripts/run.sh", mutation);
        }
    }

    @Test
    void testRefreshMetadataOnPutKeepsExistingMetadata() {
        assertNull(handler.refreshMetadataOnPut("PROJECT.md", "x".getBytes()));
        assertNull(handler.refreshMetadataOnPut("a.txt", "x".getBytes()));
    }
}
