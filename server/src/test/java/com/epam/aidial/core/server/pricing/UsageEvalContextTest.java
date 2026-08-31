package com.epam.aidial.core.server.pricing;

import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.StandardField;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageEvalContextTest {

    @Test
    void passthroughResolvesStandardFieldsAgainstNativeResponse() throws JsonProcessingException {
        JsonNode root = tree("""
                {
                  "usage": {
                    "input_tokens": 249500,
                    "cache_read_input_tokens": 400,
                    "cache_creation_input_tokens": 100,
                    "cache_creation": { "ephemeral_1h_input_tokens": 100 }
                  }
                }
                """);
        UsageEvalContext ctx = UsageEvalContext.build(InterfaceType.ANTHROPIC_MESSAGES, root);

        assertEquals(250000, ctx.resolve("promptTokens").orElseThrow().asLong());
        assertEquals("1h", ctx.resolve("ttl").orElseThrow().asText());
        assertEquals(400, ctx.resolveCounter(StandardField.CACHED_READ_TOKENS).orElseThrow());
    }

    @Test
    void translationModeUpstreamUsageTakesPriorityOverNativeUsage() throws JsonProcessingException {
        // Chat-Completions-shaped native usage disagrees with the untranslated Anthropic envelope;
        // the envelope must win per requirement 4's priority rule.
        JsonNode root = tree("""
                {
                  "usage": { "prompt_tokens": 999 },
                  "custom_fields": {
                    "upstream_usage": {
                      "interface": "anthropicMessages",
                      "usage": { "input_tokens": 249500, "cache_read_input_tokens": 400, "cache_creation_input_tokens": 100 }
                    }
                  }
                }
                """);
        UsageEvalContext ctx = UsageEvalContext.build(InterfaceType.OPENAI_CHAT_COMPLETIONS, root);

        assertEquals(250000, ctx.resolve("promptTokens").orElseThrow().asLong());
    }

    @Test
    void unrecognizedUpstreamInterfaceAlwaysMissesStandardFields() throws JsonProcessingException {
        JsonNode root = tree("""
                {
                  "usage": {},
                  "custom_fields": { "upstream_usage": { "interface": "someFutureShape", "usage": { "x": 1 } } }
                }
                """);
        UsageEvalContext ctx = UsageEvalContext.build(InterfaceType.OPENAI_CHAT_COMPLETIONS, root);

        assertTrue(ctx.resolve("promptTokens").isEmpty());
    }

    @Test
    void jsonPathResolvesAgainstUsageRootInPassthroughMode() throws JsonProcessingException {
        JsonNode root = tree("""
                { "usage": { "server_tool_use": { "web_search_requests": 3 } } }
                """);
        UsageEvalContext ctx = UsageEvalContext.build(InterfaceType.ANTHROPIC_MESSAGES, root);

        assertEquals(3, ctx.resolve("$.usage.server_tool_use.web_search_requests").orElseThrow().asInt());
        assertTrue(ctx.resolve("$.upstream_usage.server_tool_use.web_search_requests").isEmpty());
    }

    @Test
    void jsonPathResolvesAgainstUpstreamUsageRootInTranslationMode() throws JsonProcessingException {
        JsonNode root = tree("""
                {
                  "usage": {},
                  "custom_fields": {
                    "upstream_usage": {
                      "interface": "anthropicMessages",
                      "usage": { "server_tool_use": { "web_search_requests": 3 } }
                    }
                  }
                }
                """);
        UsageEvalContext ctx = UsageEvalContext.build(InterfaceType.OPENAI_CHAT_COMPLETIONS, root);

        assertEquals(3, ctx.resolve("$.upstream_usage.server_tool_use.web_search_requests").orElseThrow().asInt());
    }

    @Test
    void missingUsageSourceMissesEveryStandardField() throws JsonProcessingException {
        JsonNode root = tree("{}");
        UsageEvalContext ctx = UsageEvalContext.build(InterfaceType.OPENAI_CHAT_COMPLETIONS, root);

        assertTrue(ctx.resolve("promptTokens").isEmpty());
        assertTrue(ctx.resolveCounter(StandardField.CACHED_READ_TOKENS).isEmpty());
    }

    private static JsonNode tree(String json) throws JsonProcessingException {
        return ProxyUtil.MAPPER.readTree(json);
    }
}
