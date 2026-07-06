package com.epam.aidial.core.server.function.request;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.server.util.ChatUtil;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Set;

/**
 * Anthropic Messages API request. Pure pass-through: the body is forwarded verbatim except for the
 * model-name override applied by {@code EnhanceModelRequestFn}.
 */
@RequiredArgsConstructor
public class MessagesApiRequest implements RequestObject {
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
    public List<CacheKey> buildMessageCacheKeys() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public List<CacheKey> buildToolCacheKeys() {
        throw new UnsupportedOperationException("Not supported yet.");
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
