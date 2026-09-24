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

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Exercises the real function chain wired by DeploymentPostController for streaming
 * {@code /chat/completions} responses (StripUsagePerModelFn -&gt; CollectResponseChatCompletionAttachmentsFn),
 * composed the same way {@code BufferingReadStream.BaseEventListener} chains it via {@code Future.compose}.
 * Regression test for a null tree silently produced by one function and blindly dereferenced by the next.
 */
@ExtendWith(MockitoExtension.class)
class ChatCompletionFunctionChainTest {

    @Mock
    private ProxyContext context;

    @Test
    public void testChainToleratesChunksWithNoAttachments() throws JsonProcessingException {
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setPerRequestKey("per-request-key");
        when(context.getApiKeyData()).thenReturn(apiKeyData);

        List<BaseResponseFunction> functions = List.of(
                new StripUsagePerModelFn(null, context),
                new CollectResponseChatCompletionAttachmentsFn(null, context));

        JsonNode tree = ProxyUtil.MAPPER.readTree("""
                { "choices": [{ "index": 0, "delta": { "content": "hi" } }] }
                """);

        Future<JsonNode> result = process(functions, tree);
        assertTrue(result.succeeded(), () -> "chain failed: " + result.cause());
        assertSame(tree, result.result());
    }

    private static Future<JsonNode> process(List<BaseResponseFunction> functions, JsonNode tree) {
        Future<JsonNode> result = Future.succeededFuture(tree);
        for (BaseResponseFunction fn : functions) {
            result = result.compose(fn);
        }
        return result;
    }
}
