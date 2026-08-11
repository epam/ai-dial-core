package com.epam.aidial.core.server.function.request;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.server.data.cache.CachePrefixPath;
import com.epam.aidial.core.server.util.ChatUtil;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
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

    /**
     * {@code tools[i]}, {@code instructions[0]}, {@code input[i]} — candidates come solely from
     * auto-caching (OpenAI prompt caching has no client breakpoint concept), so {@code hasBreakpoint}
     * is always {@code false}. {@code input} last guarantees monotonicity: appending a turn never
     * perturbs an earlier prefix.
     */
    @Override
    public List<CacheKey> buildCacheKeys(List<String> nodeOrder) {
        CacheKeyBuilder builder = new CacheKeyBuilder();
        List<CacheKey> result = new ArrayList<>();
        for (String designator : nodeOrder) {
            String node = CachePrefixPath.parseNode(designator);
            if (node == null || !("tools".equals(node) || "instructions".equals(node) || "input".equals(node))) {
                log.warn("Unsupported prefix path: {}", designator);
                continue;
            }
            appendCacheKeys(builder, node, result);
        }
        return result;
    }

    private void appendCacheKeys(CacheKeyBuilder builder, String node, List<CacheKey> result) {
        List<JsonNode> elements = CacheKeyBuilder.elements(tree.get(node));
        for (int index = 0; index < elements.size(); index++) {
            builder.update(elements.get(index));
            result.add(builder.buildKey(CachePrefixPath.node(node, index), false));
        }
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
