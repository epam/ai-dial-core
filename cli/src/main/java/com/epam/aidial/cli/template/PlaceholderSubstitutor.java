package com.epam.aidial.cli.template;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Resolves {@code ${...}} placeholders inside string leaves. Supports three namespaces
 * ({@code vars}, {@code params}, {@code entity}) and the seven functions from {@link FunctionApplicator}.
 * The {@code SECRET:*} namespace is opt-in: if no {@code secretResolver} is provided
 * (4C.1 default), the placeholder is left unchanged for downstream resolution; if a resolver
 * is provided (4C.4 seam), it is called and the result is substituted.
 */
final class PlaceholderSubstitutor {

    private final Map<String, Object> ctx;
    private final Function<String, String> secretResolver;

    PlaceholderSubstitutor(Map<String, Object> ctx) {
        this(ctx, null);
    }

    PlaceholderSubstitutor(Map<String, Object> ctx, Function<String, String> secretResolver) {
        this.ctx = ctx;
        this.secretResolver = secretResolver;
    }

    /** Substitute every {@code ${...}} placeholder in {@code input}. */
    String substitute(String input) {
        if (input == null || input.indexOf("${") < 0) {
            return input;
        }
        StringBuilder out = new StringBuilder(input.length());
        int i = 0;
        while (i < input.length()) {
            int start = input.indexOf("${", i);
            if (start < 0) {
                out.append(input, i, input.length());
                break;
            }
            out.append(input, i, start);
            int end = findMatchingBrace(input, start + 2);
            if (end < 0) {
                throw new TemplateException("Unterminated '${' placeholder in: " + input);
            }
            String expr = input.substring(start + 2, end).trim();
            out.append(resolveExpression(expr));
            i = end + 1;
        }
        return out.toString();
    }

    private static int findMatchingBrace(String s, int from) {
        int depth = 1;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private String resolveExpression(String expr) {
        if (expr.isEmpty()) {
            throw new TemplateException("Empty '${}' placeholder.");
        }
        if (expr.contains("${")) {
            throw new TemplateException("Nested '${...}' inside '${" + expr
                    + "}' is not supported. Bind the inner value to a var or param first.");
        }
        if (expr.startsWith(TemplateResolver.SECRET_PREFIX)) {
            String key = expr.substring(TemplateResolver.SECRET_PREFIX.length());
            if (secretResolver == null) {
                return "${" + expr + "}";
            }
            String resolved = secretResolver.apply(key);
            if (resolved == null) {
                throw new TemplateException("SECRET '" + key + "' is not available.");
            }
            return resolved;
        }
        int paren = expr.indexOf('(');
        if (paren > 0 && expr.endsWith(")")) {
            String fnName = expr.substring(0, paren).trim();
            String argsRaw = expr.substring(paren + 1, expr.length() - 1);
            if (!FunctionApplicator.isKnown(fnName)) {
                throw new TemplateException("Unknown template function: '" + fnName + "'.");
            }
            // 'default' tolerates a missing first argument (that's its whole purpose);
            // every other function fails loud per design 05 §3.3.
            boolean softMissing = "default".equals(fnName);
            List<String> args = parseArgs(argsRaw, softMissing);
            return FunctionApplicator.apply(fnName, args, ctx);
        }
        return resolvePath(expr);
    }

    /** Parse a comma-separated function-arg list. Each arg is either a quoted literal or a path. */
    private List<String> parseArgs(String s, boolean softMissing) {
        List<String> out = new ArrayList<>();
        int i = 0;
        int len = s.length();
        while (i < len) {
            while (i < len && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            if (i >= len) {
                break;
            }
            char c = s.charAt(i);
            if (c == '\'' || c == '"') {
                int close = s.indexOf(c, i + 1);
                if (close < 0) {
                    throw new TemplateException("Unterminated string literal in: " + s);
                }
                out.add(s.substring(i + 1, close));
                i = close + 1;
            } else {
                int end = i;
                int depth = 0;
                while (end < len && (s.charAt(end) != ',' || depth > 0)) {
                    if (s.charAt(end) == '(') {
                        depth++;
                    } else if (s.charAt(end) == ')') {
                        depth--;
                    }
                    end++;
                }
                String token = s.substring(i, end).trim();
                if (token.isEmpty()) {
                    out.add("");
                } else if (softMissing) {
                    try {
                        out.add(resolvePath(token));
                    } catch (TemplateException te) {
                        out.add("");
                    }
                } else {
                    out.add(resolvePath(token));
                }
                i = end;
            }
            while (i < len && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            if (i < len && s.charAt(i) == ',') {
                i++;
            }
        }
        return out;
    }

    /** Resolve a {@code namespace.key.subkey} path against the context. */
    private String resolvePath(String path) {
        String[] parts = path.split("\\.", -1);
        // Single-segment path: a '!for' loop binding (e.g. '${region}') lives at the top of
        // the context map alongside 'vars'/'params'/'entity'.
        if (parts.length == 1) {
            Object value = ctx.get(parts[0]);
            if (value == null) {
                throw new TemplateException("Missing placeholder value: '${" + path + "}'.");
            }
            return value.toString();
        }
        String namespace = parts[0];
        Object scope = ctx.get(namespace);
        if (!(scope instanceof Map<?, ?> map)) {
            throw new TemplateException("Unknown placeholder namespace '" + namespace
                    + "' in '${" + path + "}'. Allowed: "
                    + TemplateResolver.NS_VARS + ", "
                    + TemplateResolver.NS_PARAMS + ", "
                    + TemplateResolver.NS_ENTITY + ".");
        }
        Object cursor = map;
        StringBuilder traversed = new StringBuilder(namespace);
        for (int i = 1; i < parts.length; i++) {
            traversed.append('.').append(parts[i]);
            if (!(cursor instanceof Map<?, ?> mm) || !mm.containsKey(parts[i])) {
                throw new TemplateException("Missing placeholder value: '${" + path + "}'.");
            }
            cursor = mm.get(parts[i]);
        }
        if (cursor == null) {
            throw new TemplateException("Missing placeholder value: '${" + path + "}'.");
        }
        if (cursor instanceof Map || cursor instanceof List) {
            // Render lists as comma-separated for join() and for plain string interpolation.
            if (cursor instanceof List<?> list) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append(list.get(i));
                }
                return sb.toString();
            }
            throw new TemplateException("Placeholder '${" + path + "}' resolves to a map; expected scalar.");
        }
        return cursor.toString();
    }
}
