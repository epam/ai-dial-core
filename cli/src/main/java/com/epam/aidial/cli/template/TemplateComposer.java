package com.epam.aidial.cli.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Resolves a template's effective {@code fields} block by traversing its {@code extends}
 * chain (single parent, DFS) and {@code includes} list (mixins, in order), then deep-merging
 * the template's own {@code fields} on top. Cycles are detected via a name-set on the
 * extends DFS and rejected with a {@link TemplateException} that names both endpoints.
 *
 * <p>Per design 05 §3.2 the effective merge order, top to bottom, is:
 * <ol>
 *     <li>{@code extends} chain (outer-most parent first)</li>
 *     <li>{@code includes} (in listed order)</li>
 *     <li>The template's own {@code fields} block</li>
 * </ol>
 */
final class TemplateComposer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Object> templates;

    TemplateComposer(Map<String, Object> templates) {
        this.templates = (templates == null) ? Map.of() : templates;
    }

    /**
     * Returns the effective {@code fields} JSON for {@code templateName}, fully composed.
     * The returned node is always an {@link ObjectNode} (possibly empty).
     */
    ObjectNode compose(String templateName) {
        ObjectNode out = MAPPER.createObjectNode();
        composeInto(templateName, out, new LinkedHashSet<>());
        return out;
    }

    private void composeInto(String name, ObjectNode out, LinkedHashSet<String> stack) {
        if (stack.contains(name)) {
            throw new TemplateException("Template cycle: " + String.join(" → ", stack) + " → " + name);
        }
        Object raw = templates.get(name);
        if (raw == null) {
            throw new TemplateException("Unknown template: '" + name + "'");
        }
        if (!(raw instanceof Map<?, ?> def)) {
            throw new TemplateException("Template '" + name + "' must be a mapping; got "
                    + raw.getClass().getSimpleName());
        }
        stack.add(name);
        try {
            // 1. extends chain — parents are merged first.
            Object ext = def.get("extends");
            if (ext != null) {
                if (!(ext instanceof String parent)) {
                    throw new TemplateException("Template '" + name + "': 'extends' must be a string.");
                }
                composeInto(parent, out, stack);
            }
            // 2. includes — in listed order.
            Object inc = def.get("includes");
            if (inc instanceof List<?> list) {
                for (Object item : list) {
                    if (!(item instanceof String mixin)) {
                        throw new TemplateException("Template '" + name + "': 'includes' must be a list of strings.");
                    }
                    // Each mixin gets its own copy of the current chain. The copy keeps the
                    // composing-templates path so back-edges to any ancestor are caught as
                    // cycles, but lets sibling includes share-by-diamond a deeper template.
                    LinkedHashSet<String> mixinStack = new LinkedHashSet<>(stack);
                    composeInto(mixin, out, mixinStack);
                }
            }
            // 3. Own fields.
            Object fields = def.get("fields");
            if (fields != null) {
                JsonNode ownFields = MAPPER.valueToTree(fields);
                if (!ownFields.isObject()) {
                    throw new TemplateException("Template '" + name + "': 'fields' must be a mapping.");
                }
                deepMerge(out, (ObjectNode) ownFields);
            }
        } finally {
            stack.remove(name);
        }
    }

    /**
     * Recursive deep-merge: object values merge field-wise; arrays and scalars replace.
     * Mutates {@code target}.
     */
    static void deepMerge(ObjectNode target, ObjectNode source) {
        source.fieldNames().forEachRemaining(field -> {
            JsonNode incoming = source.get(field);
            JsonNode existing = target.get(field);
            if (existing != null && existing.isObject() && incoming.isObject()) {
                deepMerge((ObjectNode) existing, (ObjectNode) incoming);
            } else {
                target.set(field, incoming.deepCopy());
            }
        });
    }

    /** Convenience overload for arbitrary nodes — only object/object pairs deep-merge. */
    static JsonNode deepMergeNodes(JsonNode base, JsonNode overlay) {
        if (base instanceof ObjectNode baseObj && overlay instanceof ObjectNode overlayObj) {
            ObjectNode merged = baseObj.deepCopy();
            deepMerge(merged, overlayObj);
            return merged;
        }
        if (overlay == null || overlay.isMissingNode() || overlay.isNull()) {
            return base;
        }
        // Arrays and scalars replace.
        return overlay.deepCopy();
    }

}
