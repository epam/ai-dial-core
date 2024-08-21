package com.epam.aidial.core.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.Map;
import java.util.Stack;

@UtilityClass
public class MergeChunks {

    public static final String LIST_OF_DICTS_ERROR_MESSAGE = "Lists could be merged only if their elements are dictionaries";

    public static final String INDEX_ERROR_MESSAGE = "A list element must have 'index' field to identify position of the element in the list";

    public static final String INCONSISTENT_INDEXED_LIST_ERROR_MESSAGE = "All elements of a list must be either indexed or not indexed";


    public static final String CANNOT_MERGE_NON_INDEXED_LIST_ERROR_MESSAGE = "Cannot merge non-indexed array";

    public JsonNode merge(List<JsonNode> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return null;
        }
        JsonNode res = chunks.get(0);
        for (int i = 1; i < chunks.size(); i++) {
            res = merge(res, chunks.get(i), new Stack<>());
        }
        removeIndices(res, false);
        return res;
    }

    public JsonNode merge(JsonNode target, JsonNode source) {
        return merge(target, source, new Stack<>());
    }

    private JsonNode merge(JsonNode target, JsonNode source, Stack<String> path) {
        if (source == null || source.isNull()) {
            return target;
        }
        if (target == null || target.isNull()) {
            if (source.isObject()) {
                target = ProxyUtil.MAPPER.createObjectNode();
            } else if (source.isArray()) {
                target = ProxyUtil.MAPPER.createArrayNode();
            } else {
                return source;
            }
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

        throw new IllegalArgumentException(String.format("Can't merge %s into %s at path %s", source.asText(), target.asText(), toPath(path)));
    }

    private static String toPath(Stack<String> path) {
        StringBuilder sb = new StringBuilder();
        for (var elem : path) {
            if (!sb.isEmpty()) {
                sb.append('.');
            }
            sb.append(elem);
        }
        return sb.toString();
    }

    private JsonNode mergeObjects(ObjectNode target, ObjectNode source, Stack<String> path) {
        for (Map.Entry<String, JsonNode> entry : source.properties()) {
            String name = entry.getKey();
            path.push(name);
            target.set(name, merge(target.get(name), entry.getValue(), path));
            path.pop();
        }
        return target;
    }


    private ArrayNode mergeArrays(ArrayNode target, ArrayNode source, Stack<String> path) {
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

    private ArrayNode mergeIndexedArrays(ArrayNode target, ArrayNode source, Stack<String> path) {
        for (int i = 0; i < source.size(); i++) {
            JsonNode elem = source.get(i);
            if (!elem.isObject()) {
                throw new IllegalArgumentException(LIST_OF_DICTS_ERROR_MESSAGE);
            }
            JsonNode indexNode = elem.get("index");
            if (!indexNode.isInt()) {
                throw new IllegalArgumentException(INDEX_ERROR_MESSAGE);
            }
            int index = indexNode.asInt();
            if (index < target.size()) {
                path.push("[" + i + "]");
                merge(target.get(index), elem, path);
                path.pop();
            } else {
                for (int j = target.size(); j < index; j++) {
                    ObjectNode objectNode = ProxyUtil.MAPPER.createObjectNode();
                    objectNode.put("index", j);
                    target.add(objectNode);
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

    public void removeIndices(JsonNode node) {
        removeIndices(node, false);
    }

    private void removeIndices(JsonNode node, boolean remove) {
        if (!node.isObject() && !node.isArray()) {
            return;
        }
        boolean isArray = node.isArray();
        if (!isArray) {
            if (remove) {
                ObjectNode objectNode = (ObjectNode) node;
                objectNode.remove("index");
            }
        }

        for (var child : node) {
            removeIndices(child, isArray);
        }
    }
}
