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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A router is served like an application on every API that accepts a model id, is never limit-checked itself,
 * gets the caller's identity from {@code GET /v1/user/info} with its key, and is listed by {@code /v1/routers} and
 * the generic {@code /v1/deployments} listings.
 */
class RouterApiTest extends ResourceBaseTest {

    private static final String CONFIG = "dial-config/routers.json";
    private static final String CHAT_BODY = "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    private static final String COMPLETION = "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\","
            + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}],"
            + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":10,\"total_tokens\":15}}";
    private static final String MESSAGE = "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
            + "\"content\":[],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
    private static final String TYPED = "dial-config/routers-typed.json";
    private static final String PROPERTIES = "X-DIAL-APPLICATION-PROPERTIES";

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
    void jwtCallerIdentityThroughTheKey() throws Exception {
        stubJwt("u1-token", "u1");
        List<RecordedRequest> received = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<String> userInfo = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848, askingUserInfo(received, userInfo))) {
            verify(send(HttpMethod.POST, "/openai/deployments/r1/chat/completions", null, CHAT_BODY,
                    "authorization", "u1-token", "content-type", "application/json"), 200);
        }
        JsonNode info = ProxyUtil.MAPPER.readTree(userInfo.get());
        assertEquals("u1", info.get("userId").asText());
        assertEquals(List.of("analysts", "b"), ProxyUtil.MAPPER.convertValue(info.get("roles"), List.class));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void projectKeyCallerIdentityThroughTheKey() throws Exception {
        List<RecordedRequest> received = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<String> userInfo = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848, askingUserInfo(received, userInfo))) {
            verify(send(HttpMethod.POST, "/openai/deployments/r1/chat/completions", null, CHAT_BODY,
                    "api-key", "proxyKey1", "content-type", "application/json"), 200);
        }
        JsonNode info = ProxyUtil.MAPPER.readTree(userInfo.get());
        assertEquals("p1", info.get("project").asText());
        assertEquals(List.of("analysts"), ProxyUtil.MAPPER.convertValue(info.get("roles"), List.class));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void responseItemCallForwardedToTheRouter() throws Exception {
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
        assertNotNull(get.getHeader("api-key"));
    }

    /**
     * A router that, like the Go router, asks Core who the caller is with the key it received.
     */
    private TestWebServer.Handler askingUserInfo(List<RecordedRequest> received, AtomicReference<String> userInfo) {
        return request -> {
            received.add(request);
            Response info = send(HttpMethod.GET, "/v1/user/info", null, null, "api-key", request.getHeader("api-key"));
            userInfo.set(info.body());
            return TestWebServer.createResponse(200, COMPLETION, "content-type", "application/json");
        };
    }

    @Test
    @DialConfigLocation(TYPED)
    void typedRouterLoads() throws Exception {
        Response response = send(HttpMethod.GET, "/v1/routers/r3", null, null, "api-key", "proxyKey1");

        verify(response, 200);
        JsonNode r3 = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals("r3", r3.get("id").asText());
        assertFalse(r3.has("application_properties"), response.body());
        assertFalse(r3.has("applicationProperties"), response.body());
    }

    @Test
    @DialConfigLocation(TYPED)
    void typedRouterReceivesItsDeclaredProperties() throws Exception {
        List<RecordedRequest> received = Collections.synchronizedList(new ArrayList<>());
        try (TestWebServer server = new TestWebServer(4848, recording(received, COMPLETION))) {
            verify(send(HttpMethod.POST, "/openai/deployments/r3/chat/completions", null, CHAT_BODY,
                    PROPERTIES, "{\"tier\": \"premium\"}", "content-type", "application/json"), 200);
        }
        RecordedRequest request = received.getFirst();
        // the client's own header is dropped: the router gets exactly the configured properties
        assertEquals(List.of(), request.getHeaders().values(PROPERTIES).stream().filter(value -> value.contains("premium")).toList());
        assertEquals(ProxyUtil.MAPPER.readTree("{\"tier\": \"medium\", \"preferredModels\": [\"gpt-4\"]}"),
                ProxyUtil.MAPPER.readTree(request.getHeader(PROPERTIES)));
        assertNull(request.getHeader("X-DIAL-APPLICATION-ID"));
    }

    @Test
    @DialConfigLocation(TYPED)
    void typedRouterFeatureEndpointReceivesTheProperties() throws Exception {
        List<RecordedRequest> received = Collections.synchronizedList(new ArrayList<>());
        try (TestWebServer server = new TestWebServer(4848, recording(received, "{}"))) {
            verify(send(HttpMethod.POST, "/v1/r3/rate", null, "{}", "content-type", "application/json"), 200);
        }
        assertEquals("/rate", received.getFirst().getPath());
        assertEquals("medium", ProxyUtil.MAPPER.readTree(received.getFirst().getHeader(PROPERTIES)).get("tier").asText());
    }

    @Test
    @DialConfigLocation(TYPED)
    void schemaThatTurnsTheHeaderOffAndUntypedRouterSendNoProperties() {
        List<RecordedRequest> received = Collections.synchronizedList(new ArrayList<>());
        try (TestWebServer server = new TestWebServer(4848, recording(received, COMPLETION))) {
            for (String router : List.of("r5", "r1")) {
                verify(send(HttpMethod.POST, "/openai/deployments/" + router + "/chat/completions", null, CHAT_BODY,
                        "content-type", "application/json"), 200);
            }
        }
        assertEquals(2, received.size());
        received.forEach(request -> assertNull(request.getHeader(PROPERTIES), request.getPath()));
    }

    @Test
    @DialConfigLocation(TYPED)
    void translatorInFrontOfTypedRouterGetsNoProperties() {
        List<RecordedRequest> received = Collections.synchronizedList(new ArrayList<>());
        try (TestWebServer server = new TestWebServer(4848, recording(received, MESSAGE))) {
            verify(send(HttpMethod.POST, "/anthropic/v1/messages", null,
                    "{\"model\":\"r6\",\"max_tokens\":16,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                    "content-type", "application/json"), 200);
        }
        assertEquals(1, received.size(), received.toString());
        assertNull(received.getFirst().getHeader(PROPERTIES));
        assertEquals("r6", received.getFirst().getHeader("X-DIAL-DEPLOYMENT-ID"));
    }

    @Test
    @DialConfigLocation(TYPED)
    void schemaChangedUnderStoredRouterAnswers502() {
        String schema = "{\"$schema\": \"https://dial.epam.com/application_type_schemas/schema#\", \"$id\": \"https://dial.test/changing\","
                + " \"dial:applicationTypeDisplayName\": \"Changing\", \"properties\": {%s}, \"required\": [%s]}";
        String tier = "\"tier\": {\"type\": \"string\", \"dial:meta\": {\"dial:propertyKind\": \"server\", \"dial:propertyOrder\": 1}}";
        String extra = "\"extra\": {\"type\": \"string\", \"dial:meta\": {\"dial:propertyKind\": \"server\", \"dial:propertyOrder\": 2}}";
        verify(send(HttpMethod.PUT, "/v1/schemas/platform/changing", null, schema.formatted(tier, "\"tier\""),
                "authorization", "admin"), 200);
        verify(send(HttpMethod.PUT, "/v1/routers/platform/r-changing", null, "{\"baseUrl\": \"http://localhost:4848\","
                + " \"applicationTypeSchemaId\": \"https://dial.test/changing\", \"applicationProperties\": {\"tier\": \"medium\"},"
                + " \"interfaces\": {\"openaiChatCompletions\": {}}}", "authorization", "admin"), 200);
        verify(send(HttpMethod.PUT, "/v1/schemas/platform/changing", null, schema.formatted(tier + ", " + extra, "\"tier\", \"extra\""),
                "authorization", "admin"), 200);

        List<RecordedRequest> received = Collections.synchronizedList(new ArrayList<>());
        try (TestWebServer server = new TestWebServer(4848, recording(received, COMPLETION))) {
            Response response = send(HttpMethod.POST, "/openai/deployments/r-changing/chat/completions", null, CHAT_BODY,
                    "content-type", "application/json");
            verify(response, 502);
            assertTrue(response.body().contains("Router r-changing properties do not match its schema"), response.body());
        }
        assertTrue(received.isEmpty(), received.toString());
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
        for (String listing : List.of("/openai/models", "/openai/deployments", "/openai/applications", "/anthropic/v1/models")) {
            Response response = send(HttpMethod.GET, listing, null, null);
            verify(response, 200);
            assertFalse(response.body().contains("\"r1\""), listing + ": " + response.body());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void routerInTheDeploymentListing() throws Exception {
        Response response = send(HttpMethod.GET, "/v1/deployments", null, null, "api-key", "proxyKey2");

        verify(response, 200);
        JsonNode r2 = entry(ProxyUtil.MAPPER.readTree(response.body()), "r2");
        assertNotNull(r2, response.body());
        assertEquals("router", r2.get("object").asText());
        assertTrue(ProxyUtil.MAPPER.convertValue(r2.get("interfaces"), List.class).contains("chat"), r2.toString());
        assertFalse(r2.has("base_url"), r2.toString());
        assertFalse(r2.has("endpoint"), r2.toString());
        assertNull(entry(ProxyUtil.MAPPER.readTree(response.body()), "r1"), "r1 is closed to the default role");
    }

    @Test
    @DialConfigLocation(CONFIG)
    void routerInterfaceFilter() throws Exception {
        for (String type : List.of("embedding", "mcp")) {
            Response response = send(HttpMethod.GET, "/v1/deployments", "interface_type=" + type, null, "api-key", "proxyKey2");
            verify(response, 200);
            assertNull(entry(ProxyUtil.MAPPER.readTree(response.body()), "r2"), type + ": " + response.body());
        }
        Response embedding = send(HttpMethod.GET, "/v1/deployments", "interface_type=embedding", null, "api-key", "proxyKey1");
        assertNotNull(entry(ProxyUtil.MAPPER.readTree(embedding.body()), "r1"), "r1 declares openaiEmbeddings: " + embedding.body());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void routerNames() throws Exception {
        Response routers = send(HttpMethod.GET, "/v1/deployment-names", "type=router", null, "api-key", "proxyKey2");
        verify(routers, 200);
        assertEquals(ProxyUtil.MAPPER.readTree("[{\"id\": \"r2\", \"object\": \"router\"}]"), ProxyUtil.MAPPER.readTree(routers.body()));

        Response all = send(HttpMethod.GET, "/v1/deployment-names", null, null, "api-key", "proxyKey2");
        verify(all, 200);
        assertTrue(all.body().contains("{\"id\":\"r2\",\"object\":\"router\"}"), all.body());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void singleRouterThroughDeploymentInfo() throws Exception {
        verify(send(HttpMethod.GET, "/v1/deployments/r1", null, null, "api-key", "proxyKey2"), 403);

        Response r2 = send(HttpMethod.GET, "/v1/deployments/r2", null, null, "api-key", "proxyKey2");
        verify(r2, 200);
        assertEquals("router", ProxyUtil.MAPPER.readTree(r2.body()).get("object").asText());
    }

    private static JsonNode entry(JsonNode list, String id) {
        JsonNode items = list.isArray() ? list : list.get("data");
        for (JsonNode item : items) {
            if (id.equals(item.path("id").asText())) {
                return item;
            }
        }
        return null;
    }

    private Response sendMessages() {
        return send(HttpMethod.POST, "/anthropic/v1/messages", null,
                "{\"model\":\"r1\",\"max_tokens\":16,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                "content-type", "application/json");
    }

    private void stubJwt(String token, String userId) {
        ExtractedClaims claims = new ExtractedClaims(userId, List.of("analysts", "b"), userId,
                ProxyUtil.MAPPER.createObjectNode(), null, null, null);
        Mockito.doReturn(Future.succeededFuture(claims)).when(validator).extractClaims(token);
    }

    private static TestWebServer.Handler recording(List<RecordedRequest> received, String body) {
        return request -> {
            received.add(request);
            return TestWebServer.createResponse(200, body, "content-type", "application/json");
        };
    }
}
