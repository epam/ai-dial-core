package com.epam.aidial.cli.template;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Single-dispatch table of the seven built-in template functions.
 * Each function takes an already-resolved positional argument list and the full context map
 * (vars / params / entity / loop bindings) so {@code default} can re-resolve a missing key
 * without throwing.
 */
final class FunctionApplicator {

    private static final Map<String, BiFunction<List<String>, Map<String, Object>, String>> FUNCTIONS = Map.of(
            "default", (args, ctx) -> {
                requireArity("default", args, 2);
                String value = args.get(0);
                return (value == null || value.isEmpty()) ? args.get(1) : value;
            },
            "lower", (args, ctx) -> {
                requireArity("lower", args, 1);
                return args.get(0).toLowerCase();
            },
            "upper", (args, ctx) -> {
                requireArity("upper", args, 1);
                return args.get(0).toUpperCase();
            },
            "trim", (args, ctx) -> {
                requireArity("trim", args, 1);
                return args.get(0).trim();
            },
            "join", (args, ctx) -> {
                requireArity("join", args, 2);
                String list = args.get(0);
                String sep = args.get(1);
                if (list == null || list.isEmpty()) {
                    return "";
                }
                return list.replace(",", sep);
            },
            "base64", (args, ctx) -> {
                requireArity("base64", args, 1);
                return Base64.getEncoder().encodeToString(args.get(0).getBytes(StandardCharsets.UTF_8));
            },
            "replace", (args, ctx) -> {
                requireArity("replace", args, 3);
                return args.get(0).replace(args.get(1), args.get(2));
            }
    );

    private FunctionApplicator() {
    }

    static boolean isKnown(String name) {
        return FUNCTIONS.containsKey(name);
    }

    static String apply(String name, List<String> args, Map<String, Object> ctx) {
        BiFunction<List<String>, Map<String, Object>, String> fn = FUNCTIONS.get(name);
        if (fn == null) {
            throw new TemplateException("Unknown template function: '" + name + "'. Allowed: " + FUNCTIONS.keySet());
        }
        return fn.apply(args, ctx);
    }

    private static void requireArity(String name, List<String> args, int expected) {
        if (args.size() != expected) {
            throw new TemplateException("Function '" + name + "' expects " + expected
                    + " argument(s); got " + args.size() + ".");
        }
    }
}
