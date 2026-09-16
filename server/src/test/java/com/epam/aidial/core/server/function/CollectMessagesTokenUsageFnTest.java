package com.epam.aidial.core.server.function;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;

@ExtendWith(MockitoExtension.class)
class CollectMessagesTokenUsageFnTest {

    @Mock
    private ProxyContext context;

    @Test
    public void testMergesUsageAcrossEvents() throws JsonProcessingException {
        doCallRealMethod().when(context).setTokenUsage(any());
        doCallRealMethod().when(context).getTokenUsage();

        CollectMessagesTokenUsageFn fn = new CollectMessagesTokenUsageFn(null, context);

        fn.apply(tree("""
                {
                  "type": "message_start",
                  "message": {
                    "usage": {
                      "input_tokens": 10,
                      "cache_read_input_tokens": 3,
                      "cache_creation_input_tokens": 2
                    }
                  }
                }
                """));
        fn.apply(tree("""
                {
                  "type": "message_delta",
                  "usage": {
                    "output_tokens": 8,
                    "output_tokens_details": {
                      "thinking_tokens": 6
                    }
                  }
                }
                """));

        TokenUsage usage = context.getTokenUsage();
        assertNotNull(usage);
        // input_tokens from message_start is kept while message_delta contributes the output counters.
        assertEquals(15, usage.getPromptTokens());
        assertEquals(8, usage.getCompletionTokens());
        assertEquals(23, usage.getTotalTokens());
        assertEquals(3, usage.getPromptTokensDetails().getCachedTokens());
        assertEquals(2, usage.getPromptTokensDetails().getCacheWriteTokens());
        assertEquals(6, usage.getCompletionTokensDetails().getReasoningTokens());
    }

    @Test
    public void testMergesPricingUsageNodeAcrossEvents() throws JsonProcessingException {
        doCallRealMethod().when(context).setTokenUsage(any());
        doCallRealMethod().when(context).setPricingUsageNode(any());
        doCallRealMethod().when(context).getPricingUsageNode();

        CollectMessagesTokenUsageFn fn = new CollectMessagesTokenUsageFn(null, context);

        fn.apply(tree("""
                {
                  "type": "message_start",
                  "message": {
                    "usage": {
                      "input_tokens": 249500,
                      "cache_read_input_tokens": 400,
                      "cache_creation_input_tokens": 100,
                      "cache_creation": { "ephemeral_5m_input_tokens": 0, "ephemeral_1h_input_tokens": 100 },
                      "service_tier": "standard"
                    }
                  }
                }
                """));
        fn.apply(tree("""
                {
                  "type": "message_delta",
                  "usage": { "output_tokens": 8 }
                }
                """));

        JsonNode pricingUsageNode = context.getPricingUsageNode();
        assertNotNull(pricingUsageNode);
        JsonNode usage = pricingUsageNode.path("usage");
        assertEquals(249500, usage.path("input_tokens").asLong());
        assertEquals(400, usage.path("cache_read_input_tokens").asLong());
        assertEquals(100, usage.path("cache_creation_input_tokens").asLong());
        assertEquals(100, usage.path("cache_creation").path("ephemeral_1h_input_tokens").asLong());
        assertEquals("standard", usage.path("service_tier").asText());
        assertEquals(8, usage.path("output_tokens").asLong());
    }

    private static JsonNode tree(String json) throws JsonProcessingException {
        return ProxyUtil.MAPPER.readTree(json);
    }
}
