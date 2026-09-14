package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
