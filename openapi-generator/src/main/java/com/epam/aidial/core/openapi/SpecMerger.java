package com.epam.aidial.core.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class SpecMerger {

    private static final Set<String> MANUAL_PREFERRED_FIELDS = Set.of(
            "description", "summary", "example", "examples",
            "externalDocs", "x-codegen-request-body-name", "title"

    );

    private static final Set<String> SKELETON_PREFERRED_FIELDS = Set.of(
            "type", "properties", "required",
            "schema", "items", "allOf", "oneOf", "anyOf",
            "operationId", "tags", "discriminator"
    );

    private final ObjectMapper yamlMapper;
    private int addedCount;
    private int orphanedCount;
    private int mergedCount;

    public SpecMerger() {
        YAMLFactory yamlFactory = YAMLFactory.builder()
                .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .disable(YAMLGenerator.Feature.SPLIT_LINES)
                .build();
        this.yamlMapper = new ObjectMapper(yamlFactory);
        this.yamlMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("Usage: SpecMerger <skeleton> <manual> <output>");
            System.exit(1);
        }

        Path skeletonPath = Paths.get(args[0]);
        Path manualPath = Paths.get(args[1]);
        Path outputPath = Paths.get(args[2]);

        SpecMerger merger = new SpecMerger();
        merger.merge(skeletonPath, manualPath, outputPath);
    }

    public void merge(Path skeletonPath, Path manualPath, Path outputPath) throws IOException {
        ObjectNode skeleton = (ObjectNode) yamlMapper.readTree(Files.readString(skeletonPath));
        ObjectNode manual = (ObjectNode) yamlMapper.readTree(Files.readString(manualPath));

        ObjectNode merged = mergeNodes(skeleton, manual, "");

        stripMarkers(merged);

        Files.createDirectories(outputPath.getParent());
        String yamlOutput = yamlMapper.writeValueAsString(merged);
        Files.writeString(outputPath, yamlOutput);

        System.out.println("Merge complete:");
        System.out.println("  New endpoints/schemas (from skeleton): " + addedCount);
        System.out.println("  Orphaned entries (in manual only): " + orphanedCount);
        System.out.println("  Merged entries: " + mergedCount);
        System.out.println("Output: " + outputPath.toAbsolutePath());
    }

    private ObjectNode mergeNodes(ObjectNode skeleton, ObjectNode manual, String jsonPath) {
        if ("paths".equals(jsonPath)) {
            return mergePathObjects(skeleton, manual);
        }
        if ("components.schemas".equals(jsonPath)) {
            return mergeComponentSchemas(skeleton, manual);
        }

        ObjectNode result = yamlMapper.createObjectNode();

        // Manual ordering first, then new skeleton entries appended
        Set<String> allFields = new LinkedHashSet<>();
        manual.fieldNames().forEachRemaining(allFields::add);
        skeleton.fieldNames().forEachRemaining(allFields::add);

        for (String field : allFields) {
            boolean inSkeleton = skeleton.has(field);
            boolean inManual = manual.has(field);
            String childPath = jsonPath.isEmpty() ? field : jsonPath + "." + field;

            if (inSkeleton && inManual) {
                // Both have the field - merge
                JsonNode skeletonValue = skeleton.get(field);
                JsonNode manualValue = manual.get(field);
                result.set(field, mergeField(field, skeletonValue, manualValue, childPath));
                if (isPathOrSchemaField(jsonPath)) {
                    mergedCount++;
                }
            } else if (inSkeleton) {
                // New in skeleton - mark as generated (only at top-level paths/schemas)
                JsonNode skeletonValue = skeleton.get(field);
                if (isPathOrSchemaField(jsonPath) && skeletonValue.isObject()) {
                    ObjectNode markedNode = skeletonValue.deepCopy();
                    markedNode.put("x-generated", true);
                    result.set(field, markedNode);
                } else {
                    result.set(field, skeletonValue.deepCopy());
                }
                if (isPathOrSchemaField(jsonPath)) {
                    addedCount++;
                }
            } else {
                // Only in manual - preserve as orphaned
                JsonNode manualValue = manual.get(field);
                if (isPathOrSchemaField(jsonPath) && manualValue.isObject()) {
                    ObjectNode orphanedNode = manualValue.deepCopy();
                    orphanedNode.put("x-orphaned", true);
                    result.set(field, orphanedNode);
                    orphanedCount++;
                } else {
                    result.set(field, manualValue.deepCopy());
                }
            }
        }

        return result;
    }

    private ObjectNode mergePathObjects(ObjectNode skeleton, ObjectNode manual) {
        ObjectNode result = yamlMapper.createObjectNode();

        Map<String, String> skeletonRawByNorm = new LinkedHashMap<>();
        Map<String, ObjectNode> skeletonNodeByNorm = new LinkedHashMap<>();
        skeleton.fields().forEachRemaining(entry -> {
            String norm = OpenApiPathNormalizer.normalizePath(entry.getKey());
            skeletonRawByNorm.putIfAbsent(norm, entry.getKey());
            skeletonNodeByNorm.putIfAbsent(norm, (ObjectNode) entry.getValue());
        });

        Map<String, String> manualRawByNorm = new LinkedHashMap<>();
        Map<String, ObjectNode> manualNodeByNorm = new LinkedHashMap<>();
        manual.fields().forEachRemaining(entry -> {
            String norm = OpenApiPathNormalizer.normalizePath(entry.getKey());
            manualRawByNorm.putIfAbsent(norm, entry.getKey());
            manualNodeByNorm.putIfAbsent(norm, (ObjectNode) entry.getValue());
        });

        // Collect all paths and sort alphabetically for deterministic output
        Set<String> allNormalized = new TreeSet<>();
        manual.fields().forEachRemaining(entry ->
                allNormalized.add(OpenApiPathNormalizer.normalizePath(entry.getKey())));
        skeleton.fields().forEachRemaining(entry ->
                allNormalized.add(OpenApiPathNormalizer.normalizePath(entry.getKey())));

        for (String normPath : allNormalized) {
            String skeletonRaw = skeletonRawByNorm.get(normPath);
            String manualRaw = manualRawByNorm.get(normPath);
            ObjectNode skeletonNode = skeletonNodeByNorm.get(normPath);
            ObjectNode manualNode = manualNodeByNorm.get(normPath);

            if (skeletonNode != null && manualNode != null) {
                String outputKey = skeletonRaw != null ? skeletonRaw : manualRaw;
                result.set(outputKey, mergeNodes(skeletonNode, manualNode, "paths." + outputKey));
                mergedCount++;
            } else if (skeletonNode != null) {
                ObjectNode markedNode = skeletonNode.deepCopy();
                markedNode.put("x-generated", true);
                result.set(skeletonRaw, markedNode);
                addedCount++;
            } else {
                ObjectNode orphanedNode = manualNode.deepCopy();
                orphanedNode.put("x-orphaned", true);
                result.set(manualRaw, orphanedNode);
                orphanedCount++;
            }
        }

        return result;
    }

    private ObjectNode mergeComponentSchemas(ObjectNode skeleton, ObjectNode manual) {
        ObjectNode result = yamlMapper.createObjectNode();

        // Sort schema names alphabetically for deterministic output
        Set<String> allSchemas = new TreeSet<>();
        manual.fieldNames().forEachRemaining(allSchemas::add);
        skeleton.fieldNames().forEachRemaining(allSchemas::add);

        for (String schemaName : allSchemas) {
            JsonNode skeletonSchema = skeleton.get(schemaName);
            JsonNode manualSchema = manual.get(schemaName);

            if (skeletonSchema != null && manualSchema != null) {
                if (skeletonSchema.isObject() && manualSchema.isObject()) {
                    result.set(schemaName, mergeSchemaNodes((ObjectNode) skeletonSchema, (ObjectNode) manualSchema));
                } else {
                    result.set(schemaName, skeletonSchema.deepCopy());
                }
                mergedCount++;
            } else if (skeletonSchema != null) {
                ObjectNode markedNode = skeletonSchema.deepCopy();
                markedNode.put("x-generated", true);
                result.set(schemaName, markedNode);
                addedCount++;
            } else {
                ObjectNode orphanedNode = manualSchema.deepCopy();
                orphanedNode.put("x-orphaned", true);
                result.set(schemaName, orphanedNode);
                orphanedCount++;
            }
        }

        return result;
    }

    private JsonNode mergeField(String fieldName, JsonNode skeletonValue,
            JsonNode manualValue, String jsonPath) {
        // Extension fields prefer skeleton (from annotations), but merge recursively for complex types
        if (fieldName.startsWith("x-")) {
            // For simple values (strings, numbers, booleans), always take skeleton
            if (!skeletonValue.isObject() && !skeletonValue.isArray()) {
                return skeletonValue.deepCopy();
            }
            // For objects and arrays, merge recursively to preserve manual documentation within
            if (skeletonValue.isObject() && manualValue.isObject()) {
                return mergeNodes((ObjectNode) skeletonValue, (ObjectNode) manualValue, jsonPath);
            }
            if (skeletonValue.isArray() && manualValue.isArray()) {
                return mergeArrays((ArrayNode) skeletonValue, (ArrayNode) manualValue, jsonPath);
            }
            // If types don't match, prefer skeleton
            return skeletonValue.deepCopy();
        }
        // Security always comes from generated spec
        if ("security".equals(fieldName)) {
            return skeletonValue.deepCopy();
        }
        if ("responses".equals(fieldName) && skeletonValue.isObject() && manualValue.isObject()) {
            return mergeResponses((ObjectNode) skeletonValue, (ObjectNode) manualValue);
        }

        // requestBody -> merge with skeleton content structure as source of truth
        if ("requestBody".equals(fieldName) && skeletonValue.isObject() && manualValue.isObject()) {
            return mergeRequestBody((ObjectNode) skeletonValue, (ObjectNode) manualValue, jsonPath);
        }

        // Manual-preferred fields
        if (MANUAL_PREFERRED_FIELDS.contains(fieldName)) {
            return manualValue.deepCopy();
        }

        if ("schema".equals(fieldName) && skeletonValue.isObject() && manualValue.isObject()) {
            return mergeSchemaNodes((ObjectNode) skeletonValue, (ObjectNode) manualValue);
        }

        // Skeleton-preferred fields (structural truth from code)
        if (SKELETON_PREFERRED_FIELDS.contains(fieldName)) {
            if (skeletonValue.isObject() && manualValue.isObject()) {
                return mergeNodes((ObjectNode) skeletonValue, (ObjectNode) manualValue, jsonPath);
            }
            return skeletonValue.deepCopy();
        }

        // For tags at the top level, prefer manual (global tag definitions)
        if ("tags".equals(fieldName) && jsonPath.equals("tags")) {
            return manualValue.deepCopy();
        }

        // For operation-level tags, prefer skeleton (annotation is source of truth)
        if ("tags".equals(fieldName)) {
            return skeletonValue.deepCopy();
        }

        // For object nodes, recurse
        if (skeletonValue.isObject() && manualValue.isObject()) {
            return mergeNodes((ObjectNode) skeletonValue, (ObjectNode) manualValue, jsonPath);
        }

        // For array nodes, merge by index or keep skeleton structure
        if (skeletonValue.isArray() && manualValue.isArray()) {
            return mergeArrays((ArrayNode) skeletonValue, (ArrayNode) manualValue, jsonPath);
        }

        // Default: prefer skeleton for structural fields, manual for descriptive
        return skeletonValue.deepCopy();
    }

    private JsonNode mergeResponses(ObjectNode skeleton, ObjectNode manual) {
        ObjectNode result = yamlMapper.createObjectNode();

        // Collect all response codes
        List<String> allCodes = new ArrayList<>();
        manual.fieldNames().forEachRemaining(allCodes::add);
        skeleton.fieldNames().forEachRemaining(code -> {
            if (!allCodes.contains(code)) {
                allCodes.add(code);
            }
        });

        // Sort response codes: numeric codes ascending, "default" last
        allCodes.sort((code1, code2) -> {
            if ("default".equals(code1)) {
                return 1;
            }
            if ("default".equals(code2)) {
                return -1;
            }
            try {
                return Integer.compare(Integer.parseInt(code1), Integer.parseInt(code2));
            } catch (NumberFormatException e) {
                return code1.compareTo(code2);
            }
        });

        for (String code : allCodes) {
            JsonNode skeletonResponse = skeleton.get(code);
            JsonNode manualResponse = manual.get(code);

            if (skeletonResponse != null && manualResponse != null) {
                if (skeletonResponse.isObject() && manualResponse.isObject()) {
                    result.set(code, mergeResponse((ObjectNode) skeletonResponse, (ObjectNode) manualResponse, "responses." + code));
                } else {
                    result.set(code, skeletonResponse.deepCopy());
                }
            } else if (skeletonResponse != null) {
                result.set(code, skeletonResponse.deepCopy());
            } else {
                result.set(code, manualResponse.deepCopy());
            }
        }

        return result;
    }

    private JsonNode mergeArrays(ArrayNode skeleton, ArrayNode manual, String jsonPath) {
        if (skeleton.isEmpty()) {
            return manual.deepCopy();
        }
        if (manual.isEmpty()) {
            return skeleton.deepCopy();
        }

        // For arrays of objects, try to match by "name" key and merge recursively
        if (skeleton.get(0).isObject() && manual.get(0).isObject()) {
            return mergeObjectArrays(skeleton, manual, jsonPath);
        }

        // For simple arrays, union both (deduplicated)
        ArrayNode result = yamlMapper.createArrayNode();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode node : skeleton) {
            String text = node.isTextual() ? node.asText() : node.toString();
            if (seen.add(text)) {
                result.add(node.deepCopy());
            }
        }
        for (JsonNode node : manual) {
            String text = node.isTextual() ? node.asText() : node.toString();
            if (seen.add(text)) {
                result.add(node.deepCopy());
            }
        }
        return result;
    }

    private JsonNode mergeObjectArrays(ArrayNode skeleton, ArrayNode manual, String jsonPath) {
        ArrayNode result = yamlMapper.createArrayNode();
        Map<String, ObjectNode> manualByKey = new LinkedHashMap<>();

        for (JsonNode node : manual) {
            if (node.isObject() && node.has("name")) {
                manualByKey.put(elementKey(node), (ObjectNode) node);
            }
        }

        Set<String> mergedKeys = new LinkedHashSet<>();
        for (JsonNode skNode : skeleton) {
            if (skNode.isObject() && skNode.has("name")) {
                String key = elementKey(skNode);
                ObjectNode manualMatch = manualByKey.get(key);
                if (manualMatch != null) {
                    result.add(mergeNodes((ObjectNode) skNode, manualMatch, jsonPath + "[]"));
                    mergedKeys.add(key);
                } else {
                    result.add(skNode.deepCopy());
                }
            } else {
                result.add(skNode.deepCopy());
            }
        }

        // Append unmatched manual entries
        for (JsonNode node : manual) {
            if (node.isObject() && node.has("name")) {
                if (!mergedKeys.contains(elementKey(node))) {
                    result.add(node.deepCopy());
                }
            } else if (!node.isObject() || !node.has("name")) {
                result.add(node.deepCopy());
            }
        }

        return result;
    }

    /**
     * Merges schema objects with skeleton structure as absolute source of truth.
     * Manual specifications can only contribute documentation fields (description,
     * example, examples, title) but never structural fields
     * (type, properties, items, required, allOf, oneOf, anyOf, $ref, discriminator).
     * x-* extensions are merged recursively to preserve manual documentation within.
     */
    private ObjectNode mergeSchemaNodes(ObjectNode skeleton, ObjectNode manual) {
        ObjectNode result = skeleton.deepCopy();
        overlayDocumentationFields(manual, result);

        // Merge x-* extensions recursively to preserve manual documentation within
        for (Iterator<String> it = manual.fieldNames(); it.hasNext();) {
            String field = it.next();
            if (field.startsWith("x-")) {
                JsonNode manualExt = manual.get(field);
                JsonNode skeletonExt = result.get(field);

                if (skeletonExt != null && manualExt != null) {
                    // Both have the extension - merge recursively
                    if (skeletonExt.isArray() && manualExt.isArray()) {
                        result.set(field, mergeArrays((ArrayNode) skeletonExt, (ArrayNode) manualExt, ""));
                    } else if (skeletonExt.isObject() && manualExt.isObject()) {
                        result.set(field, mergeNodes((ObjectNode) skeletonExt, (ObjectNode) manualExt, ""));
                    }
                    // Otherwise keep skeleton value (already in result from deepCopy)
                }
                // If only in manual, don't add it (skeleton is source of truth for which extensions exist)
            }
        }

        return result;
    }

    /**
     * Merges a single media type object. Schema structure from skeleton;
     * documentation and examples from manual.
     */
    private ObjectNode mergeMediaType(ObjectNode skeletonMedia, ObjectNode manualMedia, String jsonPath) {
        ObjectNode result = yamlMapper.createObjectNode();

        Set<String> allFields = new LinkedHashSet<>();
        skeletonMedia.fieldNames().forEachRemaining(allFields::add);
        manualMedia.fieldNames().forEachRemaining(allFields::add);

        for (String field : allFields) {
            boolean inSkeleton = skeletonMedia.has(field);
            boolean inManual = manualMedia.has(field);

            if ("schema".equals(field)) {
                // Schema: skeleton structure is authoritative
                if (inSkeleton && inManual) {
                    JsonNode skeletonSchema = skeletonMedia.get("schema");
                    JsonNode manualSchema = manualMedia.get("schema");
                    if (skeletonSchema.isObject() && manualSchema.isObject()) {
                        result.set("schema", mergeSchemaNodes((ObjectNode) skeletonSchema, (ObjectNode) manualSchema));
                    } else {
                        result.set("schema", skeletonSchema.deepCopy());
                    }
                } else if (inSkeleton) {
                    result.set("schema", skeletonMedia.get("schema").deepCopy());
                } else {
                    result.set("schema", manualMedia.get("schema").deepCopy());
                }
            } else if ("example".equals(field) || "examples".equals(field)) {
                // Examples: always prefer manual
                if (inManual) {
                    result.set(field, manualMedia.get(field).deepCopy());
                } else if (inSkeleton) {
                    result.set(field, skeletonMedia.get(field).deepCopy());
                }
            } else if (MANUAL_PREFERRED_FIELDS.contains(field)) {
                // Documentation fields: prefer manual
                if (inManual) {
                    result.set(field, manualMedia.get(field).deepCopy());
                } else {
                    result.set(field, skeletonMedia.get(field).deepCopy());
                }
            } else if (field.startsWith("x-")) {
                // Extension fields: prefer skeleton (from annotations)
                if (inSkeleton) {
                    result.set(field, skeletonMedia.get(field).deepCopy());
                } else {
                    result.set(field, manualMedia.get(field).deepCopy());
                }
            } else {
                // Other fields: prefer skeleton
                if (inSkeleton) {
                    result.set(field, skeletonMedia.get(field).deepCopy());
                } else {
                    result.set(field, manualMedia.get(field).deepCopy());
                }
            }
        }

        return result;
    }

    /**
     * Merges content objects (collections of media types) from requestBody or responses.
     */
    private ObjectNode mergeContent(ObjectNode skeletonContent, ObjectNode manualContent, String jsonPath) {
        ObjectNode result = yamlMapper.createObjectNode();

        // Sort media types alphabetically for deterministic output
        Set<String> allMediaTypes = new TreeSet<>();
        manualContent.fieldNames().forEachRemaining(allMediaTypes::add);
        skeletonContent.fieldNames().forEachRemaining(allMediaTypes::add);

        for (String mediaType : allMediaTypes) {
            boolean inSkeleton = skeletonContent.has(mediaType);
            boolean inManual = manualContent.has(mediaType);

            if (inSkeleton && inManual) {
                JsonNode skeletonMedia = skeletonContent.get(mediaType);
                JsonNode manualMedia = manualContent.get(mediaType);
                if (skeletonMedia.isObject() && manualMedia.isObject()) {
                    result.set(mediaType, mergeMediaType((ObjectNode) skeletonMedia, (ObjectNode) manualMedia, jsonPath + "." + mediaType));
                } else {
                    result.set(mediaType, skeletonMedia.deepCopy());
                }
            } else if (inSkeleton) {
                result.set(mediaType, skeletonContent.get(mediaType).deepCopy());
            } else {
                result.set(mediaType, manualContent.get(mediaType).deepCopy());
            }
        }

        return result;
    }

    /**
     * Merges requestBody objects with skeleton content structure as source of truth.
     */
    private ObjectNode mergeRequestBody(ObjectNode skeleton, ObjectNode manual, String jsonPath) {
        ObjectNode result = yamlMapper.createObjectNode();

        Set<String> allFields = new LinkedHashSet<>();
        manual.fieldNames().forEachRemaining(allFields::add);
        skeleton.fieldNames().forEachRemaining(allFields::add);

        for (String field : allFields) {
            boolean inSkeleton = skeleton.has(field);
            boolean inManual = manual.has(field);

            if ("content".equals(field)) {
                if (inSkeleton && inManual) {
                    JsonNode skeletonContent = skeleton.get("content");
                    JsonNode manualContent = manual.get("content");
                    if (skeletonContent.isObject() && manualContent.isObject()) {
                        result.set("content", mergeContent((ObjectNode) skeletonContent, (ObjectNode) manualContent, jsonPath + ".content"));
                    } else {
                        result.set("content", skeletonContent.deepCopy());
                    }
                } else if (inSkeleton) {
                    result.set("content", skeleton.get("content").deepCopy());
                } else {
                    result.set("content", manual.get("content").deepCopy());
                }
            } else if (MANUAL_PREFERRED_FIELDS.contains(field)) {
                if (inManual) {
                    result.set(field, manual.get(field).deepCopy());
                } else if (inSkeleton) {
                    result.set(field, skeleton.get(field).deepCopy());
                }
            } else if (field.startsWith("x-")) {
                // Extension fields: prefer skeleton (from annotations)
                if (inSkeleton) {
                    result.set(field, skeleton.get(field).deepCopy());
                } else if (inManual) {
                    result.set(field, manual.get(field).deepCopy());
                }
            } else {
                if (inSkeleton) {
                    result.set(field, skeleton.get(field).deepCopy());
                } else if (inManual) {
                    result.set(field, manual.get(field).deepCopy());
                }
            }
        }

        return result;
    }

    /**
     * Merges a single response object with skeleton content structure as source of truth.
     */
    private ObjectNode mergeResponse(ObjectNode skeleton, ObjectNode manual, String jsonPath) {
        ObjectNode result = yamlMapper.createObjectNode();

        Set<String> allFields = new LinkedHashSet<>();
        manual.fieldNames().forEachRemaining(allFields::add);
        skeleton.fieldNames().forEachRemaining(allFields::add);

        for (String field : allFields) {
            boolean inSkeleton = skeleton.has(field);
            boolean inManual = manual.has(field);

            if ("content".equals(field)) {
                if (inSkeleton && inManual) {
                    JsonNode skeletonContent = skeleton.get("content");
                    JsonNode manualContent = manual.get("content");
                    if (skeletonContent.isObject() && manualContent.isObject()) {
                        result.set("content", mergeContent((ObjectNode) skeletonContent, (ObjectNode) manualContent, jsonPath + ".content"));
                    } else {
                        result.set("content", skeletonContent.deepCopy());
                    }
                } else if (inSkeleton) {
                    result.set("content", skeleton.get("content").deepCopy());
                } else {
                    result.set("content", manual.get("content").deepCopy());
                }
            } else if ("headers".equals(field)) {
                if (inSkeleton && inManual) {
                    JsonNode skeletonHeaders = skeleton.get("headers");
                    JsonNode manualHeaders = manual.get("headers");
                    if (skeletonHeaders.isObject() && manualHeaders.isObject()) {
                        result.set("headers", mergeNodes((ObjectNode) skeletonHeaders, (ObjectNode) manualHeaders, jsonPath + ".headers"));
                    } else {
                        result.set("headers", skeletonHeaders.deepCopy());
                    }
                } else if (inSkeleton) {
                    result.set("headers", skeleton.get("headers").deepCopy());
                } else {
                    result.set("headers", manual.get("headers").deepCopy());
                }
            } else if (MANUAL_PREFERRED_FIELDS.contains(field)) {
                if (inManual) {
                    result.set(field, manual.get(field).deepCopy());
                } else if (inSkeleton) {
                    result.set(field, skeleton.get(field).deepCopy());
                }
            } else if (field.startsWith("x-")) {
                // Extension fields: prefer skeleton (from annotations)
                if (inSkeleton) {
                    result.set(field, skeleton.get(field).deepCopy());
                } else if (inManual) {
                    result.set(field, manual.get(field).deepCopy());
                }
            } else {
                if (inSkeleton) {
                    result.set(field, skeleton.get(field).deepCopy());
                } else if (inManual) {
                    result.set(field, manual.get(field).deepCopy());
                }
            }
        }

        return result;
    }

    /**
     * Overlays only documentation fields from manual onto skeleton target,
     * without modifying any structural schema fields or extension fields (x-*).
     * Extension fields are controlled by annotations and come from the skeleton.
     * For nested structural fields like properties, recursively overlays documentation
     * within the skeleton structure.
     */
    private void overlayDocumentationFields(ObjectNode manual, ObjectNode target) {
        for (String field : MANUAL_PREFERRED_FIELDS) {
            if (manual.has(field)) {
                target.set(field, manual.get(field).deepCopy());
            }
        }
        // Note: x-* extensions are NOT overlaid from manual - they come from skeleton (annotations)

        // Recursively overlay documentation in nested structural fields
        if (manual.has("properties") && target.has("properties")) {
            JsonNode manualProps = manual.get("properties");
            JsonNode targetProps = target.get("properties");
            if (manualProps.isObject() && targetProps.isObject()) {
                overlayDocumentationInProperties((ObjectNode) manualProps, (ObjectNode) targetProps);
            }
        }
        if (manual.has("items") && target.has("items")) {
            JsonNode manualItems = manual.get("items");
            JsonNode targetItems = target.get("items");
            if (manualItems.isObject() && targetItems.isObject()) {
                overlayDocumentationFields((ObjectNode) manualItems, (ObjectNode) targetItems);
            }
        }
        if (manual.has("allOf") && target.has("allOf")) {
            overlayDocumentationInArray(manual.get("allOf"), target.get("allOf"));
        }
        if (manual.has("oneOf") && target.has("oneOf")) {
            overlayDocumentationInArray(manual.get("oneOf"), target.get("oneOf"));
        }
        if (manual.has("anyOf") && target.has("anyOf")) {
            overlayDocumentationInArray(manual.get("anyOf"), target.get("anyOf"));
        }
    }

    /**
     * Overlays documentation fields on properties object (recursively handles nested properties).
     */
    private void overlayDocumentationInProperties(ObjectNode manualProps, ObjectNode targetProps) {
        for (Iterator<String> it = manualProps.fieldNames(); it.hasNext();) {
            String propName = it.next();
            if (targetProps.has(propName)) {
                JsonNode manualProp = manualProps.get(propName);
                JsonNode targetProp = targetProps.get(propName);
                if (manualProp.isObject() && targetProp.isObject()) {
                    overlayDocumentationFields((ObjectNode) manualProp, (ObjectNode) targetProp);
                }
            }
        }
    }

    /**
     * Overlays documentation in schema arrays (allOf, oneOf, anyOf).
     */
    private void overlayDocumentationInArray(JsonNode manualArray, JsonNode targetArray) {
        if (manualArray.isArray() && targetArray.isArray()
                && manualArray.size() == targetArray.size()) {
            for (int i = 0; i < manualArray.size(); i++) {
                JsonNode manualItem = manualArray.get(i);
                JsonNode targetItem = targetArray.get(i);
                if (manualItem.isObject() && targetItem.isObject()) {
                    overlayDocumentationFields((ObjectNode) manualItem, (ObjectNode) targetItem);
                }
            }
        }
    }

    private static String elementKey(JsonNode node) {
        String name = node.path("name").asText();
        if (node.has("in")) {
            String in = node.get("in").asText();
            if ("path".equals(in)) {
                name = name.toLowerCase(Locale.ROOT);
            }
            return name + "@" + in;
        }
        return name;
    }


    private boolean isPathOrSchemaField(String jsonPath) {
        return jsonPath.equals("paths") || jsonPath.equals("components.schemas");
    }

    private void stripMarkers(ObjectNode node) {
        node.remove("x-orphaned");
        node.remove("x-generated");
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            JsonNode child = fields.next().getValue();
            if (child.isObject()) {
                stripMarkers((ObjectNode) child);
            } else if (child.isArray()) {
                for (JsonNode element : child) {
                    if (element.isObject()) {
                        stripMarkers((ObjectNode) element);
                    }
                }
            }
        }
    }
}