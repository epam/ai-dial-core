package com.epam.aidial.core.server.service.folder;

import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SkillHandlerTest {

    private final SkillHandler handler = new SkillHandler();

    @Test
    void testResourceType() {
        assertEquals(ResourceTypes.SKILL, handler.resourceType());
    }

    @Test
    void testValidateAcceptsCompleteFrontmatter() {
        // no exception expected
        handler.validate(files("""
                ---
                name: My Skill
                description: Does something useful
                version: 1.0.0
                ---
                # Body
                """));
    }

    @Test
    void testValidateAcceptsExtraFilesAlongsideManifest() {
        Map<String, byte[]> files = files("""
                ---
                name: My Skill
                description: Does something useful
                ---
                """);
        files.put("scripts/run.sh", "echo hi".getBytes(StandardCharsets.UTF_8));

        handler.validate(files);
    }

    @Test
    void testValidateRejectsMissingManifest() {
        Map<String, byte[]> files = new HashMap<>();
        files.put("data.txt", "x".getBytes(StandardCharsets.UTF_8));

        HttpException ex = assertThrows(HttpException.class, () -> handler.validate(files));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void testValidateRejectsMissingFrontmatter() {
        HttpException ex = assertThrows(HttpException.class, () -> handler.validate(files("# no frontmatter here")));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void testValidateRejectsMissingName() {
        HttpException ex = assertThrows(HttpException.class, () -> handler.validate(files("""
                ---
                description: Does something useful
                ---
                """)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void testValidateRejectsBlankName() {
        HttpException ex = assertThrows(HttpException.class, () -> handler.validate(files("""
                ---
                name: "  "
                description: Does something useful
                ---
                """)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void testValidateRejectsMissingDescription() {
        HttpException ex = assertThrows(HttpException.class, () -> handler.validate(files("""
                ---
                name: My Skill
                ---
                """)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void testValidateRejectsUnparseableFrontmatter() {
        HttpException ex = assertThrows(HttpException.class, () -> handler.validate(files("""
                ---
                name: My Skill
                description: : : not valid
                  bad indent
                ---
                """)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void testBuildMarkerMetadataExtractsFields() {
        Map<String, Object> metadata = handler.buildMarkerMetadata(files("""
                ---
                name: My Skill
                description: Does something useful
                version: 1.2.3
                ---
                # Body
                """));

        assertEquals("My Skill", metadata.get("name"));
        assertEquals("Does something useful", metadata.get("description"));
        assertEquals("1.2.3", metadata.get("version"));
    }

    @Test
    void testBuildMarkerMetadataOmitsAbsentVersion() {
        Map<String, Object> metadata = handler.buildMarkerMetadata(files("""
                ---
                name: My Skill
                description: Does something useful
                ---
                """));

        assertEquals("My Skill", metadata.get("name"));
        assertEquals("Does something useful", metadata.get("description"));
        assertFalse(metadata.containsKey("version"));
    }

    @Test
    void testBuildMarkerMetadataCoercesNonStringVersion() {
        Map<String, Object> metadata = handler.buildMarkerMetadata(files("""
                ---
                name: My Skill
                description: Does something useful
                version: 2
                ---
                """));

        assertEquals("2", metadata.get("version"));
    }

    @Test
    void testValidateHandlesLeadingByteOrderMarkAndCrlf() {
        String manifest = "﻿---\r\nname: My Skill\r\ndescription: Does something useful\r\n---\r\n# Body\r\n";
        handler.validate(files(manifest));
    }

    @Test
    void testBuildMarkerMetadataMissingNameYieldsNull() {
        // buildMarkerMetadata does not validate; an absent name maps to null
        Map<String, Object> metadata = handler.buildMarkerMetadata(files("""
                ---
                description: Does something useful
                ---
                """));
        assertNull(metadata.get("name"));
    }

    private static Map<String, byte[]> files(String manifest) {
        Map<String, byte[]> files = new HashMap<>();
        files.put(SkillHandler.MANIFEST, manifest.getBytes(StandardCharsets.UTF_8));
        return files;
    }
}
