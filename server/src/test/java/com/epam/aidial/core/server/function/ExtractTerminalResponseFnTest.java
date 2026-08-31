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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ExtractTerminalResponseFnTest {

    @Mock
    private ProxyContext context;

    @Test
    public void testCapturesPricingUsageNodeOnResponseCompleted() throws JsonProcessingException {
        doCallRealMethod().when(context).setPricingUsageNode(any());
        doCallRealMethod().when(context).getPricingUsageNode();

        ExtractTerminalResponseFn fn = new ExtractTerminalResponseFn(null, context);

        fn.apply(tree("""
                {
                  "type": "response.in_progress"
                }
                """));

        fn.apply(tree("""
                {
                  "type": "response.completed",
                  "response": {
                    "usage": { "input_tokens": 150000, "output_tokens": 200 },
                    "service_tier": "flex"
                  }
                }
                """));

        JsonNode pricingUsageNode = context.getPricingUsageNode();
        assertNotNull(pricingUsageNode);
        assertEquals(150000, pricingUsageNode.path("usage").path("input_tokens").asLong());
        assertEquals("flex", pricingUsageNode.path("service_tier").asText());
    }

    @Test
    public void testIgnoresNonTerminalEvents() throws JsonProcessingException {
        ExtractTerminalResponseFn fn = new ExtractTerminalResponseFn(null, context);

        fn.apply(tree("""
                {
                  "type": "response.output_text.delta",
                  "response": { "usage": { "input_tokens": 1 } }
                }
                """));

        verify(context, never()).setPricingUsageNode(any());
    }

    private static JsonNode tree(String json) throws JsonProcessingException {
        return ProxyUtil.MAPPER.readTree(json);
    }
}
