package com.epam.aidial.core.server.function.request;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.SneakyThrows;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiFunction;

/**
 * Shared rolling-hash builder for upstream cache keys, used by all {@link RequestObject} implementations.
 * One instance corresponds to one node-order traversal: {@link #update(JsonNode)} feeds nodes into a
 * single cumulative digest (so a change to an earlier node changes every later hash, matching how
 * providers cache the whole rendered prefix), and {@link #buildKey(String, boolean)} snapshots the
 * digest at that point without terminating it.
 *
 * <p>The digest is MD5, hex-encoded. The key only pins requests that share a prefix to one upstream, so it has to be
 * stable, not secure: a collision sends a request to another prefix's upstream and its cache metadata.
 */
public class CacheKeyBuilder {

    // the canonical form's tags; with every string's length before it, two different trees never feed the same bytes
    private static final byte OBJECT_START = '{';
    private static final byte OBJECT_END = '}';
    private static final byte ARRAY_START = '[';
    private static final byte ARRAY_END = ']';
    private static final byte STRING_TAG = '"';
    private static final byte SCALAR_TAG = '#';

    private final MessageDigest digest;

    @SneakyThrows
    public CacheKeyBuilder() {
        // MD5 because HotSpot intrinsifies it on every x86-64 and aarch64 CPU, while SHA-1 and SHA-256 run far slower over
        // the whole prompt without the CPU SHA extensions
        this.digest = MessageDigest.getInstance("MD5");
    }

    /**
     * Feeds the node into the rolling digest in a canonical form: object properties sorted by name, nested ones too,
     * and every string as its UTF-8 bytes after their length. Equal JSON gives equal keys whatever its layout or property
     * order. The node is left unchanged.
     */
    public void update(JsonNode node) {
        // no JSON text: a generator escapes and encodes char by char, slower than the digest itself; getBytes copies an
        // ASCII string in bulk
        switch (node.getNodeType()) {
            case OBJECT -> {
                digest.update(OBJECT_START);
                sortedProperties(node).forEach((name, value) -> {
                    text(name);
                    update(value);
                });
                digest.update(OBJECT_END);
            }
            case ARRAY -> {
                digest.update(ARRAY_START);
                node.forEach(this::update);
                digest.update(ARRAY_END);
            }
            case STRING -> {
                digest.update(STRING_TAG);
                text(node.textValue());
            }
            default -> {
                // a number as parsed (1 and 1.0 differ), a boolean or null
                digest.update(SCALAR_TAG);
                text(node.asText());
            }
        }
    }

    /**
     * Feeds the object's properties that {@code feed} chooses, in name order, each as its name and then what
     * {@code feed} returns for its value (null skips the property): the same canonical form as {@link #update}, so
     * the names and the object's bounds stay in the digest when a caller leaves some properties out.
     */
    void updateProperties(JsonNode object, BiFunction<String, JsonNode, JsonNode> feed) {
        digest.update(OBJECT_START);
        sortedProperties(object).forEach((name, value) -> {
            JsonNode fed = feed.apply(name, value);
            if (fed != null) {
                text(name);
                update(fed);
            }
        });
        digest.update(OBJECT_END);
    }

    // an untagged name, the API or a request section, so the same JSON in two APIs or two sections gives two keys
    void section(String name) {
        text(name);
    }

    private void text(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        int length = bytes.length;
        digest.update((byte) (length >>> 24));
        digest.update((byte) (length >>> 16));
        digest.update((byte) (length >>> 8));
        digest.update((byte) length);
        digest.update(bytes);
    }

    /**
     * Snapshots the current digest state into a {@link CacheKey} without resetting it, so subsequent
     * {@link #update(JsonNode)} calls keep accumulating on top of it.
     */
    @SneakyThrows
    public CacheKey buildKey(String path, boolean hasBreakpoint) {
        MessageDigest snapshot = (MessageDigest) digest.clone();
        return new CacheKey(path, HexFormat.of().formatHex(snapshot.digest()), hasBreakpoint);
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

    // the object's own properties sorted by name; the values are not copied
    private static Map<String, JsonNode> sortedProperties(JsonNode object) {
        Map<String, JsonNode> propertiesByName = new TreeMap<>();
        object.properties().forEach(property -> propertiesByName.put(property.getKey(), property.getValue()));
        return propertiesByName;
    }
}
