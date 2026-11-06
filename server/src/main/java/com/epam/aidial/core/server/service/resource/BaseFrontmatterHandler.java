package com.epam.aidial.core.server.service.resource;

import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Base for complex resources described by a root Markdown manifest carrying YAML frontmatter (delimited by
 * {@code ---} lines) with at least {@code name} and {@code description}; {@code version} is optional.
 */
public abstract class BaseFrontmatterHandler implements ComplexResourceHandler {

    // Captures the YAML block between the leading and the next "---" delimiter.
    private static final Pattern FRONTMATTER = Pattern.compile(
            "^\\uFEFF?\\s*---\\s*\\R(.*?)\\R\\s*---\\s*(?:\\R|$)", Pattern.DOTALL);

    private static final ObjectMapper YAML = new YAMLMapper();

    private final String manifest;
    private final String label;

    /**
     * @param manifest root-relative manifest file name
     * @param label    resource kind used in error messages, e.g. {@code Skill}
     */
    protected BaseFrontmatterHandler(String manifest, String label) {
        this.manifest = manifest;
        this.label = label;
    }

    @Override
    public void validate(Map<String, byte[]> files) {
        Map<String, Object> frontmatter = parseFrontmatter(files);
        if (StringUtils.isBlank(asString(frontmatter.get("name")))) {
            throw new HttpException(HttpStatus.BAD_REQUEST, manifest + " frontmatter must define a non-empty 'name'");
        }
        if (StringUtils.isBlank(asString(frontmatter.get("description")))) {
            throw new HttpException(HttpStatus.BAD_REQUEST, manifest + " frontmatter must define a non-empty 'description'");
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
        if (mutation == FileMutation.DELETE && manifest.equals(relativePath)) {
            throw new HttpException(HttpStatus.BAD_REQUEST, manifest + " cannot be deleted");
        }
    }

    @Override
    public Map<String, Object> refreshMetadataOnPut(String relativePath, byte[] content) {
        if (!manifest.equals(relativePath)) {
            // Only the manifest carries the metadata; other files leave it unchanged.
            return null;
        }
        Map<String, byte[]> files = Map.of(manifest, content);
        validate(files);
        return buildMarkerMetadata(files);
    }

    private Map<String, Object> parseFrontmatter(Map<String, byte[]> files) {
        byte[] content = files.get(manifest);
        if (content == null) {
            throw new HttpException(HttpStatus.BAD_REQUEST, label + " must contain a " + manifest + " at its root");
        }

        Matcher matcher = FRONTMATTER.matcher(new String(content, StandardCharsets.UTF_8));
        if (!matcher.find()) {
            throw new HttpException(HttpStatus.BAD_REQUEST, manifest + " must start with YAML frontmatter delimited by '---'");
        }

        try {
            Map<String, Object> frontmatter = YAML.readValue(matcher.group(1), new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
            return frontmatter == null ? Map.of() : frontmatter;
        } catch (Exception e) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Failed to parse " + manifest + " frontmatter: " + e.getMessage());
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
