package com.epam.aidial.core.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import lombok.experimental.UtilityClass;

import java.util.ArrayDeque;

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

        } else if (target.isObject() && source.isObject()) {

        } else if (target.isIntegralNumber() && source.isIntegralNumber()) {

        } else if (target.isFloatingPointNumber() && source.isFloatingPointNumber()) {

        } else if (target.isBoolean() && source.isBoolean()) {

        } else if (target.getNodeType() == JsonNodeType.STRING && source.getNodeType() == JsonNodeType.STRING) {

        }

        throw new IllegalArgumentException(String.format("Can't merge %s into %s at path %s", source.asText(), target.asText(), path.toString()));
    }


    private ArrayNode mergeArrays(ArrayNode target, ArrayNode source, ArrayDeque<String> path) {
        if (source.isEmpty()) {
            return target;
        }
        boolean isSourceIndexed = isIndexedArray(source);
        if (target.isEmpty()) {
            if (isSourceIndexed) {

            } else {
                return source;
            }
        }
        boolean isTargetIndexed = isIndexedArray(target);
        if (!isTargetIndexed || !isSourceIndexed) {
            throw new IllegalArgumentException(CANNOT_MERGE_NON_INDEXED_LIST_ERROR_MESSAGE);
        }

    }

    /**
     * for elem in source:
     *         assert isinstance(elem, dict), LIST_OF_DICTS_ERROR_MESSAGE
     *
     *         index = elem.get("index")
     *         assert isinstance(index, int), INDEX_ERROR_MESSAGE
     *
     *         path.append(index)
     *
     *         if index < len(target):
     *             target[index] = merge_recursive(target[index], elem, path)
     *         else:
     *             target.extend([{"index": idx} for idx in range(len(target), index)])
     *             target.append(elem)
     *
     *         path.pop()
     *
     *     return target
     * @param target
     * @param source
     * @param path
     * @return
     */
    private ArrayNode mergeIndexedArrays(ArrayNode target, ArrayNode source, ArrayDeque<String> path) {
        for (JsonNode)
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
