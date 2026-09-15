package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An application serves every interface type a model does — the deployment lookup behind
 * {@code /openai/v1/responses} and {@code /anthropic/v1/messages} resolves applications too — and
 * {@code interfaces} is the whitelist saying which ones it serves.
 */
class ApplicationInterfacesApiTest extends ResourceBaseTest {

    private static final String RESPONSES_BODY = "{\"id\":\"resp_1\",\"object\":\"response\",\"output\":[],"
            + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
    private static final String MESSAGES_BODY = "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
            + "\"content\":[],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
    private static final String COMPLETIONS_BODY = "{\"id\":\"cmpl_1\",\"object\":\"chat.completion\","
            + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\","
            + "\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}],"
            + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";

    @Test
    @DialConfigLocation("dial-config/application-interfaces.json")
    void applicationServesTheResponsesApi() {
        AtomicReference<String> capturedPath = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/openai/v1/responses", request -> {
                capturedPath.set(request.getPath());
                return TestWebServer.createResponse(200, RESPONSES_BODY, "Content-Type", "application/json");
            });

            Response response = send(HttpMethod.POST, "/openai/v1/responses", null,
                    "{\"model\":\"app-multi-interface\",\"store\":false,\"input\":\"hello\"}",
                    "Content-Type", "application/json");

            assertEquals(200, response.status(), response.body());
            assertEquals("/openai/v1/responses", capturedPath.get());
        }
    }

    @Test
    @DialConfigLocation("dial-config/application-interfaces.json")
    void applicationServesTheAnthropicMessagesApi() {
        AtomicReference<String> capturedPath = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/anthropic/v1/messages", request -> {
                capturedPath.set(request.getPath());
                return TestWebServer.createResponse(200, MESSAGES_BODY, "Content-Type", "application/json");
            });

            Response response = send(HttpMethod.POST, "/anthropic/v1/messages", null,
                    "{\"model\":\"app-multi-interface\",\"max_tokens\":16,"
                            + "\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}",
                    "Content-Type", "application/json");

            assertEquals(200, response.status(), response.body());
            assertEquals("/anthropic/v1/messages", capturedPath.get());
        }
    }

    @Test
    @DialConfigLocation("dial-config/application-interfaces.json")
    void applicationDeclaringOnlyChatCompletionsServesNothingElse() {
        try (TestWebServer server = new TestWebServer(4848)) {
            Response response = send(HttpMethod.POST, "/openai/v1/responses", null,
                    "{\"model\":\"app-chat-only\",\"store\":false,\"input\":\"hello\"}",
                    "Content-Type", "application/json");

            assertEquals(503, response.status(), response.body());
        }
    }

    /**
     * The write API accepts the same shapes the config file does: an application whose only routing target
     * is {@code interfaces} — here an entry claiming the deployment-level {@code baseUrl} — is stored and
     * serves, with no legacy {@code endpoint} to satisfy the older validation.
     */
    @Test
    void applicationWrittenThroughTheApiServesInterfacesAsTheOnlyRoutingTarget() {
        String completionsPath = "/openai/deployments/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST"
                + "/interfaces-only-app/chat/completions";
        AtomicReference<String> capturedPath = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, completionsPath, request -> {
                capturedPath.set(request.getPath());
                return TestWebServer.createResponse(200, COMPLETIONS_BODY, "Content-Type", "application/json");
            });

            Response created = send(HttpMethod.PUT,
                    "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/interfaces-only-app", null,
                    "{\"baseUrl\":\"http://localhost:4848\", \"interfaces\":{\"openaiChatCompletions\":{}}}");
            assertEquals(200, created.status(), created.body());

            Response response = send(HttpMethod.POST, completionsPath, null,
                    "{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}], \"max_tokens\":16, \"stream\":false}",
                    "Content-Type", "application/json");

            assertEquals(200, response.status(), response.body());
            assertEquals(completionsPath, capturedPath.get());
        }
    }

    @Test
    void anInterfacesEntryWithoutAnyUrlIsNoRoutingTarget() {
        Response response = send(HttpMethod.PUT,
                "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/no-url-app", null,
                "{\"interfaces\":{\"openaiChatCompletions\":{}}}");

        assertEquals(400, response.status(), response.body());
        assertTrue(response.body().contains("At least application endpoint"), response.body());
    }
}
