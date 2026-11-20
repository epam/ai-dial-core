package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * deployment-aliases "Target pass served in process": Core's own address names a port nothing listens on, so a request
 * an alias still sent there over HTTP fails with 502, and one served in process answers.
 */
class AliasInProcessApiTest extends ResourceBaseTest {

    private static final String CONFIG = "dial-config/aliases.json";
    private static final String ROUTER_CONFIG = "dial-config/router-decision.json";

    private static final String CHAT_REQUEST = "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    private static final String CHAT_RESPONSE = "{\"id\":\"cmpl_1\",\"object\":\"chat.completion\","
            + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\","
            + "\"message\":{\"role\":\"assistant\",\"content\":\"hello\"}}],"
            + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
    private static final String RESPONSES_BODY = "{\"id\":\"resp_1\",\"object\":\"response\",\"output\":[],"
            + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
    private static final String MESSAGES_BODY = "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
            + "\"content\":[],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";

    private final List<RecordedRequest> received = new CopyOnWriteArrayList<>();

    @Override
    protected JsonObject additionalSettingsOverrides() {
        return new JsonObject().put("backgroundJob", new JsonObject().put("localBaseUrl", "http://localhost:1"));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void chatCompletionThroughAnAlias() {
        Response response;
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/openai/deployments/gpt-4/chat/completions", 200, CHAT_RESPONSE,
                    "Content-Type", "application/json");
            response = send(HttpMethod.POST, "/openai/deployments/assistant/chat/completions", null, CHAT_REQUEST,
                    "api-key", "default-key", "Content-Type", "application/json");
        }
        verify(response, 200);
        assertEquals("hello", new JsonObject(response.body()).getJsonArray("choices").getJsonObject(0)
                .getJsonObject("message").getString("content"));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void responsesAndMessagesThroughAnAlias() {
        List<String> models = new CopyOnWriteArrayList<>();
        try (TestWebServer server = new TestWebServer(4848)) {
            for (String path : List.of("/openai/v1/responses", "/openai/responses")) {
                server.map(HttpMethod.POST, path, request -> {
                    models.add(new JsonObject(request.getBody().readUtf8()).getString("model"));
                    return TestWebServer.createResponse(200, RESPONSES_BODY, "Content-Type", "application/json");
                });
            }
            server.map(HttpMethod.POST, "/anthropic/v1/messages", request -> {
                models.add(new JsonObject(request.getBody().readUtf8()).getString("model"));
                return TestWebServer.createResponse(200, MESSAGES_BODY, "Content-Type", "application/json");
            });

            for (String path : List.of("/openai/v1/responses", "/openai/responses")) {
                verify(send(HttpMethod.POST, path, null, "{\"model\":\"multi-alias\",\"store\":false,\"input\":\"hello\"}",
                        "api-key", "default-key", "Content-Type", "application/json"), 200);
            }
            verify(send(HttpMethod.POST, "/anthropic/v1/messages", null,
                    "{\"model\":\"multi-alias\",\"max_tokens\":16,\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}",
                    "api-key", "default-key", "Content-Type", "application/json"), 200);
        }
        assertEquals(List.of("multi-model", "multi-model", "multi-model"), models);
    }

    @Test
    @DialConfigLocation(ROUTER_CONFIG)
    void routerInDecisionModeBehindAnAlias() {
        Response response;
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/decide", 200, "{\"candidates\": [\"alias-m1\"]}", "Content-Type", "application/json");
            server.map(HttpMethod.POST, "/openai/deployments/m1/chat/completions", request -> {
                received.add(request);
                return TestWebServer.createResponse(200, CHAT_RESPONSE, "Content-Type", "application/json");
            });
            response = send(HttpMethod.POST, "/openai/deployments/strong/chat/completions", null, CHAT_REQUEST,
                    "api-key", "proxyKey1", "Content-Type", "application/json");
        }
        verify(response, 200);
        assertEquals(1, received.size());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void requestsThatStayOnHttp() {
        Response tokenize;
        Response countTokens;
        Response background;
        try (TestWebServer server = new TestWebServer(4848, request -> {
            received.add(request);
            return TestWebServer.createResponse(200, "{}", "Content-Type", "application/json");
        })) {
            tokenize = send(HttpMethod.POST, "/v1/deployments/featured-alias/tokenize", null, "{\"inputs\":[]}",
                    "api-key", "default-key", "Content-Type", "application/json");
            countTokens = send(HttpMethod.POST, "/anthropic/v1/messages/count_tokens", null,
                    "{\"model\":\"multi-alias\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}",
                    "api-key", "default-key", "Content-Type", "application/json");
            background = send(HttpMethod.POST, "/openai/v1/responses", null,
                    "{\"model\":\"multi-alias\",\"background\":true,\"input\":\"hello\"}",
                    "api-key", "default-key", "Content-Type", "application/json");
        }
        verify(tokenize, 502);
        verify(countTokens, 502);
        verify(background, 502);
        assertTrue(received.isEmpty(), () -> received.stream().map(RecordedRequest::getPath).toList().toString());
    }
}
