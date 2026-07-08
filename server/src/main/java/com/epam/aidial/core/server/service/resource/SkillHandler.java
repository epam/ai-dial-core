package com.epam.aidial.core.server.service.resource;

import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceType;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handler for the {@code skills} group. A skill must contain a {@code SKILL.md} at its root carrying
 * YAML frontmatter (delimited by {@code ---} lines) with at least {@code name} and {@code description}.
 */
public class SkillHandler implements ComplexResourceHandler {

    public static final String MANIFEST = "SKILL.md";

    // Captures the YAML block between the leading and the next "---" delimiter.
    private static final Pattern FRONTMATTER = Pattern.compile(
            "^\\uFEFF?\\s*---\\s*\\R(.*?)\\R\\s*---\\s*(?:\\R|$)", Pattern.DOTALL);

    private static final ObjectMapper YAML = new YAMLMapper();

    @Override
    public ResourceType resourceType() {
        return ResourceTypes.SKILL;
    }

    @Override
    public void validate(Map<String, byte[]> files) {
        Map<String, Object> frontmatter = parseFrontmatter(files);
        if (StringUtils.isBlank(asString(frontmatter.get("name")))) {
            throw new HttpException(HttpStatus.BAD_REQUEST, MANIFEST + " frontmatter must define a non-empty 'name'");
        }
        if (StringUtils.isBlank(asString(frontmatter.get("description")))) {
            throw new HttpException(HttpStatus.BAD_REQUEST, MANIFEST + " frontmatter must define a non-empty 'description'");
        }
    }

    @Override
    public Map<String, Object> buildMarkerMetadata(Map<String, byte[]> files) {
        Map<String, Object> frontmatter = parseFrontmatter(files);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", asString(frontmatter.get("name")));
        metadata.put("description", asString(frontmatter.get("description")));
        Object version = frontmatter.get("version");
        if (version != null) {
            metadata.put("version", asString(version));
        }
        return metadata;
    }

    @Override
    public void validateFileMutation(String relativePath, FileMutation mutation) {
        if (mutation == FileMutation.DELETE && MANIFEST.equals(relativePath)) {
            throw new HttpException(HttpStatus.BAD_REQUEST, MANIFEST + " cannot be deleted");
        }
    }

    @Override
    public Map<String, Object> refreshMetadataOnPut(String relativePath, byte[] content) {
        if (!MANIFEST.equals(relativePath)) {
            // Only the manifest carries skill metadata; other files leave it unchanged.
            return null;
        }
        Map<String, byte[]> files = Map.of(MANIFEST, content);
        validate(files);
        return buildMarkerMetadata(files);
    }

    private Map<String, Object> parseFrontmatter(Map<String, byte[]> files) {
        byte[] manifest = files.get(MANIFEST);
        if (manifest == null) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Skill must contain a " + MANIFEST + " at its root");
        }

        String content = new String(manifest, StandardCharsets.UTF_8);
        Matcher matcher = FRONTMATTER.matcher(content);
        if (!matcher.find()) {
            throw new HttpException(HttpStatus.BAD_REQUEST, MANIFEST + " must start with YAML frontmatter delimited by '---'");
        }

        try {
            Map<String, Object> frontmatter = YAML.readValue(matcher.group(1), new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
            return frontmatter == null ? Map.of() : frontmatter;
        } catch (Exception e) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Failed to parse " + MANIFEST + " frontmatter: " + e.getMessage());
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
