package com.epam.aidial.core.server.function.request;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.server.data.cache.CachePrefixPath;
import com.epam.aidial.core.server.util.ChatUtil;
import com.epam.aidial.core.server.util.JsonUtil;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Anthropic Messages API request. Pure pass-through: the body is forwarded verbatim except for the
 * model-name override applied by {@code EnhanceDeploymentRequestFn}.
 */
@Slf4j
@RequiredArgsConstructor
public class MessagesApiRequest implements RequestObject {
    private static final String CONTENT_NODE = "content";
    private static final String CACHE_CONTROL_NODE = "cache_control";

    private final ObjectNode tree;

    @Override
    public String getModel() {
        return tree.path("model").asText();
    }

    @Override
    public void setModel(String model) {
        tree.put("model", model);
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
            ObjectNode sorted = (ObjectNode) JsonUtil.sort(message);
            for (Map.Entry<String, JsonNode> entry : sorted.properties()) {
                if (!entry.getKey().equals(CONTENT_NODE)) {
                    builder.update(entry.getValue());
                }
            }
            List<JsonNode> blocks = CacheKeyBuilder.elements(sorted.get(CONTENT_NODE));
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
        ObjectNode sorted = (ObjectNode) JsonUtil.sort(block);
        for (Map.Entry<String, JsonNode> entry : sorted.properties()) {
            if (!entry.getKey().equals(CACHE_CONTROL_NODE)) {
                builder.update(entry.getValue());
            }
        }
    }

    private static boolean hasCacheControl(JsonNode block) {
        return block.isObject() && block.has(CACHE_CONTROL_NODE);
    }

    @Override
    public void clearInterceptorSettings() {
        ChatUtil.removeInterceptorConfiguration(tree);
    }

    @Override
    public void applyDefaults(Deployment deployment) {
        // No-op: Anthropic params differ from the OpenAI chat/responses defaults, so applying
        // Deployment defaults here would be wrong. Per-interface defaults can be added later.
    }

    @Override
    public byte[] serialize() throws JsonProcessingException {
        return ProxyUtil.MAPPER.writeValueAsBytes(tree);
    }
}
