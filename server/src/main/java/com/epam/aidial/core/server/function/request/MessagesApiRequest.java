package com.epam.aidial.core.server.function.request;

import com.epam.aidial.core.server.data.cache.CachePrefixPath;
import com.epam.aidial.core.server.util.ChatUtil;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.buffer.ByteBufInputStream;
import io.vertx.core.buffer.Buffer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import javax.annotation.Nullable;

/**
 * Anthropic Messages API request. Pure pass-through: the body is forwarded verbatim except for the
 * model-name override applied by {@code EnhanceDeploymentRequestFn}.
 *
 * <p>A request {@link #parse parsed} from a body forwards the received bytes, with only the model value
 * replaced, until a default or the removal of interceptor settings changes the tree; only then is the tree
 * serialized. So the tree must be changed through this class, never through {@link #getTree()}.
 */
@Slf4j
public class MessagesApiRequest implements RequestObject {
    private static final String MODEL_NODE = "model";
    private static final String CONTENT_NODE = "content";
    private static final String CACHE_CONTROL_NODE = "cache_control";

    @Getter
    private final ObjectNode tree;
    @Nullable
    private final Buffer body;
    // byte range of the top-level model string in the body, quotes included; -1 when the body has none
    private final int modelStart;
    private final int modelEnd;
    @Nullable
    private String modelOverride;
    private boolean treeChanged;

    public MessagesApiRequest(ObjectNode tree) {
        this(tree, null, -1, -1);
    }

    private MessagesApiRequest(ObjectNode tree, @Nullable Buffer body, int modelStart, int modelEnd) {
        this.tree = tree;
        this.body = body;
        this.modelStart = modelStart;
        this.modelEnd = modelEnd;
    }

