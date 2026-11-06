package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage for {@code interfaces.<type>.overridePaths}: a request on the standard Core
 * path reaches the upstream on the overridden path, per API family.
 */
class OverridePathsApiTest extends ResourceBaseTest {

    @Test
    @DialConfigLocation("dial-config/override-paths.json")
    void streamingGetPreservesQueryAndRewritesResponseIdOnOverriddenPath() throws Exception {
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/v1/responses", request ->
                    TestWebServer.createResponse(200,
                            "{\"id\":\"stream_1\",\"object\":\"response\",\"output\":[]}",
                            "Content-Type", "application/json"));
            server.map(HttpMethod.GET, "/v1/responses/stream_1?stream=true&starting_after=3", request ->
                    TestWebServer.createResponse(200,
                            "event: response.completed\ndata: {\"response\":{\"id\":\"stream_1\",\"status\":\"completed\",\"output\":[]}}\n\n",
                            "Content-Type", "text/event-stream"));
            Response created = send(HttpMethod.POST, "/openai/v1/responses", null,
                    "{\"model\":\"responses-switchyard\",\"store\":true,\"input\":\"hello\"}",
                    "Content-Type", "application/json");
            assertEquals(200, created.status(), created.body());
            String dialId = ProxyUtil.MAPPER.readTree(created.body()).path("id").asText();
            Response response = send(HttpMethod.GET,
                    "/openai/v1/responses/" + dialId + "?stream=true&starting_after=3", null, null);
            assertEquals(200, response.status(), response.body());
            assertTrue(response.body().contains(dialId), response.body());
            assertFalse(response.body().contains("\"stream_1\""), response.body());
        }
    }

    /**
     * A deployment written through the Configuration API lives in blob storage rather than the config
     * file, so it reaches routing by a different road: serialized for the blob, decoded, and merged into
     * the live config. This pins {@code overridePaths} surviving that round trip — it uses the default
     * config rather than a {@code @DialConfigLocation} one precisely because the entity under test is
     * written at runtime.
     */
    @Test
    void blobPersistedModelHonorsOverridePaths() {
        String spec = """
                {
                  "type": "chat",
                  "overrideName": "blob-upstream-name",
                  "interfaces": {
                    "openaiChatCompletions": {
                      "base_url": "http://localhost:4848",
                      "overridePaths": {
                        "postAzureOpenaiChatCompletions": "/v1/{overrideName}/chat/completions"
                      }
                    }
                  }
                }
                """;
        Response put = send(HttpMethod.PUT, "/v1/models/platform/blob-override-model", null, spec,
                "authorization", "admin", "If-None-Match", "*");
        assertEquals(200, put.status(), put.body());

        Response stored = send(HttpMethod.GET, "/v1/models/platform/blob-override-model", null, null,
                "authorization", "admin");
        assertEquals(200, stored.status(), stored.body());
        assertTrue(stored.body().contains("\"postAzureOpenaiChatCompletions\":\"/v1/{overrideName}/chat/completions\""),
                stored.body());

        AtomicReference<String> capturedPath = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/v1/blob-upstream-name/chat/completions", request -> {
                capturedPath.set(request.getPath());
                return TestWebServer.createResponse(200,
                        "{\"id\":\"c1\",\"object\":\"chat.completion\",\"choices\":[],"
                                + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}",
                        "Content-Type", "application/json");
            });

            Response chat = send(HttpMethod.POST, "/openai/deployments/blob-override-model/chat/completions", null,
                    "{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}",
                    "Content-Type", "application/json");

            assertEquals(200, chat.status(), chat.body());
            assertEquals("/v1/blob-upstream-name/chat/completions", capturedPath.get());
        }
    }

    /** The same write surface refuses a template routing could not render. */
    @Test
    void blobWriteRejectsAnUnrenderableTemplate() {
        String spec = """
                {
                  "type": "chat",
                  "interfaces": {
                    "openaiResponses": {
                      "base_url": "http://localhost:4848",
                      "overridePaths": {"postOpenaiResponses": "/v1/{id}/responses"}
                    }
                  }
                }
                """;
        Response put = send(HttpMethod.PUT, "/v1/models/platform/blob-bad-override", null, spec,
                "authorization", "admin", "If-None-Match", "*");

        assertEquals(422, put.status(), put.body());
        assertTrue(put.body().contains("overridePaths.postOpenaiResponses"), put.body());
    }

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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DialConfigLocation("dial-config/override-paths.json")
    void responsesCreateAndGetByIdRoutedToTheOverriddenPaths(boolean overrideAllOperations) throws Exception {
        String model = overrideAllOperations ? "responses-fully-overridden" : "responses-switchyard";
        String cancelPath = overrideAllOperations ? "/custom/cancel/resp_1" : "/openai/v1/responses/resp_1/cancel";
        String deletePath = overrideAllOperations ? "/custom/delete/resp_1" : "/openai/v1/responses/resp_1";
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
            server.map(HttpMethod.POST, cancelPath, request -> {
                capturedPath.set(request.getPath());
                return TestWebServer.createResponse(200, "{\"id\":\"resp_1\",\"status\":\"cancelled\"}",
                        "Content-Type", "application/json");
            });
            server.map(HttpMethod.DELETE, deletePath, request -> {
                capturedPath.set(request.getPath());
                return TestWebServer.createResponse(200, "{\"id\":\"resp_1\",\"deleted\":true}",
                        "Content-Type", "application/json");
            });

            Response response = send(HttpMethod.POST, "/openai/v1/responses", null,
                    "{\"model\":\"" + model + "\",\"store\":true,\"input\":\"hello\"}",
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

            // Verify both explicit operation overrides and fallback when only create/GET are overridden.
            response = send(HttpMethod.POST, "/openai/v1/responses/" + dialId + "/cancel", null, null);
            assertEquals(200, response.status(), response.body());
            assertEquals(cancelPath, capturedPath.get());

            response = send(HttpMethod.DELETE, "/openai/v1/responses/" + dialId, null, null);
            assertEquals(200, response.status(), response.body());
            assertEquals(deletePath, capturedPath.get());
        }
    }
}
