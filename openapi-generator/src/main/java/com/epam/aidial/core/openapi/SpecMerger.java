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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SpecMerger {

    private static final Set<String> MANUAL_PREFERRED_FIELDS = Set.of(
            "description", "summary", "example", "examples",
            "externalDocs", "x-codegen-request-body-name", "title"

    );

    private static final Set<String> SKELETON_PREFERRED_FIELDS = Set.of(
            "type", "properties", "required",
            "schema", "items", "allOf", "oneOf", "anyOf",
            "operationId", "tags"
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
                    if (jsonPath.equals("paths")) {
                        tagOperationsAsUncategorized(markedNode);
                    }
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

        Set<String> allNormalized = new LinkedHashSet<>();
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
                tagOperationsAsUncategorized(markedNode);
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

    private JsonNode mergeField(String fieldName, JsonNode skeletonValue,
            JsonNode manualValue, String jsonPath) {
        // Extension fields always prefer manual
        if (fieldName.startsWith("x-")) {
            return manualValue.deepCopy();
        }
        // Security always comes from generated spec
        if ("security".equals(fieldName)) {
            return skeletonValue.deepCopy();
        }
        if ("responses".equals(fieldName) && skeletonValue.isObject() && manualValue.isObject()) {
            return mergeResponses((ObjectNode) skeletonValue, (ObjectNode) manualValue);
        }

        // requestBody -> recursive merge
        if ("requestBody".equals(fieldName) && skeletonValue.isObject() && manualValue.isObject()) {
            return mergeNodes((ObjectNode) skeletonValue, (ObjectNode) manualValue, jsonPath);
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

        Set<String> allCodes = new LinkedHashSet<>();

        manual.fieldNames().forEachRemaining(allCodes::add);
        skeleton.fieldNames().forEachRemaining(allCodes::add);

        for (String code : allCodes) {
            JsonNode skeletonResponse = skeleton.get(code);
            JsonNode manualResponse = manual.get(code);

            if (skeletonResponse != null && manualResponse != null) {
                if (skeletonResponse.isObject() && manualResponse.isObject()) {
                    result.set(
                            code,
                            mergeNodes((ObjectNode) skeletonResponse, (ObjectNode) manualResponse, "responses." + code)
                    );
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
     * OpenAPI schema objects must not combine {@code $ref} with sibling keywords such as
     * {@code type} or {@code items}. When one side is a structural schema and the other is
     * ref-only, keep the structural schema (typically the documented streaming shape).
     */
    private ObjectNode mergeSchemaNodes(ObjectNode skeleton, ObjectNode manual) {
        boolean skeletonRef = skeleton.has("$ref");
        boolean manualRef = manual.has("$ref");
        boolean skeletonStructural = hasStructuralSchemaFields(skeleton);
        boolean manualStructural = hasStructuralSchemaFields(manual);

        if (skeletonRef && manualStructural && !manualRef) {
            return manual.deepCopy();
        }
        if (manualRef && skeletonStructural && !skeletonRef) {
            ObjectNode result = skeleton.deepCopy();
            overlayManualPreferredFields(manual, result);
            return result;
        }
        if (skeletonRef && manualRef) {
            return manual.deepCopy();
        }
        if (skeletonRef ^ manualRef) {
            ObjectNode result = (manualRef ? manual : skeleton).deepCopy();
            overlayManualPreferredFields(manualRef ? skeleton : manual, result);
            return result;
        }
        return (ObjectNode) mergeNodes(skeleton, manual, "schema");
    }

    private void overlayManualPreferredFields(ObjectNode manual, ObjectNode target) {
        for (String field : MANUAL_PREFERRED_FIELDS) {
            if (manual.has(field)) {
                target.set(field, manual.get(field).deepCopy());
            }
        }
        for (Iterator<String> it = manual.fieldNames(); it.hasNext();) {
            String field = it.next();
            if (field.startsWith("x-") && !target.has(field)) {
                target.set(field, manual.get(field).deepCopy());
            }
        }
    }

    private static boolean hasStructuralSchemaFields(ObjectNode node) {
        return node.has("type") || node.has("items") || node.has("properties")
                || node.has("allOf") || node.has("oneOf") || node.has("anyOf");
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

    private static final Set<String> HTTP_METHODS = Set.of(
            "get", "post", "put", "delete", "patch", "head", "options"
    );

    private void tagOperationsAsUncategorized(ObjectNode pathItem) {
        for (String method : HTTP_METHODS) {
            JsonNode op = pathItem.get(method);
            if (op != null && op.isObject()) {
                ArrayNode tags = yamlMapper.createArrayNode();
                tags.add("uncategorized");
                ((ObjectNode) op).set("tags", tags);
            }
        }
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