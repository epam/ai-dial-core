package com.epam.aidial.core.storage.data;

import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FileMetadataTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testOrdinaryFileOmitsResourceReference() {
        ResourceDescriptor descriptor = new ResourceDescriptor(ResourceTypes.FILE, "a.txt", List.of(), "public", "public/", false);
        FileMetadata metadata = new FileMetadata(descriptor, 12, "text/plain");
        metadata.setEtag("\"file-etag\"");
        JsonNode json = mapper.valueToTree(metadata);
        assertFalse(json.has("resource"));
        assertFalse(json.has("descriptor"));
        assertEquals("files/public/a.txt", json.get("url").asText());
        assertEquals(12, json.get("contentLength").asLong());
        assertEquals("text/plain", json.get("contentType").asText());
        assertEquals("\"file-etag\"", json.get("etag").asText());
    }

    @Test
    void testComplexFileRetainsFullLogicalMetadataAndResourceReference() {
        ResourceDescriptor descriptor = new ResourceDescriptor(ResourceTypes.SKILL, "a.md",
                List.of("team", "skill", "files", "refs"), "public", "public/", false);
        FileMetadata metadata = new FileMetadata(descriptor, 7, "image/png");
        metadata.setEtag("\"file-etag\"");
        metadata.setCreatedAt(1L);
        metadata.setUpdatedAt(2L);
        metadata.setAuthor("writer");
        metadata.setResource(new FileMetadata.ResourceReference("skills/public/team/skill", "\"resource-etag\""));
        JsonNode json = mapper.valueToTree(metadata);
        assertEquals("ITEM", json.get("nodeType").asText());
        assertEquals("SKILL", json.get("resourceType").asText());
        assertEquals("a.md", json.get("name").asText());
        assertEquals("team/skill/files/refs", json.get("parentPath").asText());
        assertEquals("public", json.get("bucket").asText());
        assertEquals("skills/public/team/skill/files/refs/a.md", json.get("url").asText());
        assertEquals(7, json.get("contentLength").asLong());
        assertEquals("image/png", json.get("contentType").asText());
        assertEquals(1, json.get("createdAt").asLong());
        assertEquals(2, json.get("updatedAt").asLong());
        assertEquals("writer", json.get("author").asText());
        assertEquals("\"file-etag\"", json.get("etag").asText());
        assertEquals("skills/public/team/skill", json.get("resource").get("url").asText());
        assertEquals("\"resource-etag\"", json.get("resource").get("etag").asText());
        assertEquals(2, json.get("resource").size());
    }
}
