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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class SpecMerger {

    private enum FieldPrecedence {
        MANUAL, SKELETON, MERGE_RECURSIVELY
    }

    private static final Set<String> MANUAL_PREFERRED_FIELDS = Set.of(
            "description", "summary", "example", "examples", "externalDocs", "x-codegen-request-body-name", "title"
    );

    private static final Set<String> SKELETON_PREFERRED_FIELDS = Set.of(
            "tags", "type", "properties", "required", "schema", "items", "allOf", "oneOf", "anyOf", "operationId", "discriminator"
    );

    private final ObjectMapper yamlMapper;
    private int addedCount;
    private int orphanedCount;

    private final List<String> orphanedPaths = new ArrayList<>();
    private final List<String> orphanedSchemas = new ArrayList<>();

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
        new SpecMerger().merge(Paths.get(args[0]), Paths.get(args[1]), Paths.get(args[2]));
    }

    public void merge(Path skeletonPath, Path manualPath, Path outputPath) throws IOException {
        ObjectNode skeleton = (ObjectNode) yamlMapper.readTree(Files.readString(skeletonPath));
        ObjectNode manual = (ObjectNode) yamlMapper.readTree(Files.readString(manualPath));

        ObjectNode merged = mergeNodes(skeleton, manual, "");

        stripMarkers(merged);

        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, yamlMapper.writeValueAsString(merged));

        System.out.println("Merge complete:");
        System.out.println("  New top-level endpoints/schemas (from skeleton): " + addedCount);
        System.out.println("  Orphaned top-level entries removed (in manual only): " + orphanedCount);

        if (orphanedCount > 0) {
            System.out.println("WARN: Detected and removed deleted (orphaned) entries from manual spec:");
            if (!orphanedPaths.isEmpty()) {
                System.out.println("  Removed Paths (no longer present in Java source code):");
                orphanedPaths.forEach(path -> System.out.println("    - " + path));
            }
            if (!orphanedSchemas.isEmpty()) {
                System.out.println("  Removed Component Schemas (no longer present in Java code):");
                orphanedSchemas.forEach(schema -> System.out.println("    - " + schema));
            }
        }
    }

    private ObjectNode mergeNodes(ObjectNode skeleton, ObjectNode manual, String jsonPath) {
        if ("paths".equals(jsonPath)) {
            return mergePaths(skeleton, manual);
        }
        if ("components.schemas".equals(jsonPath)) {
            return mergeSchemas(skeleton, manual);
        }

        ObjectNode result = yamlMapper.createObjectNode();
        Set<String> allFields = new LinkedHashSet<>();
        if (manual != null) {
            manual.fieldNames().forEachRemaining(allFields::add);
        }
        if (skeleton != null) {
            skeleton.fieldNames().forEachRemaining(allFields::add);
        }

        for (String field : allFields) {
            JsonNode skelVal = skeleton != null ? skeleton.get(field) : null;
            JsonNode manVal = manual != null ? manual.get(field) : null;
            String childPath = jsonPath.isEmpty() ? field : jsonPath + "." + field;

            if (skelVal != null && manVal != null) {
                if ("tags".equals(field) && skelVal.isArray() && manVal.isArray()) {
                    result.set(field, mergeTags((ArrayNode) skelVal, (ArrayNode) manVal, childPath));
                } else if ("enum".equals(field)) {
                    result.set(field, skelVal.deepCopy());
                } else if ("responses".equals(field) && skelVal.isObject() && manVal.isObject()) {
                    result.set(field, mergeResponses((ObjectNode) skelVal, (ObjectNode) manVal, childPath));
                } else if ("content".equals(field) && skelVal.isObject() && manVal.isObject()) {
                    result.set(field, mergeContent((ObjectNode) skelVal, (ObjectNode) manVal, childPath));
                } else if ("parameters".equals(field) && skelVal.isArray() && manVal.isArray()) {
                    result.set(field, mergeParameters((ArrayNode) skelVal, (ArrayNode) manVal, childPath));
                } else if (Set.of("allOf", "oneOf", "anyOf").contains(field) && skelVal.isArray() && manVal.isArray()) {
                    result.set(field, mergeSchemaArrays((ArrayNode) skelVal, (ArrayNode) manVal, childPath));
                } else {
                    FieldPrecedence precedence = resolvePrecedence(field);

                    if (precedence == FieldPrecedence.MANUAL) {
                        result.set(field, manVal.deepCopy());
                    } else {
                        if (skelVal.isObject() && manVal.isObject()) {
                            if (skelVal.has("$ref") && !manVal.has("$ref")) {
                                ObjectNode mergedRef = (ObjectNode) skelVal.deepCopy();
                                for (String docField : MANUAL_PREFERRED_FIELDS) {
                                    if (manVal.has(docField)) {
                                        mergedRef.set(docField, manVal.get(docField).deepCopy());
                                    }
                                }
                                result.set(field, mergedRef);
                            } else {
                                result.set(field, mergeNodes((ObjectNode) skelVal, (ObjectNode) manVal, childPath));
                            }
                        } else if (skelVal.isArray() && manVal.isArray()) {
                            result.set(field, mergeArrays((ArrayNode) skelVal, (ArrayNode) manVal, childPath));
                        } else {
                            result.set(field, skelVal.deepCopy());
                        }
                    }
                }
            } else if (skelVal != null) {
                result.set(field, skelVal.deepCopy());
            } else {
                result.set(field, manVal.deepCopy());
            }
        }
        return result;
    }

    private FieldPrecedence resolvePrecedence(String fieldName) {
        if (fieldName.startsWith("x-")) {
            return FieldPrecedence.MERGE_RECURSIVELY;
        }
        if (MANUAL_PREFERRED_FIELDS.contains(fieldName)) {
            return FieldPrecedence.MANUAL;
        }
        if (SKELETON_PREFERRED_FIELDS.contains(fieldName)) {
            return FieldPrecedence.SKELETON;
        }
        return FieldPrecedence.MERGE_RECURSIVELY;
    }

    private ObjectNode mergePaths(ObjectNode skeleton, ObjectNode manual) {
        ObjectNode result = yamlMapper.createObjectNode();

        Map<String, String> skelRawByNorm = new LinkedHashMap<>();
        if (skeleton != null) {
            skeleton.fieldNames().forEachRemaining(k -> skelRawByNorm.putIfAbsent(OpenApiPathNormalizer.normalizePath(k), k));
        }

        Map<String, String> manRawByNorm = new LinkedHashMap<>();
        if (manual != null) {
            manual.fieldNames().forEachRemaining(k -> manRawByNorm.putIfAbsent(OpenApiPathNormalizer.normalizePath(k), k));
        }

        Set<String> allNorm = new TreeSet<>(skelRawByNorm.keySet());
        allNorm.addAll(manRawByNorm.keySet());

        for (String norm : allNorm) {
            String skelKey = skelRawByNorm.get(norm);
            String manKey = manRawByNorm.get(norm);

            ObjectNode skelNode = skelKey != null ? (ObjectNode) skeleton.get(skelKey) : null;
            ObjectNode manNode = manKey != null ? (ObjectNode) manual.get(manKey) : null;
            String outKey = skelKey != null ? skelKey : manKey;

            if (skelNode != null && manNode != null) {
                result.set(outKey, mergeNodes(skelNode, manNode, "paths." + outKey));
            } else if (skelNode != null) {
                ObjectNode marked = skelNode.deepCopy();
                marked.put("x-generated", true);
                result.set(outKey, marked);
                addedCount++;
            } else {
                orphanedCount++;
                orphanedPaths.add(outKey);
            }
        }
        return result;
    }

    private ArrayNode mergeTags(ArrayNode skeleton, ArrayNode manual, String jsonPath) {
        FieldPrecedence precedence = resolvePrecedence("tags");
        ArrayNode result = yamlMapper.createArrayNode();

        // Handle operation-level tags (string arrays)
        if ((skeleton != null && !skeleton.isEmpty() && skeleton.get(0).isTextual())
                || (manual != null && !manual.isEmpty() && manual.get(0).isTextual())) {

            if (precedence == FieldPrecedence.MANUAL) {
                if (manual != null) {
                    return (ArrayNode) manual.deepCopy();
                }
                return result;
            } else if (precedence == FieldPrecedence.SKELETON) {
                if (skeleton != null) {
                    return (ArrayNode) skeleton.deepCopy();
                }
                return result;
            } else { // MERGE_RECURSIVELY
                Set<String> uniqueTags = new LinkedHashSet<>();
                if (skeleton != null) {
                    skeleton.forEach(node -> uniqueTags.add(node.asText()));
                }
                if (manual != null) {
                    manual.forEach(node -> uniqueTags.add(node.asText()));
                }
                uniqueTags.forEach(result::add);
                return result;
            }
        }

        // Handle top-level tags (object arrays with "name" field)
        if (precedence == FieldPrecedence.MANUAL) {
            if (manual != null) {
                return (ArrayNode) manual.deepCopy();
            }
            return result;
        } else if (precedence == FieldPrecedence.SKELETON) {
            if (skeleton != null) {
                return (ArrayNode) skeleton.deepCopy();
            }
            return result;
        } else { // MERGE_RECURSIVELY
            Map<String, JsonNode> manualTagsByName = new LinkedHashMap<>();
            if (manual != null) {
                manual.forEach(tagNode -> {
                    if (tagNode.isObject() && tagNode.has("name")) {
                        manualTagsByName.put(tagNode.get("name").asText(), tagNode);
                    }
                });
            }

            Set<String> mergedTagNames = new HashSet<>();

            if (skeleton != null) {
                for (JsonNode skelTag : skeleton) {
                    if (skelTag.isObject() && skelTag.has("name")) {
                        String tagName = skelTag.get("name").asText();
                        JsonNode manTag = manualTagsByName.get(tagName);

                        if (manTag != null) {
                            result.add(mergeNodes((ObjectNode) skelTag, (ObjectNode) manTag, jsonPath + "." + tagName));
                        } else {
                            result.add(skelTag.deepCopy());
                        }
                        mergedTagNames.add(tagName);
                    } else {
                        result.add(skelTag.deepCopy());
                    }
                }
            }

            if (manual != null) {
                manual.forEach(tagNode -> {
                    if (tagNode.isObject() && tagNode.has("name")) {
                        String tagName = tagNode.get("name").asText();
                        if (!mergedTagNames.contains(tagName)) {
                            result.add(tagNode.deepCopy());
                        }
                    } else if (!tagNode.isObject() || !tagNode.has("name")) {
                        result.add(tagNode.deepCopy());
                    }
                });
            }

            return result;
        }
    }

    private ObjectNode mergeSchemas(ObjectNode skeleton, ObjectNode manual) {
        ObjectNode result = yamlMapper.createObjectNode();
        Set<String> keys = new TreeSet<>();
        if (skeleton != null) {
            skeleton.fieldNames().forEachRemaining(keys::add);
        }
        if (manual != null) {
            manual.fieldNames().forEachRemaining(keys::add);
        }

        for (String key : keys) {
            ObjectNode skelNode = skeleton != null && skeleton.has(key) ? (ObjectNode) skeleton.get(key) : null;
            ObjectNode manNode = manual != null && manual.has(key) ? (ObjectNode) manual.get(key) : null;

            if (skelNode != null && manNode != null) {
                result.set(key, mergeNodes(skelNode, manNode, "components.schemas." + key));
            } else if (skelNode != null) {
                ObjectNode marked = skelNode.deepCopy();
                marked.put("x-generated", true);
                result.set(key, marked);
                addedCount++;
            } else {
                orphanedCount++;
                orphanedSchemas.add(key);
            }
        }
        return result;
    }

    private ObjectNode mergeResponses(ObjectNode skeleton, ObjectNode manual, String jsonPath) {
        ObjectNode result = yamlMapper.createObjectNode();
        List<String> codes = new ArrayList<>();
        if (skeleton != null) {
            skeleton.fieldNames().forEachRemaining(codes::add);
        }
        if (manual != null) {
            manual.fieldNames().forEachRemaining(c -> {
                if (!codes.contains(c)) {
                    codes.add(c);
                }
            });
        }

        codes.sort((c1, c2) -> {
            if ("default".equals(c1)) {
                return 1;
            }
            if ("default".equals(c2)) {
                return -1;
            }
            return c1.compareTo(c2);
        });

        for (String code : codes) {
            JsonNode skelNode = skeleton != null ? skeleton.get(code) : null;
            JsonNode manNode = manual != null ? manual.get(code) : null;

            if (skelNode != null && manNode != null && skelNode.isObject() && manNode.isObject()) {
                result.set(code, mergeNodes((ObjectNode) skelNode, (ObjectNode) manNode, jsonPath + "." + code));
            } else {
                result.set(code, skelNode != null ? skelNode.deepCopy() : manNode.deepCopy());
            }
        }
        return result;
    }

    private ArrayNode mergeParameters(ArrayNode skeleton, ArrayNode manual, String jsonPath) {
        ArrayNode result = yamlMapper.createArrayNode();
        Map<String, JsonNode> manByKey = new LinkedHashMap<>();

        if (manual != null) {
            manual.forEach(n -> {
                if (n.isObject() && n.has("name")) {
                    manByKey.put(paramKey(n), n);
                }
            });
        }

        Set<String> merged = new HashSet<>();
        int i = 0;
        if (skeleton != null) {
            for (JsonNode skelNode : skeleton) {
                if (skelNode.isObject() && skelNode.has("name")) {
                    String key = paramKey(skelNode);
                    JsonNode manNode = manByKey.get(key);
                    if (manNode != null) {
                        result.add(mergeNodes((ObjectNode) skelNode, (ObjectNode) manNode, jsonPath + "[" + i + "]"));
                        merged.add(key);
                    } else {
                        result.add(skelNode.deepCopy());
                    }
                } else {
                    result.add(skelNode.deepCopy());
                }
                i++;
            }
        }

        if (manual != null) {
            manual.forEach(n -> {
                if (n.isObject() && n.has("name") && !merged.contains(paramKey(n))) {
                    result.add(n.deepCopy());
                } else if (!n.isObject() || !n.has("name")) {
                    result.add(n.deepCopy());
                }
            });
        }
        return result;
    }

    private ArrayNode mergeSchemaArrays(ArrayNode skeleton, ArrayNode manual, String jsonPath) {
        ArrayNode result = yamlMapper.createArrayNode();
        Map<String, JsonNode> manByRef = new HashMap<>();

        if (manual != null) {
            manual.forEach(n -> {
                if (n.isObject() && n.has("$ref")) {
                    manByRef.put(n.get("$ref").asText(), n);
                }
            });
        }

        int i = 0;
        if (skeleton != null) {
            for (JsonNode skelNode : skeleton) {
                if (skelNode.isObject() && skelNode.has("$ref")) {
                    String ref = skelNode.get("$ref").asText();
                    JsonNode manNode = manByRef.remove(ref);
                    if (manNode != null) {
                        result.add(mergeNodes((ObjectNode) skelNode, (ObjectNode) manNode, jsonPath + "[" + i + "]"));
                    } else {
                        result.add(skelNode.deepCopy());
                    }
                } else {
                    result.add(skelNode.deepCopy());
                }
                i++;
            }
        }

        manByRef.values().forEach(n -> result.add(n.deepCopy()));
        return result;
    }

    private ArrayNode mergeArrays(ArrayNode skeleton, ArrayNode manual, String jsonPath) {
        if (skeleton.isEmpty()) {
            return manual.deepCopy();
        }
        if (manual.isEmpty()) {
            return skeleton.deepCopy();
        }

        if (skeleton.get(0).isObject() && manual.get(0).isObject() && skeleton.get(0).has("name")) {
            return mergeObjectArrays(skeleton, manual, jsonPath);
        }

        if (skeleton.get(0).isContainerNode() || manual.get(0).isContainerNode()) {
            return skeleton.deepCopy();
        }

        ArrayNode result = yamlMapper.createArrayNode();
        List<JsonNode> seen = new ArrayList<>();
        skeleton.forEach(n -> {
            if (addIfNotSeen(seen, n)) {
                result.add(n.deepCopy());
            }
        });
        manual.forEach(n -> {
            if (addIfNotSeen(seen, n)) {
                result.add(n.deepCopy());
            }
        });
        return result;
    }

    private ArrayNode mergeObjectArrays(ArrayNode skeleton, ArrayNode manual, String jsonPath) {
        ArrayNode result = yamlMapper.createArrayNode();
        Map<String, ObjectNode> manualByKey = new LinkedHashMap<>();

        manual.forEach(node -> {
            if (node.isObject() && node.has("name")) {
                manualByKey.put(paramKey(node), (ObjectNode) node);
            }
        });

        Set<String> mergedKeys = new HashSet<>();
        int i = 0;
        for (JsonNode skelNode : skeleton) {
            if (skelNode.isObject() && skelNode.has("name")) {
                String key = paramKey(skelNode);
                ObjectNode manNode = manualByKey.get(key);
                if (manNode != null) {
                    result.add(mergeNodes((ObjectNode) skelNode, manNode, jsonPath + "[" + i + "]"));
                    mergedKeys.add(key);
                } else {
                    result.add(skelNode.deepCopy());
                }
            } else {
                result.add(skelNode.deepCopy());
            }
            i++;
        }

        manual.forEach(node -> {
            if (node.isObject() && node.has("name")) {
                String key = paramKey(node);
                if (!mergedKeys.contains(key)) {
                    result.add(node.deepCopy());
                }
            } else if (!node.isObject() || !node.has("name")) {
                result.add(node.deepCopy());
            }
        });

        return result;
    }

    private String paramKey(JsonNode node) {
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

    private boolean addIfNotSeen(List<JsonNode> seen, JsonNode node) {
        if (seen.stream().noneMatch(n -> n.equals(node))) {
            seen.add(node);
            return true;
        }
        return false;
    }

    private void stripMarkers(JsonNode node) {
        if (node.isObject()) {
            ((ObjectNode) node).remove("x-orphaned");
            ((ObjectNode) node).remove("x-generated");
        }
        for (JsonNode child : node) {
            stripMarkers(child);
        }
    }

    private ObjectNode mergeContent(ObjectNode skeleton, ObjectNode manual, String jsonPath) {
        ObjectNode result = yamlMapper.createObjectNode();

        Set<String> allMediaTypes = new TreeSet<>();
        if (skeleton != null) {
            skeleton.fieldNames().forEachRemaining(allMediaTypes::add);
        }
        if (manual != null) {
            manual.fieldNames().forEachRemaining(allMediaTypes::add);
        }

        for (String mediaType : allMediaTypes) {
            JsonNode skelMedia = skeleton != null ? skeleton.get(mediaType) : null;
            JsonNode manMedia = manual != null ? manual.get(mediaType) : null;
            String childPath = jsonPath + "." + mediaType;

            if (skelMedia != null && manMedia != null && skelMedia.isObject() && manMedia.isObject()) {
                result.set(mediaType, mergeNodes((ObjectNode) skelMedia, (ObjectNode) manMedia, childPath));
            } else {
                result.set(mediaType, skelMedia != null ? skelMedia.deepCopy() : manMedia.deepCopy());
            }
        }
        return result;
    }
}