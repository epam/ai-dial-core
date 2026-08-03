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

    private static JsonNode tree(String json) throws JsonProcessingException {
        return ProxyUtil.MAPPER.readTree(json);
    }
}
