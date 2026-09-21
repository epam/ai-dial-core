package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

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
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/chat/completions", request ->
                    TestWebServer.createResponse(200, answer, "Content-Type", "application/json"));

            Response response = send(HttpMethod.POST, "/openai/v1/chat/completions", null,
                    "{\"model\":\"gpt-3-turbo\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                    "content-type", "application/json");

            verify(response, 200);
            assertTrue(response.body().contains("\"content\":\"hi\""));
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
