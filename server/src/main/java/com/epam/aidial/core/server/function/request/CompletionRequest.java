package com.epam.aidial.core.server.function.request;

import com.epam.aidial.core.server.util.JsonUtil;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

@RequiredArgsConstructor
public class CompletionRequest implements RequestObject {
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
        result.addAll(ProxyUtil.collectAttachments(tree, List.of(
                "$.messages[*].content[?(@.type == 'image_url')].image_url.url")));
        result.addAll(ProxyUtil.collectCustomAttachments(tree, List.of(
                "$.messages[*].custom_content.attachments[*]",
                "$.messages[*].custom_content.stages[*].attachments[*]",
                "$.custom_input[*]")));
        return result;
    }

    @Override
    public Set<String> collectAppAttachments(List<String> paths) {
        return ProxyUtil.collectAttachments(tree, paths);
    }

    @Override
    public List<CacheKey> buildMessageCacheKeys() {
        return buildCacheKeys("messages");
    }

    @Override
    public List<CacheKey> buildToolCacheKeys() {
        return buildCacheKeys("tools");
    }

    @SneakyThrows
    private List<CacheKey> buildCacheKeys(String name) {
        JsonNode node = tree.get(name);
        if (node == null || !node.isArray()) {
            // embedding request is not supported yet
            return new ArrayList<>();
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        List<CacheKey> result = new ArrayList<>();
        for (int index = 0; index < node.size(); index++) {
            ObjectNode objectNode = (ObjectNode) JsonUtil.sort(node.get(index));
            for (Map.Entry<String, JsonNode> entry : objectNode.properties()) {
                if (entry.getKey().equals(CUSTOM_FIELDS_NODE)) {
                    continue;
                }
                if (entry.getKey().equals("custom_content")) {
                    // include attachments only
                    JsonNode attachments = entry.getValue().get("attachments");
                    if (attachments != null && !attachments.isEmpty()) {
                        digest.update(attachments.toString().getBytes(StandardCharsets.UTF_8));
                    }
                } else {
                    digest.update(entry.getValue().toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            String hash = toString(digest.digest());
            CacheKey cacheKey = new CacheKey(hash, objectNode.path(CUSTOM_FIELDS_NODE).has(CACHE_BREAKPOINT_NODE));
            result.add(cacheKey);
        }
        return result;
    }

    @Override
    public void clearInterceptorSettings() {
        ProxyUtil.removeInterceptorConfiguration(tree);
    }

    @Override
    public void update(String key, Function<JsonNode, JsonNode> mapper) {
        JsonUtil.update(tree, key, mapper);
    }

    @Override
    public byte[] serialize() throws JsonProcessingException {
        return ProxyUtil.MAPPER.writeValueAsBytes(tree);
    }

    private static String toString(byte[] digest) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : digest) {
            hexString.append(String.format("%02x", b));
        }
        return hexString.toString();
    }
}
