package com.epam.aidial.core.server.function.request;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.server.util.ChatUtil;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Set;

@RequiredArgsConstructor
public class ResponsesApiRequest implements RequestObject {
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
        return ChatUtil.collectAttachments(tree, List.of(
                "$.input[?(!@.type || @.type == 'message')].content[?(@.type == 'input_image')].image_url",
                "$.input[?(!@.type || @.type == 'message')].content[?(@.type == 'input_file')].file_url",
                "$.input[?(@.type == 'custom_tool_call_output' || @.type == 'function_call_output')].output[?(@.type == 'input_image')].image_url",
                "$.input[?(@.type == 'custom_tool_call_output' || @.type == 'function_call_output')].output[?(@.type == 'input_file')].file_url",
                "$.input[?(@.type == 'computer_call_output')].output.image_url",
                "$.tools[?(@.type == 'image_generation')].input_image_mask.image_url"));
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
        ChatUtil.applyDefaults(tree, deployment.getResponsesDefaults());
    }

    @Override
    public byte[] serialize() throws JsonProcessingException {
        return ProxyUtil.MAPPER.writeValueAsBytes(tree);
    }

    @Override
    public boolean isStore() {
        return tree.path("store").asBoolean(true);
    }

    @Override
    public boolean isBackground() {
        return tree.path("background").asBoolean(false);
    }
}
