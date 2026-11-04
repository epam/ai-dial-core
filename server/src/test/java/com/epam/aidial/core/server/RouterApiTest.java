package com.epam.aidial.core.server;

import com.epam.aidial.core.server.security.ExtractedClaims;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.Future;
import io.vertx.core.http.HttpMethod;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A router is served like an application on every API that accepts a model id, is never limit-checked itself,
 * receives the caller's identity in {@code X-DIAL-USER-INFO}, and is listed only by {@code /v1/routers}.
 */
class RouterApiTest extends ResourceBaseTest {

    private static final String CONFIG = "dial-config/routers.json";
    private static final String CHAT_BODY = "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    private static final String COMPLETION = "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\","
            + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}],"
            + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":10,\"total_tokens\":15}}";
    private static final String MESSAGE = "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
            + "\"content\":[],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
    private static final String USER_INFO = "X-DIAL-USER-INFO";

    @Test
    @DialConfigLocation(CONFIG)
    void chatCompletionProxiedToTheRouter() throws Exception {
        List<RecordedRequest> received = Collections.synchronizedList(new ArrayList<>());
        try (TestWebServer server = new TestWebServer(4848, recording(received, COMPLETION))) {
            Response response = send(HttpMethod.POST, "/openai/deployments/r1/chat/completions", null, CHAT_BODY,
                    "content-type", "application/json");

            verify(response, 200);
            assertEquals(COMPLETION, response.body());
        }
        assertEquals(1, received.size());
        RecordedRequest request = received.getFirst();
        assertEquals("/openai/deployments/r1/chat/completions", request.getPath());
        String perRequestKey = request.getHeaders().get("api-key");
        assertNotNull(perRequestKey);
        assertNotEquals("proxyKey1", perRequestKey);
        assertTrue(ProxyUtil.MAPPER.readTree(request.getHeaders().get("X-DIAL-DEPLOYMENT-FEATURES")).isObject());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void messagesRequestProxiedToTheRouter() {
        List<RecordedRequest> received = Collections.synchronizedList(new ArrayList<>());
        try (TestWebServer server = new TestWebServer(4848, recording(received, MESSAGE))) {
            Response response = sendMessages();

            verify(response, 200);
        }
        assertEquals(1, received.size());
        assertEquals("/anthropic/v1/messages", received.getFirst().getPath());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void embeddingsProxiedToTheRouter() {
        String embeddings = "{\"object\":\"list\",\"data\":[],\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}";
        List<RecordedRequest> received = Collections.synchronizedList(new ArrayList<>());
        try (TestWebServer server = new TestWebServer(4848, recording(received, embeddings))) {
            Response response = send(HttpMethod.POST, "/openai/deployments/r1/embeddings", null, "{\"input\":\"hi\"}",
                    "content-type", "application/json");

            verify(response, 200);
            assertEquals(embeddings, response.body());
        }
        assertEquals(1, received.size());
        assertEquals("/openai/deployments/r1/embeddings", received.getFirst().getPath());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void interfaceTheRouterDoesNotDeclare() {
        List<RecordedRequest> received = Collections.synchronizedList(new ArrayList<>());
        try (TestWebServer server = new TestWebServer(4848, recording(received, "{}"))) {
            Response response = send(HttpMethod.POST, "/openai/v1/responses", null,
                    "{\"model\":\"r1\",\"store\":false,\"input\":\"hi\"}", "content-type", "application/json");

            verify(response, 503);
        }
        assertTrue(received.isEmpty());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void exhaustedRequestLimitOnTheRouterDoesNotRefuse() {
        List<RecordedRequest> received = Collections.synchronizedList(new ArrayList<>());
        try (TestWebServer server = new TestWebServer(4848, recording(received, COMPLETION))) {
            for (int i = 0; i < 2; i++) {
                Response response = send(HttpMethod.POST, "/openai/v1/chat/completions", null,
                        "{\"model\":\"r1\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}", "content-type", "application/json");
                verify(response, 200);
            }
        }
        assertEquals(2, received.size());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void routerNotCheckedOnTheMessagesApi() {
        List<RecordedRequest> received = Collections.synchronizedList(new ArrayList<>());
        try (TestWebServer server = new TestWebServer(4848, recording(received, MESSAGE))) {
            verify(sendMessages(), 200);
            verify(sendMessages(), 200);
        }
        assertEquals(2, received.size());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void modelCalledBackIsStillCharged() throws Exception {
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/openai/deployments/gpt-4/chat/completions", 200, COMPLETION);
            // the router answers by calling the model back through Core with the per-request key it received
            server.map(HttpMethod.POST, "/openai/deployments/r1/chat/completions", request -> {
                Response callback = send(HttpMethod.POST, "/openai/deployments/gpt-4/chat/completions", null, CHAT_BODY,
                        "api-key", request.getHeaders().get("api-key"), "content-type", "application/json");
                return TestWebServer.createResponse(callback.status(), callback.body(), "content-type", "application/json");
            });

            verify(send(HttpMethod.POST, "/openai/deployments/r1/chat/completions", null, CHAT_BODY,
                    "content-type", "application/json"), 200);
        }

        Response limits = send(HttpMethod.GET, "/v1/deployments/gpt-4/limits", null, null);
        verify(limits, 200);
        assertEquals(15, ProxyUtil.MAPPER.readTree(limits.body()).get("minuteTokenStats").get("used").asLong());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void jwtCaller() throws Exception {
        stubJwt("u1-token", "u1");
        List<RecordedRequest> received = Collections.synchronizedList(new ArrayList<>());
        try (TestWebServer server = new TestWebServer(4848, recording(received, COMPLETION))) {
            verify(send(HttpMethod.POST, "/openai/deployments/r1/chat/completions", null, CHAT_BODY,
                    "authorization", "u1-token", USER_INFO, "{\"roles\": [\"admin\"]}", "content-type", "application/json"), 200);
        }
        List<String> values = received.getFirst().getHeaders().values(USER_INFO);
        assertEquals(1, values.size(), values.toString());
        JsonNode userInfo = ProxyUtil.MAPPER.readTree(values.getFirst());
        assertEquals("u1", userInfo.get("userId").asText());
        assertEquals(List.of("analysts", "b"), ProxyUtil.MAPPER.convertValue(userInfo.get("roles"), List.class));
        assertNull(userInfo.get("project"));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void nonAsciiUserIdIsEscaped() throws Exception {
        stubJwt("u2-token", "Jürgen");
        List<RecordedRequest> received = Collections.synchronizedList(new ArrayList<>());
        try (TestWebServer server = new TestWebServer(4848, recording(received, COMPLETION))) {
            verify(send(HttpMethod.POST, "/openai/deployments/r1/chat/completions", null, CHAT_BODY,
                    "authorization", "u2-token", "content-type", "application/json"), 200);
        }
        String header = received.getFirst().getHeaders().get(USER_INFO);
        assertTrue(header.contains("J\\u00FCrgen"), header);
        assertEquals("Jürgen", ProxyUtil.MAPPER.readTree(header).get("userId").asText());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void projectKeyCaller() throws Exception {
        List<RecordedRequest> received = Collections.synchronizedList(new ArrayList<>());
        try (TestWebServer server = new TestWebServer(4848, recording(received, COMPLETION))) {
            verify(send(HttpMethod.POST, "/openai/deployments/r2/chat/completions", null, CHAT_BODY,
                    "api-key", "proxyKey2", "content-type", "application/json"), 200);
        }
        JsonNode userInfo = ProxyUtil.MAPPER.readTree(received.getFirst().getHeaders().get(USER_INFO));
        assertEquals("p2", userInfo.get("project").asText());
        assertEquals(List.of("default"), ProxyUtil.MAPPER.convertValue(userInfo.get("roles"), List.class));
        assertNull(userInfo.get("userId"));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void featureEndpointCallCarriesTheIdentityHeader() throws Exception {
        List<RecordedRequest> received = Collections.synchronizedList(new ArrayList<>());
        try (TestWebServer server = new TestWebServer(4848, recording(received, "{}"))) {
            verify(send(HttpMethod.POST, "/v1/r2/rate", null, "{}", USER_INFO, "{\"roles\": [\"admin\"]}",
                    "content-type", "application/json"), 200);
        }
        assertEquals("/rate", received.getFirst().getPath());
        List<String> values = received.getFirst().getHeaders().values(USER_INFO);
        assertEquals(1, values.size(), values.toString());
        assertEquals("p1", ProxyUtil.MAPPER.readTree(values.getFirst()).get("project").asText());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void responseItemCallCarriesTheIdentityHeader() throws Exception {
        List<RecordedRequest> received = Collections.synchronizedList(new ArrayList<>());
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/openai/v1/responses", recording(received,
                    "{\"id\":\"resp_1\",\"object\":\"response\",\"output\":[],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}"));
            server.map(HttpMethod.GET, "/openai/v1/responses/resp_1", recording(received, "{\"id\":\"resp_1\",\"status\":\"completed\"}"));
            Response created = send(HttpMethod.POST, "/openai/v1/responses", null,
                    "{\"model\":\"r2\",\"store\":true,\"input\":\"hi\"}", "content-type", "application/json");
            verify(created, 200);
            String dialId = ProxyUtil.MAPPER.readTree(created.body()).get("id").asText();

            verify(send(HttpMethod.GET, "/openai/v1/responses/" + dialId, null, null), 200);
        }
        assertEquals(2, received.size());
        RecordedRequest get = received.get(1);
        assertEquals("/openai/v1/responses/resp_1", get.getPath());
        List<String> values = get.getHeaders().values(USER_INFO);
        assertEquals(1, values.size(), values.toString());
        assertEquals("p1", ProxyUtil.MAPPER.readTree(values.getFirst()).get("project").asText());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void identityHeaderNotAddedForApplication() {
        List<RecordedRequest> received = Collections.synchronizedList(new ArrayList<>());
        try (TestWebServer server = new TestWebServer(4848, recording(received, COMPLETION))) {
            verify(send(HttpMethod.POST, "/openai/deployments/app/chat/completions", null, CHAT_BODY,
                    "content-type", "application/json"), 200);
        }
        assertNull(received.getFirst().getHeaders().get(USER_INFO));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void clientIdentityHeaderDroppedForModel() {
        List<RecordedRequest> received = Collections.synchronizedList(new ArrayList<>());
        try (TestWebServer server = new TestWebServer(4848, recording(received, COMPLETION))) {
            verify(send(HttpMethod.POST, "/openai/deployments/gpt-4/chat/completions", null, CHAT_BODY,
                    USER_INFO, "{\"roles\": [\"admin\"]}", "content-type", "application/json"), 200);
        }
        assertNull(received.getFirst().getHeaders().get(USER_INFO));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void listingFilteredByRole() throws Exception {
        Response response = send(HttpMethod.GET, "/v1/routers", null, null, "api-key", "proxyKey2");

        verify(response, 200);
        JsonNode data = ProxyUtil.MAPPER.readTree(response.body()).get("data");
        assertEquals(1, data.size(), response.body());
        JsonNode r2 = data.get(0);
        assertEquals("r2", r2.get("id").asText());
        assertEquals("router", r2.get("object").asText());
        assertFalse(r2.has("base_url"), response.body());
        assertFalse(r2.has("endpoint"), response.body());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void singleRouterByRole() throws Exception {
        verify(send(HttpMethod.GET, "/v1/routers/r1", null, null, "api-key", "proxyKey2"), 403);

        Response r2 = send(HttpMethod.GET, "/v1/routers/r2", null, null, "api-key", "proxyKey2");
        verify(r2, 200);
        assertEquals("r2", ProxyUtil.MAPPER.readTree(r2.body()).get("id").asText());

        verify(send(HttpMethod.GET, "/v1/routers/missing", null, null, "api-key", "proxyKey2"), 404);
    }

    @Test
    @DialConfigLocation(CONFIG)
    void routerHiddenFromTheModelAndDeploymentListings() {
        for (String listing : List.of("/openai/models", "/openai/deployments", "/openai/applications", "/v1/deployments",
                "/v1/deployment-names", "/anthropic/v1/models")) {
            Response response = send(HttpMethod.GET, listing, null, null);
            verify(response, 200);
            assertFalse(response.body().contains("\"r1\""), listing + ": " + response.body());
        }
        verify(send(HttpMethod.GET, "/v1/deployments/r1", null, null), 404);
    }

    private Response sendMessages() {
        return send(HttpMethod.POST, "/anthropic/v1/messages", null,
                "{\"model\":\"r1\",\"max_tokens\":16,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                "content-type", "application/json");
    }

    private void stubJwt(String token, String userId) {
        ExtractedClaims claims = new ExtractedClaims(userId, List.of("analysts", "b"), userId,
                ProxyUtil.MAPPER.createObjectNode(), null, null);
        Mockito.doReturn(Future.succeededFuture(claims)).when(validator).extractClaims(token);
    }

    private static TestWebServer.Handler recording(List<RecordedRequest> received, String body) {
        return request -> {
            received.add(request);
            return TestWebServer.createResponse(200, body, "content-type", "application/json");
        };
    }
}
