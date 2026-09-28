package com.epam.aidial.core.server.token;

import com.epam.aidial.core.server.util.JsonUtil;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class MessagesTokenUsageParserTest {

    @Test
    void parseDerivesTotalAndCache() {
        JsonNode body = tree(
                "{\"id\":\"msg\",\"usage\":{\"input_tokens\":10,\"output_tokens\":8,\"cache_read_input_tokens\":2}}");

        TokenUsage usage = MessagesTokenUsageParser.parse(body);

        assertNotNull(usage);
        // Anthropic's input_tokens excludes cache counters; DIAL prompt tokens include them (OpenAI semantics).
        assertEquals(12, usage.getPromptTokens());
        assertEquals(8, usage.getCompletionTokens());
        // Anthropic omits total_tokens — it must be derived, not left at 0.
        assertEquals(20, usage.getTotalTokens());
        assertNotNull(usage.getPromptTokensDetails());
        assertEquals(2, usage.getPromptTokensDetails().getCachedTokens());
        assertEquals(0, usage.getPromptTokensDetails().getCacheWriteTokens());
    }

    @Test
    void parseCountsCacheCreationTokens() {
        JsonNode body = tree(
                "{\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"cache_read_input_tokens\":7,\"cache_creation_input_tokens\":3}}");

        TokenUsage usage = MessagesTokenUsageParser.parse(body);

        assertNotNull(usage);
        assertEquals(20, usage.getPromptTokens());
        assertEquals(25, usage.getTotalTokens());
        // Cache reads and cache writes are disjoint subsets of prompt tokens.
        assertEquals(7, usage.getPromptTokensDetails().getCachedTokens());
        assertEquals(3, usage.getPromptTokensDetails().getCacheWriteTokens());
    }

    @Test
    void parseCountsCacheCreationTokensWithoutCacheReads() {
        JsonNode body = tree(
                "{\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"cache_creation_input_tokens\":3}}");

        TokenUsage usage = MessagesTokenUsageParser.parse(body);

        assertNotNull(usage);
        assertEquals(13, usage.getPromptTokens());
        assertNotNull(usage.getPromptTokensDetails());
        assertEquals(0, usage.getPromptTokensDetails().getCachedTokens());
        assertEquals(3, usage.getPromptTokensDetails().getCacheWriteTokens());
    }

    @Test
    void parseMapsThinkingTokensToReasoningTokens() {
        JsonNode body = tree(
                "{\"usage\":{\"input_tokens\":10,\"output_tokens\":8,\"output_tokens_details\":{\"thinking_tokens\":6}}}");

        TokenUsage usage = MessagesTokenUsageParser.parse(body);

        assertNotNull(usage);
        // Thinking tokens are a subset of output tokens, so prompt/total accounting is unchanged.
        assertEquals(8, usage.getCompletionTokens());
        assertEquals(18, usage.getTotalTokens());
        assertNotNull(usage.getCompletionTokensDetails());
        assertEquals(6, usage.getCompletionTokensDetails().getReasoningTokens());
    }

    @Test
    void parseWithoutCacheLeavesDetailsNull() {
        JsonNode body = tree("{\"usage\":{\"input_tokens\":3,\"output_tokens\":4}}");

        TokenUsage usage = MessagesTokenUsageParser.parse(body);

        assertNotNull(usage);
        assertEquals(7, usage.getTotalTokens());
        assertNull(usage.getPromptTokensDetails());
        assertNull(usage.getCompletionTokensDetails());
    }

    @Test
    void parseReturnsNullWhenNoUsage() {
        assertNull(MessagesTokenUsageParser.parse(tree("{\"id\":\"msg\"}")));
        assertNull(MessagesTokenUsageParser.parse(MissingNode.getInstance()));
    }

    @Test
    void fromUsageNodeBuildsTokenUsage() throws Exception {
        JsonNode node = ProxyUtil.MAPPER.readTree(
                "{\"input_tokens\":25,\"output_tokens\":130,\"cache_read_input_tokens\":5}");

        TokenUsage usage = MessagesTokenUsageParser.fromUsageNode(node);

        assertEquals(30, usage.getPromptTokens());
        assertEquals(130, usage.getCompletionTokens());
        assertEquals(160, usage.getTotalTokens());
        assertEquals(5, usage.getPromptTokensDetails().getCachedTokens());
    }

    private static JsonNode tree(String json) {
        return JsonUtil.tryParse(json);
    }
}
