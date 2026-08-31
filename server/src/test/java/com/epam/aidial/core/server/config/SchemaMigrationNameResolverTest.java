package com.epam.aidial.core.server.config;

import com.epam.aidial.core.server.config.SchemaMigrationNameResolver.Resolution;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaMigrationNameResolverTest {

    @Test
    void resolveNames_usesLastPathSegment_verbatimCase() {
        String id = "https://example.com/schemas/MySchema";
        Map<String, Resolution> resolutions = SchemaMigrationNameResolver.resolveNames(
                Map.of(id, "{}"), Map.of());

        assertTrue(resolutions.get(id).isValid());
        assertEquals("MySchema", resolutions.get(id).blobName());
    }

    @Test
    void resolveNames_fallsBackToHost_whenPathIsEmpty() {
        String id = "https://dial.com";
        Map<String, Resolution> resolutions = SchemaMigrationNameResolver.resolveNames(
                Map.of(id, "{}"), Map.of());

        assertTrue(resolutions.get(id).isValid());
        assertEquals("dial.com", resolutions.get(id).blobName());
    }

    @Test
    void resolveNames_fallsBackToHost_whenPathIsRootOnly() {
        String id = "https://dial.com/";
        Map<String, Resolution> resolutions = SchemaMigrationNameResolver.resolveNames(
                Map.of(id, "{}"), Map.of());

        assertTrue(resolutions.get(id).isValid());
        assertEquals("dial.com", resolutions.get(id).blobName());
    }

    @Test
    void resolveNames_fails_forMalformedUri() {
        String id = "https://exa mple.com/schema";
        Map<String, Resolution> resolutions = SchemaMigrationNameResolver.resolveNames(
                Map.of(id, "{}"), Map.of());

        assertFalse(resolutions.get(id).isValid());
        assertTrue(resolutions.get(id).error().contains("not a valid URI"));
    }

    @Test
    void resolveNames_fails_whenNoPathAndNoHost() {
        String id = "urn:foo";
        Map<String, Resolution> resolutions = SchemaMigrationNameResolver.resolveNames(
                Map.of(id, "{}"), Map.of());

        assertFalse(resolutions.get(id).isValid());
        assertTrue(resolutions.get(id).error().contains("Cannot derive a blob name"));
    }

    @Test
    void resolveNames_fails_forIllegalCharacters() {
        String id = "https://dial.com/my!schema";
        Map<String, Resolution> resolutions = SchemaMigrationNameResolver.resolveNames(
                Map.of(id, "{}"), Map.of());

        assertFalse(resolutions.get(id).isValid());
        assertTrue(resolutions.get(id).error().contains("illegal characters"));
    }

    @Test
    void resolveNames_fails_forCaseInsensitiveBatchCollision_twoWay() {
        String idA = "https://dial.com/my-schema";
        String idB = "https://epam.com/My-Schema";
        Map<String, Resolution> resolutions = SchemaMigrationNameResolver.resolveNames(
                Map.of(idA, "{}", idB, "{}"), Map.of());

        assertFalse(resolutions.get(idA).isValid());
        assertFalse(resolutions.get(idB).isValid());
        assertTrue(resolutions.get(idA).error().contains(idB));
        assertTrue(resolutions.get(idB).error().contains(idA));
    }

    @Test
    void resolveNames_fails_forCaseInsensitiveBatchCollision_threeWay() {
        String idA = "https://a.com/dup";
        String idB = "https://b.com/DUP";
        String idC = "https://c.com/Dup";
        Map<String, Resolution> resolutions = SchemaMigrationNameResolver.resolveNames(
                Map.of(idA, "{}", idB, "{}", idC, "{}"), Map.of());

        assertFalse(resolutions.get(idA).isValid());
        assertFalse(resolutions.get(idB).isValid());
        assertFalse(resolutions.get(idC).isValid());
    }

    @Test
    void resolveNames_fails_forCaseInsensitiveExistingBlobCollision() {
        String id = "https://dial.com/my-schema";
        String otherId = "https://dial.com/my-schema-old";
        Map<String, Resolution> resolutions = SchemaMigrationNameResolver.resolveNames(
                Map.of(id, "{}"), Map.of("My-Schema", otherId));

        assertFalse(resolutions.get(id).isValid());
        assertTrue(resolutions.get(id).error().contains(otherId));
    }

    @Test
    void resolveNames_allSucceed_whenNoCollisions() {
        String idA = "https://dial.com/one";
        String idB = "https://dial.com/two";
        Map<String, Resolution> resolutions = SchemaMigrationNameResolver.resolveNames(
                Map.of(idA, "{}", idB, "{}"), Map.of());

        assertTrue(resolutions.get(idA).isValid());
        assertTrue(resolutions.get(idB).isValid());
        assertEquals("one", resolutions.get(idA).blobName());
        assertEquals("two", resolutions.get(idB).blobName());
    }
}
