package com.epam.aidial.cli.service.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;

/**
 * RFC 7396 JSON Merge Patch — pure, non-mutating.
 *
 * <p>If the patch is not an object, it replaces the target wholesale. If both are objects,
 * each field in the patch with a {@code null} value removes the matching field from the target;
 * every other field is recursively merged. Arrays are atomic (full replacement).
 */
public final class JsonMergePatch {

    private JsonMergePatch() {
    }

    public static JsonNode apply(JsonNode target, JsonNode patch) {
        if (patch == null || patch.isMissingNode()) {
            return target;
        }
        if (!patch.isObject()) {
            return patch.deepCopy();
        }
        ObjectNode patchObj = (ObjectNode) patch;
        ObjectNode result = (target != null && target.isObject())
                ? ((ObjectNode) target).deepCopy()
                : patchObj.objectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = patchObj.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> e = fields.next();
            JsonNode value = e.getValue();
            if (value.isNull()) {
                result.remove(e.getKey());
            } else {
                result.set(e.getKey(), apply(result.get(e.getKey()), value));
            }
        }
        return result;
    }
}
