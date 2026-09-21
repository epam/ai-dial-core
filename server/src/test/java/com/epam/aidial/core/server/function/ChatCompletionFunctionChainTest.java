package com.epam.aidial.core.server.function;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.when;

/**
 * Exercises the real function chain wired by DeploymentPostController for streaming
 * {@code /chat/completions} responses (StripUsagePerModelFn -&gt; CollectResponseChatCompletionAttachmentsFn
 * -&gt; CollectChatCompletionUsageFn), composed the same way {@code BufferingReadStream.BaseEventListener}
 * chains it via {@code Future.compose}. Regression test for a null tree silently produced by one
 * function and blindly dereferenced by the next.
 */
@ExtendWith(MockitoExtension.class)
class ChatCompletionFunctionChainTest {

    @Mock
    private ProxyContext context;

    @Test
    public void testChainToleratesChunksWithNoAttachmentsAndCollectsUsage() throws JsonProcessingException {
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setPerRequestKey("per-request-key");
        when(context.getApiKeyData()).thenReturn(apiKeyData);
        when(context.isStreamingRequest()).thenReturn(true);
        doCallRealMethod().when(context).setPricingUsageNode(any());
        doCallRealMethod().when(context).getPricingUsageNode();

        List<BaseResponseFunction> functions = List.of(
                new StripUsagePerModelFn(null, context),
                new CollectResponseChatCompletionAttachmentsFn(null, context),
                new CollectChatCompletionUsageFn(null, context));

        Future<JsonNode> contentChunk = process(functions, """
                { "choices": [{ "index": 0, "delta": { "content": "hi" } }] }
                """);
        assertTrue(contentChunk.succeeded(), () -> "chain failed: " + contentChunk.cause());

        Future<JsonNode> usageChunk = process(functions, """
                {
                  "choices": [{ "index": 0, "delta": {} }],
                  "usage": { "completion_tokens": 33, "prompt_tokens": 19, "total_tokens": 52 }
                }
                """);
        assertTrue(usageChunk.succeeded(), () -> "chain failed: " + usageChunk.cause());

        JsonNode pricingUsageNode = context.getPricingUsageNode();
        assertNotNull(pricingUsageNode);
        assertEquals(52, pricingUsageNode.path("usage").path("total_tokens").asLong());
    }

    private static Future<JsonNode> process(List<BaseResponseFunction> functions, String json) throws JsonProcessingException {
        Future<JsonNode> result = Future.succeededFuture(ProxyUtil.MAPPER.readTree(json));
        for (BaseResponseFunction fn : functions) {
            result = result.compose(fn);
        }
        return result;
    }
}
