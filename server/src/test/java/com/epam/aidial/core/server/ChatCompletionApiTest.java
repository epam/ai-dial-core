package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the OpenAI-native {@code POST /openai/v1/chat/completions} endpoint: same behavior as
 * {@code /openai/deployments/{id}/chat/completions}, but the deployment is resolved from the
 * request body's {@code model} field instead of the URL path.
 */
public class ChatCompletionApiTest extends ResourceBaseTest {

    @Test
    public void testChatCompletions_Success() {
        String answer = "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"gpt-35-turbo\","
                + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}]}";
        String body = "{\"model\": \"gpt-3-turbo\", \"messages\": [{\"role\": \"user\", \"content\": \"hi\"}]}";
        AtomicReference<String> upstreamBody = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/chat/completions", request -> {
                upstreamBody.set(request.getBody().readUtf8());
                return TestWebServer.createResponse(200, answer, "Content-Type", "application/json");
            });

            Response response = send(HttpMethod.POST, "/openai/v1/chat/completions", null, body, "content-type", "application/json");

            verify(response, 200);
            assertTrue(response.body().contains("\"content\":\"hi\""));
            // nothing changed the body, so the upstream gets the received bytes
            assertEquals(body, upstreamBody.get());
        }
    }

    @Test
    public void testChatCompletions_MissingModel_BadRequest() {
        Response response = send(HttpMethod.POST, "/openai/v1/chat/completions", null,
                "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                "content-type", "application/json");

        verify(response, 400);
    }

    @Test
    public void testChatCompletions_UnknownModel_NotFound() {
        Response response = send(HttpMethod.POST, "/openai/v1/chat/completions", null,
                "{\"model\":\"no-such-model\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                "content-type", "application/json");

        verify(response, 404);
    }
}
