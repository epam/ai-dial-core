package com.epam.aidial.cli.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pre-parse string rewrite of YAML {@code !if}/{@code !for} tags into sentinel keys
 * ({@code __if__ <expr>} / {@code __for__ ...}), then a standard {@link YAMLMapper}
 * parse, followed by a post-parse tree walk that expands the sentinels using a
 * {@link PlaceholderSubstitutor} and an {@link ExpressionEvaluator}.
 *
 * <p>YAML source-position fidelity is lost on rewritten lines — the trade-off for
 * keeping the parser standard and the dependency surface small.
 */
public final class ControlFlowExpander {

    static final String IF_SENTINEL = "__if__";
    static final String FOR_SENTINEL = "__for__";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final YAMLMapper YAML = new YAMLMapper();

    // Match an indented line of the form: "<indent>!if <expr>:<trailing>". The expression
    // may contain quoted strings; we capture everything up to the trailing ':'.
    private static final Pattern IF_LINE = Pattern.compile(
            "^(\\s*)!if\\s+(.+?):\\s*$");
    // Match: "<indent>!for <inline-flow-map>:<trailing>". The flow map may embed '${...}'
    // placeholders so we balance braces explicitly rather than excluding '}' from the body.
    private static final Pattern FOR_LINE = Pattern.compile(
            "^(\\s*)!for\\s+(\\{.*\\}):\\s*$");

    private ControlFlowExpander() {
    }

