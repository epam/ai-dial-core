package com.epam.aidial.cli.service.manifest;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.Map;

/**
 * A parsed manifest entry. Exactly one of {@code spec} or {@code patch} is non-null —
 * regular entity manifests carry {@code spec}; Bundle entries with {@code patch:} carry
 * the patch JsonNode to be resolved at apply time (GET → JSON Merge Patch). {@code
 * source} is the file the manifest was loaded from (used by overlay {@code .disable}
 * matching to compute the file's relative path under the {@code -f} root); it is
 * {@code null} when callers construct a manifest synthetically.
 */
public record Manifest(String kind, String name, JsonNode spec, JsonNode patch, String templateName,
                       Map<String, Object> params, Path source) { }
