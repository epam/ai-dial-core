package com.epam.aidial.cli;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

public final class JsonPatcher {

    private JsonPatcher() {
    }

    public static void apply(ObjectNode target, Map<String, JsonNode> sets) {
        if (sets == null) {
            return;
        }
        for (Map.Entry<String, JsonNode> entry : sets.entrySet()) {
            String pathExpr = entry.getKey();
            JsonNode value = entry.getValue();
            for (String segment : pathExpr.split("\\.", -1)) {
                if (segment.isEmpty()) {
                    throw CliException.validation("--set path must not contain empty segments; got '" + pathExpr + "'.");
                }
            }
            JsonPointer pointer = JsonPointer.compile("/" + pathExpr.replace(".", "/"));
            JsonPointer head = pointer.head();
            ObjectNode parent;
            if (head == null || head.matches()) {
                parent = target;
            } else {
                try {
                    parent = target.withObject(head);
                } catch (UnsupportedOperationException e) {
                    throw CliException.validation("--set path '" + pathExpr
                            + "' would overwrite a non-object value at an intermediate segment.");
                }
            }
            parent.set(pointer.last().getMatchingProperty(), value);
        }
    }
}