    /**
     * Pre-parse YAML rewrite. Substitutes sentinel keys for {@code !if}/{@code !for} lines.
     */
    public static String rewriteYaml(String yaml) {
        if (yaml == null || yaml.isEmpty()) {
            return yaml;
        }
        StringBuilder out = new StringBuilder(yaml.length() + 32);
        for (String line : yaml.split("\n", -1)) {
            Matcher ifMatch = IF_LINE.matcher(line);
            if (ifMatch.matches()) {
                String indent = ifMatch.group(1);
                String expr = stripWrappingYamlQuotes(ifMatch.group(2).trim());
                // Encode expression as a single-line key value. Use YAML-safe quoting: wrap in
                // double quotes and escape any embedded double quote / backslash.
                String safe = yamlEscape(expr);
                out.append(indent).append('"').append(IF_SENTINEL).append(' ').append(safe).append("\":\n");
                continue;
            }
            Matcher forMatch = FOR_LINE.matcher(line);
            if (forMatch.matches()) {
                String indent = forMatch.group(1);
                String spec = forMatch.group(2).trim();
                String safe = yamlEscape(spec);
                out.append(indent).append('"').append(FOR_SENTINEL).append(' ').append(safe).append("\":\n");
                continue;
            }
            out.append(line).append('\n');
        }
        // Strip the trailing newline we always add to keep parity with the input.
        if (out.length() > 0 && out.charAt(out.length() - 1) == '\n'
                && (yaml.isEmpty() || yaml.charAt(yaml.length() - 1) != '\n')) {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }

    private static String yamlEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // Operators often wrap an !if expression in double quotes so YAML accepts a value that
    // begins with '${...}' (e.g. !if "${vars.x} != ''":). Those outer quotes are for YAML's
    // sake — the expression grammar uses single-quoted literals — so strip them before the
    // sentinel is built. Only strip when the pair spans the whole expression: the first '"'
    // sits at index 0, its naive close is at the last index, and there is no interior '"'.
    // This rejects ambiguous forms like "${x}" != "y" where the leading/trailing quotes are
    // separate operand boundaries.
    private static String stripWrappingYamlQuotes(String expr) {
        if (expr.length() < 2 || expr.charAt(0) != '"' || expr.charAt(expr.length() - 1) != '"') {
            return expr;
        }
        if (expr.indexOf('"', 1) != expr.length() - 1) {
            return expr;
        }
        return expr.substring(1, expr.length() - 1);
    }

    /**
     * Post-parse expansion. Walks {@code node} and replaces any {@code __if__}/{@code __for__}
     * sentinel keys by evaluating their guard / loop binding under {@code ctx}.
     */
    static JsonNode expand(JsonNode node, Map<String, Object> ctx) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return node;
        }
        if (node.isObject()) {
            return expandObject((ObjectNode) node, ctx);
        }
        if (node.isArray()) {
            return expandArray((ArrayNode) node, ctx);
        }
        return node;
    }

    private static JsonNode expandObject(ObjectNode obj, Map<String, Object> ctx) {
        // Single-key '!for' as the only mapping key collapses the parent into the expanded
        // array (design 05 §3.3 — 'upstreams: !for ...:' produces 'upstreams: [...]').
        if (obj.size() == 1) {
            String only = obj.fieldNames().next();
            if (only.startsWith(FOR_SENTINEL + " ")) {
                ArrayNode out = MAPPER.createArrayNode();
                expandFor(only, obj.get(only), ctx, out);
                return out;
            }
        }
        ObjectNode out = MAPPER.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            String key = entry.getKey();
            JsonNode value = entry.getValue();

            if (key.startsWith(IF_SENTINEL + " ")) {
                String expr = key.substring(IF_SENTINEL.length() + 1);
                if (new ExpressionEvaluator(ctx).evaluate(expr)) {
                    JsonNode expanded = expand(value, ctx);
                    if (expanded.isObject()) {
                        TemplateComposer.deepMerge(out, (ObjectNode) expanded);
                    } else {
                        throw new TemplateException("'!if' body must be a mapping; got "
                                + expanded.getNodeType());
                    }
                }
                continue;
            }

            if (key.startsWith(FOR_SENTINEL + " ")) {
                throw new TemplateException("'!for' may not appear alongside other keys in a mapping; "
                        + "use it as the sole key so the parent collapses to an array");
            }

            out.set(key, expand(value, ctx));
        }
        return out;
    }

    private static JsonNode expandArray(ArrayNode arr, Map<String, Object> ctx) {
        ArrayNode out = MAPPER.createArrayNode();
        for (JsonNode element : arr) {
            if (element.isObject()) {
                ObjectNode elObj = (ObjectNode) element;
                String forKey = findSentinelKey(elObj, FOR_SENTINEL);
                if (forKey != null && elObj.size() == 1) {
                    expandFor(forKey, elObj.get(forKey), ctx, out);
                    continue;
                }
            }
            JsonNode expanded = expand(element, ctx);
            out.add(expanded);
        }
        return out;
    }

    private static void expandFor(String forKey, JsonNode body, Map<String, Object> ctx, ArrayNode out) {
        String spec = forKey.substring(FOR_SENTINEL.length() + 1);
        ForBinding b = parseForSpec(spec);
        Object listObj = resolveList(b.in, ctx);
        if (listObj == null) {
            return;
        }
        if (!(listObj instanceof List<?> list)) {
            throw new TemplateException("'!for' input '" + b.in + "' must resolve to a list; got "
                    + listObj.getClass().getSimpleName());
        }
        for (Object element : list) {
            Map<String, Object> child = new HashMap<>(ctx);
            child.put(b.as, element);
            JsonNode expanded = expand(body, child);
            // Substitute now while the loop binding is still in scope. Outer placeholders
            // (vars/params/entity) get resolved on this pass too — that's safe because they
            // are stable across iterations, and a later top-level substitute pass is a no-op.
            JsonNode resolved = substitutePlaceholders(expanded, child);
            if (resolved.isArray()) {
                resolved.forEach(out::add);
            } else {
                out.add(resolved);
            }
        }
    }

    private static String findSentinelKey(ObjectNode obj, String prefix) {
        Iterator<String> names = obj.fieldNames();
        while (names.hasNext()) {
            String n = names.next();
            if (n.startsWith(prefix + " ")) {
                return n;
            }
        }
        return null;
    }

    /**
     * Parse the inline body of a {@code !for { in: ..., as: ... }} key. Tolerates either the
     * exact YAML shape it had on the original line or a JSON-ish flow rendering — both end up
     * in the same {@code __for__ "{ ... }"} sentinel string.
     */
    private static ForBinding parseForSpec(String raw) {
        try {
            JsonNode parsed = YAML.readTree(raw);
            if (parsed == null || !parsed.isObject() || !parsed.has("in") || !parsed.has("as")) {
                throw new TemplateException("'!for' must be '{ in: <list>, as: <var> }'; got: " + raw);
            }
            return new ForBinding(parsed.get("in").asText(), parsed.get("as").asText());
        } catch (TemplateException te) {
            throw te;
        } catch (Exception e) {
            throw new TemplateException("Invalid '!for' specifier '" + raw + "': " + e.getMessage());
        }
    }

    private static Object resolveList(String inExpr, Map<String, Object> ctx) {
        if (inExpr.startsWith("${") && inExpr.endsWith("}")) {
            String path = inExpr.substring(2, inExpr.length() - 1).trim();
            String[] parts = path.split("\\.", -1);
            if (parts.length < 2) {
                throw new TemplateException("'!for' input must be a 'namespace.key' path; got: " + inExpr);
            }
            Object scope = ctx.get(parts[0]);
            if (!(scope instanceof Map<?, ?> map)) {
                throw new TemplateException("Unknown placeholder namespace '" + parts[0]
                        + "' in '!for' input '" + inExpr + "'.");
            }
            Object cursor = map;
            for (int i = 1; i < parts.length; i++) {
                if (!(cursor instanceof Map<?, ?> mm) || !mm.containsKey(parts[i])) {
                    throw new TemplateException("Missing '!for' input value: '" + inExpr + "'.");
                }
                cursor = mm.get(parts[i]);
            }
            return cursor;
        }
        throw new TemplateException("'!for' input must be a '${...}' placeholder; got: " + inExpr);
    }

    /** Walk a tree and resolve every {@code ${...}} placeholder in string leaves. */
    static JsonNode substitutePlaceholders(JsonNode node, Map<String, Object> ctx) {
        return substitutePlaceholders(node, new PlaceholderSubstitutor(ctx));
    }

    private static JsonNode substitutePlaceholders(JsonNode node, PlaceholderSubstitutor sub) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return node;
        }
        if (node.isTextual()) {
            String resolved = sub.substitute(node.asText());
            return MAPPER.getNodeFactory().textNode(resolved);
        }
        if (node.isObject()) {
            ObjectNode out = MAPPER.createObjectNode();
            // Preserve order using a LinkedHashMap-ish iteration.
            Map<String, JsonNode> ordered = new LinkedHashMap<>();
            node.fields().forEachRemaining(e -> ordered.put(e.getKey(), e.getValue()));
            for (Map.Entry<String, JsonNode> e : ordered.entrySet()) {
                out.set(e.getKey(), substitutePlaceholders(e.getValue(), sub));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = MAPPER.createArrayNode();
            for (JsonNode element : node) {
                out.add(substitutePlaceholders(element, sub));
            }
            return out;
        }
        return node;
    }

    private record ForBinding(String in, String as) { }
}
