package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * End-to-end coverage for {@code interfaces.<type>.overridePaths}: a request on the standard Core
 * path reaches the upstream on the overridden path, per API family.
 */
class OverridePathsApiTest extends ResourceBaseTest {

    @Test
    @DialConfigLocation("dial-config/override-paths.json")
    void chatCompletionsRoutedToTheOverriddenPathWithOverrideName() {
        AtomicReference<String> capturedPath = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/openai/deployments/switchyard-gpt/v1/chat/completions", request -> {
                capturedPath.set(request.getPath());
                return TestWebServer.createResponse(200, "{}", "Content-Type", "application/json");
            });

            Response response = send(HttpMethod.POST, "/openai/deployments/gpt-switchyard/chat/completions", null,
                    "{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],\"max_tokens\":16}",
                    "Content-Type", "application/json");

            assertEquals(200, response.status(), response.body());
            assertEquals("/openai/deployments/switchyard-gpt/v1/chat/completions", capturedPath.get());
        }
    }

    @Test
    @DialConfigLocation("dial-config/override-paths.json")
    void embeddingsRoutedToTheOverriddenPath() {
        AtomicReference<String> capturedPath = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/v1/embeddings", request -> {
                capturedPath.set(request.getPath());
                return TestWebServer.createResponse(200, "{}", "Content-Type", "application/json");
            });

            Response response = send(HttpMethod.POST, "/openai/deployments/emb-switchyard/embeddings", null,
                    "{\"input\":\"hello\"}", "Content-Type", "application/json");

            assertEquals(200, response.status(), response.body());
            assertEquals("/v1/embeddings", capturedPath.get());
        }
    }

    @Test
    @DialConfigLocation("dial-config/override-paths.json")
    void anthropicMessagesAndCountTokensRoutedToTheOverriddenPaths() {
        AtomicReference<String> capturedPath = new AtomicReference<>();
        String body = "{\"model\":\"claude-switchyard\",\"max_tokens\":16,"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}";
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/v1/messages", request -> {
                capturedPath.set(request.getPath());
                return TestWebServer.createResponse(200,
                        "{\"id\":\"msg_01\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],"
                                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}",
                        "Content-Type", "application/json");
            });
            server.map(HttpMethod.POST, "/v1/messages/count_tokens", request -> {
                capturedPath.set(request.getPath());
                return TestWebServer.createResponse(200, "{\"input_tokens\":5}", "Content-Type", "application/json");
            });

            Response response = send(HttpMethod.POST, "/anthropic/v1/messages", null, body,
                    "Content-Type", "application/json");
            assertEquals(200, response.status(), response.body());
            assertEquals("/v1/messages", capturedPath.get());

            response = send(HttpMethod.POST, "/anthropic/v1/messages/count_tokens", null, body,
                    "Content-Type", "application/json");
            assertEquals(200, response.status(), response.body());
            assertEquals("/v1/messages/count_tokens", capturedPath.get());
        }
    }

    @Test
    @DialConfigLocation("dial-config/override-paths.json")
    void applicationRoutedToTheOverriddenPath() {
        AtomicReference<String> capturedPath = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/v1/chat/completions", request -> {
                capturedPath.set(request.getPath());
                return TestWebServer.createResponse(200, "{}", "Content-Type", "application/json");
            });

            Response response = send(HttpMethod.POST, "/openai/deployments/app-switchyard/chat/completions", null,
                    "{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],\"max_tokens\":16}",
                    "Content-Type", "application/json");

            assertEquals(200, response.status(), response.body());
            assertEquals("/v1/chat/completions", capturedPath.get());
        }
    }

    @Test
    @DialConfigLocation("dial-config/override-paths.json")
    void responsesCreateAndGetByIdRoutedToTheOverriddenPaths() throws Exception {
        AtomicReference<String> capturedPath = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/v1/responses", request -> {
                capturedPath.set(request.getPath());
                return TestWebServer.createResponse(200,
                        "{\"id\":\"resp_1\",\"object\":\"response\",\"output\":[],"
                                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}",
                        "Content-Type", "application/json");
            });
            server.map(HttpMethod.GET, "/v1/responses/resp_1", request -> {
                capturedPath.set(request.getPath());
                return TestWebServer.createResponse(200, "{\"id\":\"resp_1\",\"status\":\"completed\"}",
                        "Content-Type", "application/json");
            });
            server.map(HttpMethod.POST, "/openai/v1/responses/resp_1/cancel", request -> {
                capturedPath.set(request.getPath());
                return TestWebServer.createResponse(200, "{\"id\":\"resp_1\",\"status\":\"cancelled\"}",
                        "Content-Type", "application/json");
            });
            server.map(HttpMethod.DELETE, "/openai/v1/responses/resp_1", request -> {
                capturedPath.set(request.getPath());
                return TestWebServer.createResponse(200, "{\"id\":\"resp_1\",\"deleted\":true}",
                        "Content-Type", "application/json");
            });

            Response response = send(HttpMethod.POST, "/openai/v1/responses", null,
                    "{\"model\":\"responses-switchyard\",\"store\":true,\"input\":\"hello\"}",
                    "Content-Type", "application/json");
            assertEquals(200, response.status(), response.body());
            assertEquals("/v1/responses", capturedPath.get());

            // the create response carries the dial id the item operations are addressed by
            JsonNode created = ProxyUtil.MAPPER.readTree(response.body());
            String dialId = created.path("id").asText();
            assertNotNull(dialId);

            response = send(HttpMethod.GET, "/openai/v1/responses/" + dialId, null, null);
            assertEquals(200, response.status(), response.body());
            // {id} in getOpenaiResponsesById renders the upstream response id, not the dial one
            assertEquals("/v1/responses/resp_1", capturedPath.get());

            // cancel and delete carry no override of their own here, so they keep the default path
            response = send(HttpMethod.POST, "/openai/v1/responses/" + dialId + "/cancel", null, null);
            assertEquals(200, response.status(), response.body());
            assertEquals("/openai/v1/responses/resp_1/cancel", capturedPath.get());

            response = send(HttpMethod.DELETE, "/openai/v1/responses/" + dialId, null, null);
            assertEquals(200, response.status(), response.body());
            assertEquals("/openai/v1/responses/resp_1", capturedPath.get());
        }
    }
}
