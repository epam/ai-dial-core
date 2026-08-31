package com.epam.aidial.core.server.function;

import com.epam.aidial.core.server.ProxyContext;
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
class CollectChatCompletionUsageFnTest {

    @Mock
    private ProxyContext context;

    @Test
    public void testMergesUsageServiceTierAndCustomFieldsAcrossChunks() throws JsonProcessingException {
        doCallRealMethod().when(context).setPricingUsageNode(any());
        doCallRealMethod().when(context).getPricingUsageNode();

        CollectChatCompletionUsageFn fn = new CollectChatCompletionUsageFn(null, context);

        fn.apply(tree("""
                {
                  "choices": [{ "index": 0, "delta": { "content": "hi" } }],
                  "service_tier": "flex"
                }
                """));
        fn.apply(tree("""
                {
                  "choices": [{ "index": 0, "delta": {} }],
                  "usage": {
                    "prompt_tokens": 150000,
                    "prompt_tokens_details": { "cached_tokens": 800, "cache_write_tokens": 50 },
                    "completion_tokens": 200
                  },
                  "custom_fields": {
                    "upstream_usage": { "interface": "anthropicMessages", "usage": { "input_tokens": 1 } }
                  }
                }
                """));

        JsonNode pricingUsageNode = context.getPricingUsageNode();
        assertNotNull(pricingUsageNode);
        assertEquals(150000, pricingUsageNode.path("usage").path("prompt_tokens").asLong());
        assertEquals(800, pricingUsageNode.path("usage").path("prompt_tokens_details").path("cached_tokens").asLong());
        assertEquals("flex", pricingUsageNode.path("service_tier").asText());
        assertEquals("anthropicMessages", pricingUsageNode.path("custom_fields").path("upstream_usage").path("interface").asText());
    }

    @Test
    public void testPassesEventThroughUnchanged() throws JsonProcessingException {
        CollectChatCompletionUsageFn fn = new CollectChatCompletionUsageFn(null, context);
        JsonNode input = tree("""
                { "choices": [{ "index": 0, "delta": { "content": "hi" } }] }
                """);

        JsonNode output = fn.apply(input).result();

        assertEquals(input, output);
    }

    private static JsonNode tree(String json) throws JsonProcessingException {
        return ProxyUtil.MAPPER.readTree(json);
    }
}
