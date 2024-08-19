package com.epam.aidial.core.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.experimental.UtilityClass;

import java.util.ArrayDeque;
import java.util.Map;

@UtilityClass
public class MergeChunks {

    public static final String LIST_OF_DICTS_ERROR_MESSAGE = "Lists could be merged only if their elements are dictionaries";

    public static final String INDEX_ERROR_MESSAGE = "A list element must have 'index' field to identify position of the element in the list";

    public static final String INCONSISTENT_INDEXED_LIST_ERROR_MESSAGE = "All elements of a list must be either indexed or not indexed";


    public static final String CANNOT_MERGE_NON_INDEXED_LIST_ERROR_MESSAGE = "Cannot merge non-indexed array";


    public JsonNode merge(JsonNode target, JsonNode source, ArrayDeque<String> path) {
        if (source == null || source.isNull()) {
            return target;
        }
        if (target == null || target.isNull()) {
            return source;
        }
        if (target.isArray() && source.isArray()) {
            return mergeArrays((ArrayNode) target, (ArrayNode) source, path);
        } else if (target.isObject() && source.isObject()) {
            return mergeObjects((ObjectNode) target, (ObjectNode) source, path);
        } else if (target.isIntegralNumber() && source.isIntegralNumber()) {
            return source;
        } else if (target.isFloatingPointNumber() && source.isFloatingPointNumber()) {
            return source;
        } else if (target.isBoolean() && source.isBoolean()) {
            return source;
        } else if (target.getNodeType() == JsonNodeType.STRING && source.getNodeType() == JsonNodeType.STRING) {
            String text = target.textValue() + source.textValue();
            return new TextNode(text);
        }

        throw new IllegalArgumentException(String.format("Can't merge %s into %s at path %s", source.asText(), target.asText(), path.toString()));
    }

    private JsonNode mergeObjects(ObjectNode target, ObjectNode source, ArrayDeque<String> path) {
        for (Map.Entry<String, JsonNode> entry : source.properties()) {
            String name = entry.getKey();
            path.addFirst(name);
            target.set(name, merge(target.get(name), entry.getValue(), path));
            path.removeFirst();
        }
        return target;
    }


    private ArrayNode mergeArrays(ArrayNode target, ArrayNode source, ArrayDeque<String> path) {
        if (source.isEmpty()) {
            return target;
        }
        boolean isSourceIndexed = isIndexedArray(source);
        if (target.isEmpty()) {
            if (isSourceIndexed) {
                return mergeIndexedArrays(target, source, path);
            } else {
                return source;
            }
        }
        boolean isTargetIndexed = isIndexedArray(target);
        if (!isTargetIndexed || !isSourceIndexed) {
            throw new IllegalArgumentException(CANNOT_MERGE_NON_INDEXED_LIST_ERROR_MESSAGE);
        }

        return mergeIndexedArrays(target, source, path);

    }

    private ArrayNode mergeIndexedArrays(ArrayNode target, ArrayNode source, ArrayDeque<String> path) {
        for (JsonNode elem : source) {
            if (!elem.isObject()) {
                throw new IllegalArgumentException(LIST_OF_DICTS_ERROR_MESSAGE);
            }
            JsonNode indexNode = elem.get("index");
            if (!indexNode.isInt()) {
                throw new IllegalArgumentException(INDEX_ERROR_MESSAGE);
            }
            int index = indexNode.asInt();
            if (index < target.size()) {
                merge(target.get(index), elem, path);
            } else {
                for (int i = target.size(); i < index; i++) {
                    ObjectNode objectNode = ProxyUtil.MAPPER.createObjectNode();
                    objectNode.put("index", i);
                }
                target.add(elem);
            }
        }
        return target;
    }

    private boolean isIndexedArray(ArrayNode array) {
        if (array.isEmpty()) {
            return false;
        }
        boolean allIndexed = true;
        boolean anyIndexed = false;

        for (JsonNode node : array) {
            if (node.isObject() && node.has("index")) {
                anyIndexed = true;
            } else {
                allIndexed = false;
            }
        }

        if (anyIndexed && !allIndexed) {
            throw new IllegalArgumentException(INCONSISTENT_INDEXED_LIST_ERROR_MESSAGE);
        }
        return allIndexed;
    }
}
