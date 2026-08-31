package com.epam.aidial.core.server.pricing;

import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.StandardField;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StandardFieldResolverTest {

    @Test
    void resolvesOpenAiChatCompletionsFields() throws JsonProcessingException {
        JsonNode root = tree("""
                {
                  "usage": {
                    "prompt_tokens": 150000,
                    "prompt_tokens_details": { "cached_tokens": 800, "cache_write_tokens": 50 }
                  },
                  "service_tier": "flex"
                }
                """);
        assertEquals(800, resolve(InterfaceType.OPENAI_CHAT_COMPLETIONS, StandardField.CACHED_READ_TOKENS, root).asLong());
        assertEquals(50, resolve(InterfaceType.OPENAI_CHAT_COMPLETIONS, StandardField.CACHED_WRITE_TOKENS, root).asLong());
        assertEquals(150000, resolve(InterfaceType.OPENAI_CHAT_COMPLETIONS, StandardField.PROMPT_TOKENS, root).asLong());
        assertEquals("flex", resolve(InterfaceType.OPENAI_CHAT_COMPLETIONS, StandardField.SERVICE_TIER, root).asText());
        assertTrue(resolve(InterfaceType.OPENAI_CHAT_COMPLETIONS, StandardField.TTL, root).isMissingNode());
    }

    @Test
    void resolvesOpenAiResponsesFields() throws JsonProcessingException {
        JsonNode root = tree("""
                {
                  "usage": {
                    "input_tokens": 150000,
                    "input_tokens_details": { "cached_tokens": 800, "cache_write_tokens": 50 }
                  },
                  "service_tier": "priority"
                }
                """);
        assertEquals(800, resolve(InterfaceType.OPENAI_RESPONSES, StandardField.CACHED_READ_TOKENS, root).asLong());
        assertEquals(50, resolve(InterfaceType.OPENAI_RESPONSES, StandardField.CACHED_WRITE_TOKENS, root).asLong());
        assertEquals(150000, resolve(InterfaceType.OPENAI_RESPONSES, StandardField.PROMPT_TOKENS, root).asLong());
        assertEquals("priority", resolve(InterfaceType.OPENAI_RESPONSES, StandardField.SERVICE_TIER, root).asText());
    }

    @Test
    void resolvesAnthropicMessagesFieldsIncludingDerivations() throws JsonProcessingException {
        JsonNode root = tree("""
                {
                  "usage": {
                    "input_tokens": 249500,
                    "cache_read_input_tokens": 400,
                    "cache_creation_input_tokens": 100,
                    "cache_creation": { "ephemeral_5m_input_tokens": 0, "ephemeral_1h_input_tokens": 100 },
                    "service_tier": "standard"
                  }
                }
                """);
        assertEquals(400, resolve(InterfaceType.ANTHROPIC_MESSAGES, StandardField.CACHED_READ_TOKENS, root).asLong());
        assertEquals(100, resolve(InterfaceType.ANTHROPIC_MESSAGES, StandardField.CACHED_WRITE_TOKENS, root).asLong());
        // derivation: input_tokens + cache_read + cache_creation = 249500 + 400 + 100 = 250000
        assertEquals(250000, resolve(InterfaceType.ANTHROPIC_MESSAGES, StandardField.PROMPT_TOKENS, root).asLong());
        assertEquals("standard", resolve(InterfaceType.ANTHROPIC_MESSAGES, StandardField.SERVICE_TIER, root).asText());
        assertEquals("1h", resolve(InterfaceType.ANTHROPIC_MESSAGES, StandardField.TTL, root).asText());
    }

    @Test
    void anthropicTtlPrefers1hOver5mAndMissesWhenNeitherPresent() throws JsonProcessingException {
        JsonNode fiveMinute = tree("""
                { "usage": { "cache_creation": { "ephemeral_5m_input_tokens": 10, "ephemeral_1h_input_tokens": 0 } } }
                """);
        assertEquals("5m", resolve(InterfaceType.ANTHROPIC_MESSAGES, StandardField.TTL, fiveMinute).asText());

        JsonNode neither = tree("{ \"usage\": {} }");
        assertTrue(resolve(InterfaceType.ANTHROPIC_MESSAGES, StandardField.TTL, neither).isMissingNode());
    }

    @Test
    void nullInterfaceAlwaysMisses() throws JsonProcessingException {
        JsonNode root = tree("{ \"usage\": { \"prompt_tokens\": 1 } }");
        assertTrue(resolve(null, StandardField.PROMPT_TOKENS, root).isMissingNode());
    }

    private static JsonNode resolve(InterfaceType type, StandardField field, JsonNode root) {
        return StandardFieldResolver.resolve(type, field, root);
    }

    private static JsonNode tree(String json) throws JsonProcessingException {
        return ProxyUtil.MAPPER.readTree(json);
    }
}