    /**
     * Parses the body once, field by field, recording where the top-level model string lies. The received bytes
     * are kept for forwarding only when they are exactly the tree: one UTF-8 object, no duplicate top-level keys,
     * nothing but whitespace around it. Otherwise the tree is serialized, as before.
     *
     * @throws IllegalArgumentException if the body is not a JSON object
     */
    public static MessagesApiRequest parse(Buffer body) throws IOException {
        ObjectNode tree = ProxyUtil.MAPPER.createObjectNode();
        int modelStart = -1;
        int modelEnd = -1;
        boolean duplicate = false;
        long rootStart;
        long rootEnd;
        try (JsonParser parser = ProxyUtil.MAPPER.createParser((InputStream) new ByteBufInputStream(body.getByteBuf()))) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalArgumentException("Invalid json object");
            }
            // a byte offset is -1 for a body that is not UTF-8
            rootStart = parser.currentTokenLocation().getByteOffset();
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String name = parser.currentName();
                duplicate |= tree.has(name);
                boolean stringModel = parser.nextToken() == JsonToken.VALUE_STRING && name.equals(MODEL_NODE);
                long start = parser.currentTokenLocation().getByteOffset();
                tree.set(name, ProxyUtil.MAPPER.readTree(parser));
                if (stringModel) {
                    modelStart = (int) start;
                    modelEnd = (int) parser.currentLocation().getByteOffset();
                }
            }
            rootEnd = parser.currentLocation().getByteOffset();
        }
        boolean verbatim = !duplicate && rootStart >= 0 && isWhitespace(body, 0, rootStart) && isWhitespace(body, rootEnd, body.length());
        return new MessagesApiRequest(tree, verbatim ? body : null, modelStart, modelEnd);
    }

    private static boolean isWhitespace(Buffer body, long from, long to) {
        for (int i = (int) from; i < to; i++) {
            byte b = body.getByte(i);
            if (b != ' ' && b != '\t' && b != '\n' && b != '\r') {
                return false;
            }
        }
        return true;
    }

    @Override
    public String getModel() {
        return tree.path(MODEL_NODE).asText();
    }

    @Override
    public void setModel(String model) {
        tree.put(MODEL_NODE, model);
        modelOverride = model;
    }

    @Override
    public boolean isStreaming() {
        return tree.path("stream").asBoolean(false);
    }

    @Override
    public Set<String> collectAttachments() {
        // Pure pass-through: Anthropic bodies carry base64/public content, not DIAL file references,
        // so there is nothing to access-check here.
        return Set.of();
    }

    @Override
    public Set<String> collectAppAttachments(List<String> paths) {
        return ChatUtil.collectAttachments(tree, paths);
    }

    @Override
    public List<CacheKey> buildCacheKeys(List<String> nodeOrder) {
        CacheKeyBuilder builder = new CacheKeyBuilder();
        List<CacheKey> result = new ArrayList<>();
        for (String designator : nodeOrder) {
            String node = CachePrefixPath.parseNode(designator);
            if (node == null) {
                log.warn("Unsupported prefix path: {}", designator);
                continue;
            }
            switch (node) {
                case "tools" -> appendItemCacheKeys(builder, "tools", result);
                case "system" -> appendItemCacheKeys(builder, "system", result);
                case "messages" -> appendMessageCacheKeys(builder, result);
                default -> log.warn("Unsupported prefix path: {}", designator);
            }
        }
        return result;
    }

    /**
     * {@code tools[i]} / {@code system[i]}: one candidate per element, scalar normalized to a
     * one-element array (e.g. {@code system:"x"} -> {@code system[0]}).
     */
    private void appendItemCacheKeys(CacheKeyBuilder builder, String node, List<CacheKey> result) {
        List<JsonNode> elements = CacheKeyBuilder.elements(tree.get(node));
        for (int index = 0; index < elements.size(); index++) {
            JsonNode element = elements.get(index);
            updateExcludingCacheControl(builder, element);
            result.add(builder.buildKey(CachePrefixPath.node(node, index), hasCacheControl(element)));
        }
    }

    /**
     * {@code messages[i].content[j]}: block-level candidates. The message envelope (e.g. {@code role})
     * feeds the digest once per message, before its content blocks, so an envelope change changes
     * every later hash. String content is scalar-normalized to {@code content[0]}.
     */
    private void appendMessageCacheKeys(CacheKeyBuilder builder, List<CacheKey> result) {
        JsonNode messages = tree.get("messages");
        if (messages == null || !messages.isArray()) {
            return;
        }
        for (int index = 0; index < messages.size(); index++) {
            JsonNode message = messages.get(index);
            if (!message.isObject()) {
                continue;
            }
            sortProperties(message).forEach((name, value) -> {
                if (!name.equals(CONTENT_NODE)) {
                    builder.update(value);
                }
            });
            List<JsonNode> blocks = CacheKeyBuilder.elements(message.get(CONTENT_NODE));
            for (int contentIndex = 0; contentIndex < blocks.size(); contentIndex++) {
                JsonNode block = blocks.get(contentIndex);
                updateExcludingCacheControl(builder, block);
                result.add(builder.buildKey(
                        CachePrefixPath.contentBlock("messages", index, contentIndex),
                        hasCacheControl(block)));
            }
        }
    }

    /**
     * Feeds a block into the digest, skipping {@code cache_control} so a client moving its marker
     * forward each turn does not invalidate earlier prefixes. {@code cache_control} is only skipped
     * during iteration, never removed from the tree.
     */
    private static void updateExcludingCacheControl(CacheKeyBuilder builder, JsonNode block) {
        if (!block.isObject()) {
            builder.update(block);
            return;
        }
        sortProperties(block).forEach((name, value) -> {
            if (!name.equals(CACHE_CONTROL_NODE)) {
                builder.update(value);
            }
        });
    }

    /**
     * The object's own properties by name; the values are not copied, {@link CacheKeyBuilder#update} sorts them.
     */
    private static Map<String, JsonNode> sortProperties(JsonNode object) {
        Map<String, JsonNode> sorted = new TreeMap<>();
        object.properties().forEach(property -> sorted.put(property.getKey(), property.getValue()));
        return sorted;
    }

    private static boolean hasCacheControl(JsonNode block) {
        return block.isObject() && block.has(CACHE_CONTROL_NODE);
    }

    @Override
    public void clearInterceptorSettings() {
        // ChatUtil also drops custom_fields left empty
        JsonNode customFields = tree.path("custom_fields");
        treeChanged |= customFields.has("interceptor_configuration") || customFields.isObject() && customFields.isEmpty();
        ChatUtil.removeInterceptorConfiguration(tree);
    }

    @Override
    public void applyDefaults(Map<String, Object> defaults) {
        // a default the request already holds changes nothing; objects are merged in place, hence the copies
        Map<String, JsonNode> before = new HashMap<>();
        defaults.keySet().forEach(key -> before.put(key, tree.has(key) ? tree.get(key).deepCopy() : null));
        ChatUtil.applyDefaults(tree, defaults);
        treeChanged |= before.entrySet().stream().anyMatch(entry -> !Objects.equals(entry.getValue(), tree.get(entry.getKey())));
    }

    @Override
    public byte[] serialize() throws JsonProcessingException {
        if (body == null || treeChanged || modelOverride != null && modelStart < 0) {
            return ProxyUtil.MAPPER.writeValueAsBytes(tree);
        }
        if (modelOverride == null) {
            return body.getBytes();
        }
        byte[] model = ProxyUtil.MAPPER.writeValueAsBytes(modelOverride);
        return Buffer.buffer(body.length() - (modelEnd - modelStart) + model.length)
                .appendBuffer(body, 0, modelStart)
                .appendBytes(model)
                .appendBuffer(body, modelEnd, body.length() - modelEnd)
                .getBytes();
    }
}
