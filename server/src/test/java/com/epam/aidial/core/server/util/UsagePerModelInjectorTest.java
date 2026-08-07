package com.epam.aidial.core.server.util;

import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.token.UsagePerModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class UsagePerModelInjectorTest {

    @Test
    public void testInject_CreatesStatisticsWhenAbsent() {
        ObjectNode root = ProxyUtil.MAPPER.createObjectNode();
        root.put("id", "chatcmpl-1");

        UsagePerModelInjector.inject(root, List.of(new UsagePerModel("gpt-4", usage(10, 5))));

        JsonNode usagePerModel = root.path("statistics").path("usage_per_model");
        assertTrue(usagePerModel.isArray());
        assertEquals(1, usagePerModel.size());
        assertEquals("gpt-4", usagePerModel.get(0).path("model").asText());
        assertEquals(10, usagePerModel.get(0).path("prompt_tokens").asLong());
    }

    @Test
    public void testInject_OverridesExistingUsagePerModel() {
        ObjectNode root = ProxyUtil.MAPPER.createObjectNode();
        ObjectNode statistics = root.putObject("statistics");
        statistics.putArray("usage_per_model").addObject().put("model", "app-reported-value");

        UsagePerModelInjector.inject(root, List.of(new UsagePerModel("gpt-4", usage(10, 5))));

        JsonNode usagePerModel = root.path("statistics").path("usage_per_model");
        assertEquals(1, usagePerModel.size());
        assertEquals("gpt-4", usagePerModel.get(0).path("model").asText());
    }

    @Test
    public void testInject_PreservesSiblingDiscardedMessages() {
        ObjectNode root = ProxyUtil.MAPPER.createObjectNode();
        ObjectNode statistics = root.putObject("statistics");
        statistics.putArray("discarded_messages").add(0).add(1);

        UsagePerModelInjector.inject(root, List.of(new UsagePerModel("gpt-4", usage(10, 5))));

        assertEquals(2, root.path("statistics").path("discarded_messages").size());
        assertEquals(1, root.path("statistics").path("usage_per_model").size());
    }

    @Test
    public void testStrip_RemovesUsagePerModelButKeepsDiscardedMessages() {
        ObjectNode root = ProxyUtil.MAPPER.createObjectNode();
        ObjectNode statistics = root.putObject("statistics");
        statistics.putArray("usage_per_model").addObject().put("model", "upstream-value");
        statistics.putArray("discarded_messages").add(0);

        UsagePerModelInjector.strip(root);

        assertFalse(root.path("statistics").has("usage_per_model"));
        assertEquals(1, root.path("statistics").path("discarded_messages").size());
    }

    @Test
    public void testStrip_RemovesStatisticsEntirelyWhenItBecomesEmpty() {
        ObjectNode root = ProxyUtil.MAPPER.createObjectNode();
        ObjectNode statistics = root.putObject("statistics");
        statistics.putArray("usage_per_model").addObject().put("model", "upstream-value");

        UsagePerModelInjector.strip(root);

        assertFalse(root.has("statistics"));
    }

    @Test
    public void testStrip_NoOpWhenStatisticsAbsent() {
        ObjectNode root = ProxyUtil.MAPPER.createObjectNode();
        root.put("id", "chatcmpl-1");

        UsagePerModelInjector.strip(root);

        assertFalse(root.has("statistics"));
        assertEquals("chatcmpl-1", root.path("id").asText());
    }

    private static TokenUsage usage(long promptTokens, long completionTokens) {
        TokenUsage usage = new TokenUsage();
        usage.setPromptTokens(promptTokens);
        usage.setCompletionTokens(completionTokens);
        usage.setTotalTokens(promptTokens + completionTokens);
        return usage;
    }
}
