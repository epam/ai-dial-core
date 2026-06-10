package com.epam.aidial.cli.service.template;

import com.epam.aidial.cli.exception.TemplateException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.Map;

/**
 * Public entry point for the template DSL pipeline. Pure transformation:
 * {@link JsonNode} in, {@link JsonNode} out — no I/O, no HTTP, no live linking.
 *
 * <p>Pipeline (per template-named manifest):
 * <ol>
 *     <li>{@link TemplateComposer} — resolve {@code extends}/{@code includes} into an
 *         effective {@code fields} block.</li>
 *     <li>{@link ControlFlowExpander#expand(JsonNode, Map)} — expand
 *         {@code !if}/{@code !for} sentinels (already rewritten by the loader) using
 *         the merged {@code vars}/{@code params}/{@code entity} context.</li>
 *     <li>{@code substitutePlaceholders} — resolve {@code ${...}} placeholders in every
 *         string leaf.</li>
 *     <li>Deep-merge the spec on top of the resolved template fields (spec wins).</li>
 * </ol>
 *
 * <p>For raw-spec manifests (no template name), only steps 2-3 run on the spec itself,
 * giving raw specs zero-cost backward compatibility plus optional placeholder/control-flow.
 */
public final class TemplateResolver {

    static final String NS_VARS = "vars";
    static final String NS_PARAMS = "params";
    static final String NS_ENTITY = "entity";
    static final String SECRET_PREFIX = "SECRET:";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TemplateResolver() {
    }

    /**
     * Resolve a (possibly templated) raw spec into a fully self-contained JSON tree.
     *
     * @param rawSpec the raw {@code spec:} JSON tree from the manifest envelope
     * @param tpl resolution inputs ({@code templateName}, {@code params}, {@code vars},
     *            {@code entityCtx}, {@code templates}); none of the maps may be {@code null}-keys
     * @return a resolved {@link JsonNode} (object) ready for serialization
     * @throws TemplateException on any resolution failure (missing var, unknown function,
     *         malformed expression, cyclic extends, unknown template name, ...)
     */
    public static JsonNode resolve(JsonNode rawSpec, TemplateContext tpl) {
        Map<String, Object> ctx = buildContext(tpl);
        JsonNode resolvedSpec = expandTree(rawSpec, ctx);

        String templateName = tpl.templateName();
        if (templateName == null || templateName.isBlank()) {
            return resolvedSpec;
        }

        ObjectNode templateFields = new TemplateComposer(tpl.templates()).compose(templateName);
        JsonNode expandedTemplate = ControlFlowExpander.expand(templateFields, ctx);
        JsonNode resolvedTemplate = ControlFlowExpander.substitutePlaceholders(expandedTemplate, ctx);

        return TemplateComposer.deepMergeNodes(resolvedTemplate, resolvedSpec);
    }

    /** Public seam for callers that need a template-wins merge (e.g. promote). */
    public static JsonNode deepMerge(JsonNode base, JsonNode overlay) {
        return TemplateComposer.deepMergeNodes(base, overlay);
    }

    /**
     * Resolve a named template against {@code tpl}'s scopes and return only the resolved
     * {@code fields} block — no spec merge. Inverse of {@link #resolve}'s spec-wins semantics
     * Used by promote where the template overrides source.
     */
    public static JsonNode resolveTemplate(TemplateContext tpl) {
        String templateName = tpl.templateName();
        if (templateName == null || templateName.isBlank()) {
            throw new TemplateException("resolveTemplate requires a non-empty templateName");
        }
        Map<String, Object> ctx = buildContext(tpl);
        ObjectNode templateFields = new TemplateComposer(tpl.templates()).compose(templateName);
        JsonNode expanded = ControlFlowExpander.expand(templateFields, ctx);
        return ControlFlowExpander.substitutePlaceholders(expanded, ctx);
    }

    private static JsonNode expandTree(JsonNode tree, Map<String, Object> ctx) {
        if (tree == null || tree.isMissingNode() || tree.isNull()) {
            return MAPPER.createObjectNode();
        }
        JsonNode expanded = ControlFlowExpander.expand(tree, ctx);
        return ControlFlowExpander.substitutePlaceholders(expanded, ctx);
    }

    /**
     * Resolve {@code ${...}} placeholders in a plain string using only the params + vars scopes.
     * The entity scope is intentionally empty — callers use this to resolve the entity name
     * itself before the entity context exists.
     */
    public static String resolveString(String raw, Map<String, Object> params, Map<String, Object> vars) {
        if (raw == null || !raw.contains("${")) {
            return raw;
        }
        var ctx = buildContext(vars, params, null);
        return new PlaceholderSubstitutor(ctx).substitute(raw);
    }

    private static Map<String, Object> buildContext(TemplateContext tpl) {
        return buildContext(tpl.vars(), tpl.params(), tpl.entityCtx());
    }

    private static Map<String, Object> buildContext(Map<String, Object> vars, Map<String, Object> params, Map<String, Object> entityCtx) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put(NS_VARS, vars == null ? Map.of() : vars);
        ctx.put(NS_PARAMS, params == null ? Map.of() : params);
        ctx.put(NS_ENTITY, entityCtx == null ? Map.of() : entityCtx);
        return ctx;
    }

}
