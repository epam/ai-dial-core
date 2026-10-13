package com.epam.aidial.core.server.function.request;

import com.epam.aidial.core.server.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.SneakyThrows;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared rolling-hash builder for upstream cache keys, used by all {@link RequestObject} implementations.
 * One instance corresponds to one node-order traversal: {@link #update(JsonNode)} feeds nodes into a
 * single cumulative digest (so a change to an earlier node changes every later hash, matching how
 * providers cache the whole rendered prefix), and {@link #buildKey(String, boolean)} snapshots the
 * digest at that point without terminating it.
 */
public class CacheKeyBuilder {

    private final MessageDigest digest;

    @SneakyThrows
    public CacheKeyBuilder() {
        this.digest = MessageDigest.getInstance("SHA-1");
    }

    /**
     * Canonicalizes (sorts object keys, recursively) and feeds the node into the rolling digest.
     */
    public void update(JsonNode node) {
        JsonNode sorted = JsonUtil.sort(node);
        digest.update(sorted.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Snapshots the current digest state into a {@link CacheKey} without resetting it, so subsequent
     * {@link #update(JsonNode)} calls keep accumulating on top of it.
     */
    @SneakyThrows
    public CacheKey buildKey(String path, boolean hasBreakpoint) {
        MessageDigest snapshot = (MessageDigest) digest.clone();
        return new CacheKey(path, toHex(snapshot.digest()), hasBreakpoint);
    }

    /**
     * Normalizes a node so a scalar behaves as a one-element array (e.g. {@code system:"x"} ->
     * {@code system[0]}, string {@code content} -> {@code content[0]}), per the cache-key contract.
     * A missing/null node normalizes to no elements.
     */
    public static List<JsonNode> elements(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (node.isArray()) {
            List<JsonNode> result = new ArrayList<>();
            node.forEach(result::add);
            return result;
        }
        return List.of(node);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
