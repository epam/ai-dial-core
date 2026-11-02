package com.epam.aidial.core.translator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.SneakyThrows;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One request's reversible tool-name map, ported from the Python translator's {@code tool_names.py}. A function
 * name the strictest upstream would reject (Claude Code's {@code mcp__<server>__<tool>} names are often over 64
 * characters) goes upstream as {@code <head>_<8 hex of its sha256>}, and the answer's names are mapped back, so the
 * client never sees an alias.
 *
 * <p>Per request, never process-wide: an earlier request's alias could otherwise rename a later request's tool.
 * Filled during the request translation and only read afterwards, so every upstream attempt restores alike. With
 * no collision an alias depends on the name alone, so a replayed call matches its tool from turn to turn.
 */
public class ToolNames {

    private static final Pattern VALID_NAME = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_-]{2,63}");
    private static final Pattern INVALID_CHARACTER = Pattern.compile("[^a-zA-Z0-9_-]");
    private static final Pattern VALID_START = Pattern.compile("[a-zA-Z_]");
    private static final int HASH_LENGTH = 8;
    private static final int MAX_LENGTH = 64;

    private final Map<String, String> originalsByAlias = new HashMap<>();
    private final Map<String, String> aliasesByOriginal = new HashMap<>();

    /**
     * The alias of {@code name}, registered for {@link #restore}. It differs from every name in {@code taken}
     * (the request's names that go upstream unchanged) and from every other alias of this request.
     */
    String alias(String name, Set<String> taken) {
        String alias = aliasesByOriginal.get(name);
        if (alias != null) {
            return alias;
        }
        String digest = HexFormat.of().formatHex(sha256(name), 0, HASH_LENGTH / 2);
        String head = INVALID_CHARACTER.matcher(name).replaceAll("_");
        if (!VALID_START.matcher(head).lookingAt()) {
            head = "t_" + head;
        }
        alias = candidate(head, "_" + digest);
        for (int attempt = 1; taken.contains(alias) || originalsByAlias.containsKey(alias); attempt++) {
            alias = candidate(head, "_" + digest + "_" + attempt);
        }
        originalsByAlias.put(alias, name);
        aliasesByOriginal.put(name, alias);
        return alias;
    }

    /**
     * The original of an alias this request issued; any other value, a non-string included, as it is.
     */
    JsonNode restore(JsonNode name) {
        String original = name != null && name.isTextual() ? originalsByAlias.get(name.textValue()) : null;
        return original == null ? name : TextNode.valueOf(original);
    }

    /**
     * Aliases every name the upstream may reject in a Responses body, in place: function {@code tools}, a
     * function {@code tool_choice} and replayed {@code function_call} items, so the three always agree. Tools of
     * other types keep their names: the upstream looks them up as they are.
     */
    void applyResponses(ObjectNode body) {
        List<ObjectNode> holders = new ArrayList<>();
        for (JsonNode tool : body.path("tools")) {
            if ("function".equals(tool.path("type").asText())) {
                holders.add((ObjectNode) tool);
            }
        }
        if ("function".equals(body.path("tool_choice").path("type").asText())) {
            holders.add((ObjectNode) body.get("tool_choice"));
        }
        for (JsonNode item : body.path("input")) {
            if ("function_call".equals(item.path("type").asText())) {
                holders.add((ObjectNode) item);
            }
        }
        Set<String> taken = new HashSet<>();
        for (ObjectNode holder : holders) {
            if (isValid(holder.get("name"))) {
                taken.add(holder.get("name").textValue());
            }
        }
        for (ObjectNode holder : holders) {
            JsonNode name = holder.get("name");
            if (name != null && name.isTextual() && !isValid(name)) {
                holder.put("name", alias(name.textValue(), taken));
            }
        }
    }

    private static boolean isValid(JsonNode name) {
        return name != null && name.isTextual() && VALID_NAME.matcher(name.textValue()).matches();
    }

    private static String candidate(String head, String suffix) {
        return head.substring(0, Math.min(head.length(), MAX_LENGTH - suffix.length())) + suffix;
    }

    @SneakyThrows
    private static byte[] sha256(String name) {
        return MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8));
    }
}
