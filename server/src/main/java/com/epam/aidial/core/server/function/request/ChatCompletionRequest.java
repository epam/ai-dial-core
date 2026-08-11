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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Represents a wrapper for completion and embedding requests, providing a strict interface.
 */
@Slf4j
@RequiredArgsConstructor
public class ChatCompletionRequest implements RequestObject {
    private static final String CUSTOM_FIELDS_NODE = "custom_fields";
    private static final String CACHE_BREAKPOINT_NODE = "cache_breakpoint";

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
        Set<String> result = new HashSet<>();
        result.addAll(ChatUtil.collectAttachments(tree, List.of(
                "$.messages[*].content[?(@.type == 'image_url')].image_url.url")));
        result.addAll(ChatUtil.collectCustomAttachments(tree, List.of(
                "$.messages[*].custom_content.attachments[*]",
                "$.messages[*].custom_content.stages[*].attachments[*]",
                "$.messages[*].custom_content.annotations[*].body.source.attachment",
                "$.custom_input[*]")));
        return result;
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
            if (!"tools".equals(node) && !"messages".equals(node)) {
                log.warn("Unsupported prefix path: {}", designator);
                continue;
            }
            appendCacheKeys(builder, node, result);
        }
        return result;
    }

    private void appendCacheKeys(CacheKeyBuilder builder, String node, List<CacheKey> result) {
        JsonNode array = tree.get(node);
        if (array == null || !array.isArray()) {
            // embedding request is not supported yet
            return;
        }
        for (int index = 0; index < array.size(); index++) {
            // sort so key iteration order below (and the digest fed from it) is deterministic
            // regardless of how the client ordered the JSON object's fields
            ObjectNode element = (ObjectNode) JsonUtil.sort(array.get(index));
            boolean hasBreakpoint = element.path(CUSTOM_FIELDS_NODE).has(CACHE_BREAKPOINT_NODE);
            for (Map.Entry<String, JsonNode> entry : element.properties()) {
                if (entry.getKey().equals(CUSTOM_FIELDS_NODE)) {
                    continue;
                }
                if (entry.getKey().equals("custom_content")) {
                    // include attachments only
                    JsonNode attachments = entry.getValue().get("attachments");
                    if (attachments != null && !attachments.isEmpty()) {
                        builder.update(attachments);
                    }
                } else {
                    builder.update(entry.getValue());
                }
            }
            result.add(builder.buildKey(CachePrefixPath.node(node, index), hasBreakpoint));
        }
    }

    @Override
    public void clearInterceptorSettings() {
        ChatUtil.removeInterceptorConfiguration(tree);
    }

    @Override
    public void applyDefaults(Deployment deployment) {
        ChatUtil.applyDefaults(tree, deployment.getDefaults());
    }

    @Override
    public byte[] serialize() throws JsonProcessingException {
        return ProxyUtil.MAPPER.writeValueAsBytes(tree);
    }
}
