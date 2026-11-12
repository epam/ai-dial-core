package com.epam.aidial.core.server;

import com.epam.aidial.core.plugin.Request;
import com.epam.aidial.core.server.config.TestOriginPluginProvider;
import com.epam.aidial.core.server.config.TestOriginPluginProvider.Closed;
import com.epam.aidial.core.server.data.ResponseMapping;
import com.epam.aidial.core.server.log.AnalyticsLogContext;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Interfaces served by a plugin: the config rules on write, and the requests a plugin serves. Each test
 * creates its own {@code plugins} entry and deployments through the Configuration API, so the shared test config
 * and the listings other tests count stay as they are.
 */
public class EmbeddedOriginApiTest extends ResourceBaseTest {

    @Test
    void testEmbeddedModeWithoutPlugin() {
        Response put = adminPut("/v1/models/platform/m", """
                {"type": "chat", "interfaces": {"openaiChatCompletions": {"mode": "embedded"}}}
                """);

        assertEquals(400, put.status(), put.body());
        assertEquals(404, adminGet("/v1/models/platform/m").status());
    }

    // deployment-interfaces-config "Embedded is not a mode"
    @Test
    void testEmbeddedIsNotMode() {
        Response put = adminPut("/v1/applications/platform/a", """
                {"interfaces": {"openaiChatCompletions": {"mode": "embedded", "plugin": "p1"}}}
                """);

        assertEquals(400, put.status(), put.body());
        assertEquals(404, adminGet("/v1/applications/platform/a").status());
    }

    @Test
    void testBlankPluginName() {
        Response put = adminPut("/v1/models/platform/m", """
                {"type": "chat", "interfaces": {"openaiChatCompletions": {"plugin": " "}}}
                """);

        assertEquals(422, put.status(), put.body());
        assertTrue(put.body().contains("A plugin name cannot be blank"), put.body());
        assertEquals(404, adminGet("/v1/models/platform/m").status());
    }

    @Test
    void testExplicitPassthroughModeWithPlugin() {
        plugin("p1", "{}");
        verify(adminPut("/v1/applications/platform/app", """
                {"interfaces": {"openaiChatCompletions": {"mode": "passthrough", "plugin": "p1"}}}
                """), 200);

        verify(chat("/openai/deployments/app/chat/completions", CHAT_BODY), 200);

        assertEquals("/openai/deployments/app/chat/completions", onlyRequest().uri());
    }

    // an alias of a model served by a plugin is listed with the interface and served by the plugin
    @Test
    @SneakyThrows
    void testAliasOfModelServedByPlugin() {
        plugin("p1", "{}");
        verify(adminPut("/v1/models/platform/" + MODEL, """
                {"type": "chat", "interfaces": {"openaiChatCompletions": {"plugin": "p1"}}}
                """), 200);
        verify(adminPut("/v1/aliases/public/plugin-alias", "{\"target\": {\"path\": \"models/" + MODEL + "\"}}"), 200);

        Response list = adminGet("/v1/deployments");
        verify(list, 200);
        JsonNode listed = null;
        for (JsonNode item : ProxyUtil.MAPPER.readTree(list.body())) {
            if (item.path("id").asText().endsWith("plugin-alias")) {
                listed = item;
            }
        }
        assertNotNull(listed, list.body());
        assertTrue(listed.path("interface_configs").has("openaiChatCompletions"), listed.toString());

        verify(chat("/openai/deployments/aliases/public/plugin-alias/chat/completions", CHAT_BODY), 200);
        assertEquals(1, TestOriginPluginProvider.REQUESTS.size());
    }

    @Test
    void testPluginNotRegisteredYet() {
        verify(adminPut("/v1/applications/platform/a", embeddedChat("later")), 200);
        verify(adminGet("/v1/applications/platform/a"), 200);
    }

    @Test
    void testEmbeddedInterceptor() {
        Response put = adminPut("/v1/interceptors/platform/i", embeddedChat("p1"));

        assertEquals(422, put.status(), put.body());
        assertTrue(put.body().contains("A plugin is not supported for an interceptor"), put.body());
    }

    @Test
    void testOverridePathsUnderAnEmbeddedEntry() {
        Response put = adminPut("/v1/applications/platform/a", """
                {"interfaces": {"openaiChatCompletions": {"plugin": "p1",
                  "overridePaths": {"postAzureOpenaiChatCompletions": "/x"}}}}
                """);

        assertEquals(422, put.status(), put.body());
        assertTrue(put.body().contains("An interface with a plugin is served by its plugin: overridePaths has no effect"), put.body());
    }

    @Test
    void testApplicationWithPluginButNoEmbeddedMode() {
        Response put = adminPut("/v1/applications/platform/a", """
                {"interfaces": {"openaiChatCompletions": {"base_url": "http://app", "plugin": "p1"}}}
                """);

        assertEquals(422, put.status(), put.body());
        assertTrue(put.body().contains("An interface is served either by a plugin or by a base_url, not by both"), put.body());
        assertEquals(404, adminGet("/v1/applications/platform/a").status());
    }

    @Test
    @SneakyThrows
    void testEmbeddedInterfaceAdvertised() {
        verify(adminPut("/v1/applications/platform/listed-embedded", embeddedChat("p1")), 200);

        Response list = adminGet("/v1/deployments");
        verify(list, 200);
        JsonNode listed = null;
        for (JsonNode item : ProxyUtil.MAPPER.readTree(list.body())) {
            if (item.path("id").asText().endsWith("listed-embedded")) {
                listed = item;
            }
        }
        assertNotNull(listed, list.body());
        assertTrue(listed.path("interface_configs").has("openaiChatCompletions"), listed.toString());
    }

    // deployment-origin "Embedded origin request"

    @Test
    void testChatCompletionHandedToThePlugin() {
        servedApp("openaiChatCompletions");

        Response response = chat("/openai/deployments/app/chat/completions?api-version=1", "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");

        verify(response, 200);
        Request request = onlyRequest();
        assertEquals(HttpMethod.POST, request.method());
        assertEquals("/openai/deployments/app/chat/completions?api-version=1", request.uri());
        assertNotNull(request.headers().get("X-DIAL-DEPLOYMENT-FEATURES"));
        assertNotNull(request.headers().get("API-KEY"));
        assertNotEquals("proxyKey1", request.headers().get("API-KEY"));
        assertTrue(request.body().toString().contains("\"messages\""), request.body().toString());
    }

    @Test
    void testUnifiedChatEndpointHandedToThePlugin() {
        servedApp("openaiChatCompletions");

        Response response = chat("/openai/v1/chat/completions", "{\"model\":\"app\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");

        verify(response, 200);
        assertEquals("/openai/v1/chat/completions", onlyRequest().uri());
    }

    @Test
    void testUpstreamHeadersForModel() {
        plugin("p1", "{}");
        verify(adminPut("/v1/models/platform/" + MODEL, """
                {"type": "chat", "upstreams": [{"endpoint": "http://cloud/v1", "key": "k1"}],
                 "interfaces": {"openaiChatCompletions": {"plugin": "p1"}}}
                """), 200);

        verify(chat("/openai/deployments/" + MODEL + "/chat/completions", CHAT_BODY), 200);

        MultiMap headers = onlyRequest().headers();
        assertEquals("http://cloud/v1", headers.get("X-UPSTREAM-ENDPOINT"));
        assertEquals("k1", headers.get("X-UPSTREAM-KEY"));
    }

    @Test
    void testEmbeddingsHandedToThePlugin() {
        servedApp("openaiEmbeddings");

        verify(chat("/openai/deployments/app/embeddings", "{\"input\":\"hi\"}"), 200);

        assertEquals("/openai/deployments/app/embeddings", onlyRequest().uri());
    }

    @Test
    void testResponsesCreateHandedToThePlugin() {
        servedApp("openaiResponses");

        verify(chat("/openai/v1/responses", "{\"model\":\"app\",\"input\":\"hi\"}"), 200);

        assertEquals("/openai/v1/responses", onlyRequest().uri());
    }

    @Test
    void testMessagesHandedToThePlugin() {
        servedApp("anthropicMessages");
        String body = "{\"model\":\"app\",\"max_tokens\":16,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

        verify(chat("/anthropic/v1/messages", body), 200);
        verify(chat("/anthropic/v1/messages/count_tokens", body), 200);

        assertEquals(List.of("/anthropic/v1/messages", "/anthropic/v1/messages/count_tokens"),
                TestOriginPluginProvider.REQUESTS.stream().map(Request::uri).toList());
    }

    @Test
    void testBodyTreeIsTheBody() {
        plugin("p1", "{}");
        verify(adminPut("/v1/applications/platform/app", """
                {"interfaces": {"openaiChatCompletions": {"plugin": "p1"},
                  "openaiEmbeddings": {"plugin": "p1"},
                  "openaiResponses": {"plugin": "p1"},
                  "anthropicMessages": {"plugin": "p1"}}}
                """), 200);

        verify(chat("/openai/deployments/app/chat/completions", CHAT_BODY), 200);
        verify(chat("/openai/deployments/app/embeddings", "{\"input\":\"hi\"}"), 200);
        verify(chat("/openai/v1/responses", "{\"model\":\"app\",\"input\":\"hi\"}"), 200);
        verify(chat("/anthropic/v1/messages", "{\"model\":\"app\",\"max_tokens\":16,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"), 200);

        assertEquals(4, TestOriginPluginProvider.REQUESTS.size());
        TestOriginPluginProvider.REQUESTS.forEach(EmbeddedOriginApiTest::assertBodyTreeIsTheBody);
    }

    @Test
    void testBodyTreeOfRetriedAttempt() {
        verify(retriedChat(), 200);

        List<Request> requests = TestOriginPluginProvider.REQUESTS;
        assertEquals(2, requests.size());
        requests.forEach(EmbeddedOriginApiTest::assertBodyTreeIsTheBody);
        assertNotSame(requests.get(0).bodyTree(), requests.get(1).bodyTree());
    }

    // deployment-origin "Embedded origin answer"

    @Test
    void testStreamedAnswerFromPlugin() {
        servedApp("openaiChatCompletions");

        Response response = chat("/openai/deployments/app/chat/completions", STREAM_BODY);

        verify(response, 200);
        assertTrue(response.body().contains("\"content\":\"ok\""), response.body());
        assertTrue(response.body().contains("[DONE]"), response.body());
        assertEquals("1", response.headers().get("X-UPSTREAM-ATTEMPTS"));
        List<AnalyticsLogContext> rows = awaitAnalyticsRows(1);
        assertEquals(1, rows.size());
        AnalyticsLogContext row = rows.get(0);
        assertEquals("app", row.getDeploymentName());
        assertEquals("plugin://p1", row.getUpstreamEndpoint());
    }

    // also deployment-interfaces-config "Model served by a plugin needs no url"
    @Test
    @SneakyThrows
    void testModelUsageCharged() {
        plugin("p1", "{}");
        verify(adminPut("/v1/models/platform/" + MODEL, """
                {"type": "chat", "interfaces": {"openaiChatCompletions": {"plugin": "p1"}}}
                """), 200);

        verify(chat("/openai/deployments/" + MODEL + "/chat/completions", CHAT_BODY), 200);

        awaitAnalyticsRows(1);
        Response limits = send(HttpMethod.GET, "/v1/deployments/" + MODEL + "/limits", null, null);
        verify(limits, 200);
        assertEquals(15, ProxyUtil.MAPPER.readTree(limits.body()).path("minuteTokenStats").path("used").asInt(), limits.body());
    }

    @Test
    void testRetriableStatusFromPlugin() {
        Response response = retriedChat();

        verify(response, 200);
        assertEquals(List.of("http://cloud-a", "http://cloud-b"),
                TestOriginPluginProvider.REQUESTS.stream().map(request -> request.headers().get("X-UPSTREAM-ENDPOINT")).toList());
        assertEquals("2", response.headers().get("X-UPSTREAM-ATTEMPTS"));
        // the row of a model with upstreams names the upstream of the attempt that answered
        assertEquals("http://cloud-b", awaitAnalyticsRows(1).get(0).getUpstreamEndpoint());
    }

    // deployment-origin "Embedded origin failure"

    @Test
    void testMissingPluginEntry() {
        verify(adminPut("/v1/applications/platform/app", embeddedChat("missing")), 200);

        Response response = chat("/openai/deployments/app/chat/completions", CHAT_BODY);

        assertConnectFailure(response);
    }

    @Test
    void testPluginCallFails() {
        servedApp("openaiChatCompletions");

        Response response = send(HttpMethod.POST, "/openai/deployments/app/chat/completions", null, CHAT_BODY,
                "content-type", "application/json", "X-Test-Fail", "call");

        assertEquals(502, response.status(), response.body());
        assertTrue(response.body().contains("No route"), response.body());
        assertEquals(1, TestOriginPluginProvider.REQUESTS.size());
    }

    @Test
    void testPluginAnswersNull() {
        servedApp("openaiChatCompletions");

        Response response = send(HttpMethod.POST, "/openai/deployments/app/chat/completions", null, CHAT_BODY,
                "content-type", "application/json", "X-Test-Fail", "null");

        assertEquals(502, response.status(), response.body());
        assertTrue(response.body().contains("No route"), response.body());
    }

    // deployment-origin "Client gone from an embedded origin"

    @Test
    @SneakyThrows
    void testClientDisconnectsMidStreamFromAnEmbeddedApplication() {
        servedApp("openaiChatCompletions");

        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + serverPort + "/openai/deployments/app/chat/completions"))
                    .header("api-key", "proxyKey1")
                    .header("content-type", "application/json")
                    .header("X-Test-Hold", "true")
                    .POST(HttpRequest.BodyPublishers.ofString(STREAM_BODY))
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                assertEquals(200, response.statusCode());
                byte[] first = new byte[16];
                assertTrue(body.read(first) > 0);
            }
        }

        awaitCondition(() -> TestOriginPluginProvider.CANCELLED.get() == 1);
    }

    @Test
    void testRetriedAnswerCancelled() {
        verify(retriedChat(), 200);

        // the first answer was cancelled before the second call arrived
        assertEquals(List.of(0, 1), TestOriginPluginProvider.CANCELLED_BEFORE);
    }

    // deployment-interfaces-config, request side

    @Test
    void testEmbeddedInterfaceGoesToThePlugin() {
        plugin("p1", "{}");
        verify(adminPut("/v1/applications/platform/app", """
                {"baseUrl": "http://localhost:4848", "interfaces": {"openaiChatCompletions": {"plugin": "p1"}}}
                """), 200);

        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/openai/deployments/app/chat/completions",
                    request -> TestWebServer.createResponse(500, "the base url must not be called"));

            verify(chat("/openai/deployments/app/chat/completions", CHAT_BODY), 200);
        }
        assertEquals(1, TestOriginPluginProvider.REQUESTS.size());
    }

    @Test
    void testEmbeddedInterfaceNeverFallsBack() {
        verify(adminPut("/v1/models/platform/" + MODEL, """
                {"type": "chat", "endpoint": "http://localhost:4848/chat",
                 "interfaces": {"openaiChatCompletions": {"plugin": "missing"}}}
                """), 200);

        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/chat",
                    request -> TestWebServer.createResponse(200, "{\"error\":\"the legacy endpoint must not be called\"}"));

            assertConnectFailure(chat("/openai/deployments/" + MODEL + "/chat/completions", CHAT_BODY));
        }
    }

    @Test
    void testCustomApplicationRefusedOnWrite() {
        Response put = send(HttpMethod.PUT, "/v1/applications/" + bucket + "/my-app", null, embeddedChat("p1"));

        assertEquals(422, put.status(), put.body());
        assertTrue(put.body().contains("A plugin is reserved for platform deployments"), put.body());
        assertEquals(404, send(HttpMethod.GET, "/v1/applications/" + bucket + "/my-app", null, "").status());
    }

    @Test
    @SneakyThrows
    void testCustomApplicationRefusedByValidate() {
        plugin("p1", "{}");
        Response response = send(HttpMethod.POST, "/v1/admin/validate", null, """
                {"manifests": [{"kind": "Application", "name": "applications/public/my-app",
                  "spec": {"interfaces": {"openaiChatCompletions": {"plugin": "p1"}}}}]}
                """, "authorization", "admin");

        verify(response, 422);
        JsonNode result = ProxyUtil.MAPPER.readTree(response.body()).path("results").path(0);
        assertEquals("FAILED", result.path("status").asText(), response.body());
        assertEquals("A plugin is reserved for platform deployments", result.path("error").asText(), response.body());
    }

    @Test
    void testCustomApplicationWithInlineTranslatorPluginRefusedOnWrite() {
        Response put = send(HttpMethod.PUT, "/v1/applications/" + bucket + "/my-app", null, """
                {"interfaces": {"anthropicMessages": {"mode": "translator",
                  "translator": {"out": "openaiChatCompletions", "plugin": "p1"}}}}
                """);

        assertEquals(422, put.status(), put.body());
        assertTrue(put.body().contains("A plugin is reserved for platform deployments"), put.body());
        assertEquals(404, send(HttpMethod.GET, "/v1/applications/" + bucket + "/my-app", null, "").status());
    }

    @Test
    void testStoredCustomApplicationNamingTranslatorWithPlugin() {
        plugin("p1", "{}");
        verify(adminPut("/v1/translators/platform/c2r", """
                {"in": "openaiChatCompletions", "out": "openaiResponses", "plugin": "p1"}
                """), 200);
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromPrivateUrl("applications/" + bucket + "/my-app",
                dial.getProxy().getEncryptionService());
        dial.getProxy().getResourceService().putResource(descriptor, """
                {"interfaces": {"openaiChatCompletions": {"mode": "translator", "translator": "c2r"}}}
                """, EtagHeader.ANY, null, false);

        Response response = chat("/openai/deployments/applications/" + bucket + "/my-app/chat/completions", CHAT_BODY);

        assertEquals(502, response.status(), response.body());
        assertTrue(TestOriginPluginProvider.REQUESTS.isEmpty());
    }

    @Test
    void testStoredCustomApplicationNotServedByPlugin() {
        plugin("p1", "{}");
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromPrivateUrl("applications/" + bucket + "/my-app",
                dial.getProxy().getEncryptionService());
        dial.getProxy().getResourceService().putResource(descriptor, embeddedChat("p1"), EtagHeader.ANY, null, false);

        Response response = chat("/openai/deployments/applications/" + bucket + "/my-app/chat/completions", CHAT_BODY);

        assertEquals(502, response.status(), response.body());
        assertTrue(TestOriginPluginProvider.REQUESTS.isEmpty());
    }

    // responses-api "Item request to an embedded origin"

    @Test
    @SneakyThrows
    void testStoredResponseReadBackFromPlugin() {
        String dialId = createdResponse();

        ResponseMapping mapping = dial.getProxy().getResponseMappingService().getMapping(dialId);
        assertEquals("plugin-resp-1", mapping.getUpstreamResponseId());
        assertEquals("plugin://p1", mapping.getUpstreamKey());

        verify(send(HttpMethod.GET, "/openai/v1/responses/" + dialId, null, ""), 200);
        assertEquals(HttpMethod.GET, lastRequest().method());
        assertEquals("/openai/v1/responses/plugin-resp-1", lastRequest().uri());
    }

    @Test
    void testCancelReachesThePlugin() {
        String dialId = createdResponse();

        verify(send(HttpMethod.POST, "/openai/v1/responses/" + dialId + "/cancel", null, ""), 200);

        assertEquals(HttpMethod.POST, lastRequest().method());
        assertEquals("/openai/v1/responses/plugin-resp-1/cancel", lastRequest().uri());
    }

    @Test
    @SneakyThrows
    void testClientGoneFromStreamedItemCancelsThePluginsAnswer() {
        String dialId = createdResponse();

        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + serverPort + "/openai/v1/responses/" + dialId + "?stream=true"))
                    .header("api-key", "proxyKey1")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                assertEquals(200, response.statusCode());
                byte[] first = new byte[16];
                assertTrue(body.read(first) > 0);
            }
        }

        awaitCondition(() -> TestOriginPluginProvider.CANCELLED.get() == 1);
    }

    @Test
    void testPluginGoneBeforeTheItemOperation() {
        String dialId = createdResponse();
        verify(send(HttpMethod.DELETE, "/v1/plugins/platform/p1", null, "", "authorization", "admin"), 204);

        Response response = send(HttpMethod.GET, "/openai/v1/responses/" + dialId, null, "");

        assertEquals(500, response.status(), response.body());
        assertTrue(response.body().contains("Failed to process response operation"), response.body());
        assertEquals(1, TestOriginPluginProvider.REQUESTS.size());
    }

    // plugin-registry "Origin plugins"

    @Test
    void testSettingsChangeTakesEffect() {
        plugin("p1", "{\"greeting\": \"hello\"}");
        verify(adminPut("/v1/applications/platform/app", embeddedChat("p1")), 200);
        Response hello = chat("/openai/deployments/app/chat/completions", CHAT_BODY);
        assertTrue(hello.body().contains("\"content\":\"hello\""), hello.body());

        plugin("p1", "{\"greeting\": \"hi\"}");

        Response hi = chat("/openai/deployments/app/chat/completions", CHAT_BODY);
        assertTrue(hi.body().contains("\"content\":\"hi\""), hi.body());
    }

    @Test
    void testEntryRemoved() {
        servedApp("openaiChatCompletions");
        verify(chat("/openai/deployments/app/chat/completions", CHAT_BODY), 200);

        verify(send(HttpMethod.DELETE, "/v1/plugins/platform/p1", null, "", "authorization", "admin"), 204);

        assertConnectFailure(chat("/openai/deployments/app/chat/completions", CHAT_BODY));
    }

    // plugin-registry "Closing an origin plugin"

    @Test
    void testReplacedInstanceClosed() {
        plugin("p1", "{\"greeting\": \"hello\"}");
        verify(adminPut("/v1/applications/platform/app", embeddedChat("p1")), 200);
        verify(chat("/openai/deployments/app/chat/completions", CHAT_BODY), 200);

        plugin("p1", "{\"greeting\": \"hi\"}");
        Response hi = chat("/openai/deployments/app/chat/completions", CHAT_BODY);
        assertTrue(hi.body().contains("\"content\":\"hi\""), hi.body());

        awaitCondition(() -> !TestOriginPluginProvider.CLOSED.isEmpty());
        assertEquals(List.of(new Closed("hello", false)), TestOriginPluginProvider.CLOSED);
    }

    @Test
    @SneakyThrows
    void testBusyInstanceClosedAtOnce() {
        plugin("p1", "{\"greeting\": \"hello\"}");
        verify(adminPut("/v1/applications/platform/app", embeddedChat("p1")), 200);

        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            try (InputStream held = heldStream(client, "app")) {
                plugin("p1", "{\"greeting\": \"hi\"}");
                Response hi = chat("/openai/deployments/app/chat/completions", CHAT_BODY);
                assertTrue(hi.body().contains("\"content\":\"hi\""), hi.body());

                // the stream of the hello instance is still open
                awaitCondition(() -> !TestOriginPluginProvider.CLOSED.isEmpty());
                assertEquals(List.of(new Closed("hello", false)), TestOriginPluginProvider.CLOSED);
            }
        }
    }

    @Test
    void testRemovedEntryClosed() {
        servedApp("openaiChatCompletions");
        verify(chat("/openai/deployments/app/chat/completions", CHAT_BODY), 200);

        verify(send(HttpMethod.DELETE, "/v1/plugins/platform/p1", null, "", "authorization", "admin"), 204);
        assertConnectFailure(chat("/openai/deployments/app/chat/completions", CHAT_BODY));

        awaitCondition(() -> !TestOriginPluginProvider.CLOSED.isEmpty());
        assertEquals(List.of(new Closed("ok", false)), TestOriginPluginProvider.CLOSED);
    }

    @Test
    @SneakyThrows
    void testClosedAtShutdown() {
        plugin("p1", "{\"greeting\": \"one\"}");
        plugin("p2", "{\"greeting\": \"two\"}");
        verify(adminPut("/v1/applications/platform/app", embeddedChat("p1")), 200);
        verify(adminPut("/v1/applications/platform/app2", embeddedChat("p2")), 200);
        verify(chat("/openai/deployments/app2/chat/completions", CHAT_BODY), 200);

        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            try (InputStream held = heldStream(client, "app")) {
                dial.stop();
                // stopped here; the teardown must not stop it again, a closed Vert.x rejects a second close
                dial = null;
            }
        }

        assertEquals(List.of("one", "two"), TestOriginPluginProvider.CLOSED.stream().map(Closed::greeting).sorted().toList());
    }

    @Test
    void testCloseFails() {
        plugin("p1", "{\"greeting\": \"hello\", \"closeFails\": true}");
        verify(adminPut("/v1/applications/platform/app", embeddedChat("p1")), 200);
        verify(chat("/openai/deployments/app/chat/completions", CHAT_BODY), 200);
        plugin("p1", "{\"greeting\": \"hi\"}");
        verify(chat("/openai/deployments/app/chat/completions", CHAT_BODY), 200);
        awaitCondition(() -> !TestOriginPluginProvider.CLOSED.isEmpty());

        Response hi = chat("/openai/deployments/app/chat/completions", CHAT_BODY);

        assertTrue(hi.body().contains("\"content\":\"hi\""), hi.body());
        assertEquals(List.of(new Closed("hello", false)), TestOriginPluginProvider.CLOSED);
    }

    @BeforeEach
    void resetTestOrigin() {
        TestOriginPluginProvider.reset();
    }

    private static final String MODEL = "chat-gpt-35-turbo";
    private static final String CHAT_BODY = "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    private static final String STREAM_BODY = "{\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

    /**
     * Plugin {@code p1} of type {@code test-origin}, and application {@code app} served by it on {@code type}.
     */
    private void servedApp(String type) {
        plugin("p1", "{}");
        verify(adminPut("/v1/applications/platform/app", """
                {"interfaces": {"%s": {"plugin": "p1"}}}
                """.formatted(type)), 200);
    }

    /**
     * Plugin entry {@code name} of type {@code test-origin} with {@code settings}.
     */
    private void plugin(String name, String settings) {
        putPlugin(name, "{\"type\": \"" + TestOriginPluginProvider.TYPE + "\", \"settings\": " + settings + "}");
    }

    private void putPlugin(String name, String body) {
        verify(adminPut("/v1/plugins/platform/" + name, body), 200);
    }

    /**
     * The body of a chat completion streamed by {@code deployment} until the client closes it, its first bytes read.
     */
    @SneakyThrows
    private InputStream heldStream(HttpClient client, String deployment) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + serverPort + "/openai/deployments/" + deployment + "/chat/completions"))
                .header("api-key", "proxyKey1")
                .header("content-type", "application/json")
                .header("X-Test-Hold", "true")
                .POST(HttpRequest.BodyPublishers.ofString(STREAM_BODY))
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        InputStream body = response.body();
        assertTrue(body.read(new byte[16]) > 0);
        return body;
    }

    private Response chat(String path, String body) {
        return send(HttpMethod.POST, path, null, body, "content-type", "application/json");
    }

    /**
     * A model with two upstreams in two tiers and two attempts, whose plugin answers {@code 429} to the first tier.
     */
    private Response retriedChat() {
        plugin("p1", "{}");
        verify(adminPut("/v1/models/platform/" + MODEL, """
                {"type": "chat", "maxRetryAttempts": 2,
                 "upstreams": [{"endpoint": "http://cloud-a", "key": "a", "tier": 0}, {"endpoint": "http://cloud-b", "key": "b", "tier": 1}],
                 "interfaces": {"openaiChatCompletions": {"plugin": "p1"}}}
                """), 200);
        return send(HttpMethod.POST, "/openai/deployments/" + MODEL + "/chat/completions", null, CHAT_BODY,
                "content-type", "application/json", "X-Test-Status-For-Endpoint", "http://cloud-a=429");
    }

    @SneakyThrows
    private String createdResponse() {
        servedApp("openaiResponses");
        Response created = chat("/openai/v1/responses", "{\"model\":\"app\",\"input\":\"hi\"}");
        verify(created, 200);
        return ProxyUtil.MAPPER.readTree(created.body()).path("id").asText();
    }

    private static Request onlyRequest() {
        assertEquals(1, TestOriginPluginProvider.REQUESTS.size(), () -> TestOriginPluginProvider.REQUESTS.toString());
        return TestOriginPluginProvider.REQUESTS.get(0);
    }

    private static Request lastRequest() {
        List<Request> requests = TestOriginPluginProvider.REQUESTS;
        return requests.get(requests.size() - 1);
    }

    @SneakyThrows
    private static void assertBodyTreeIsTheBody(Request request) {
        assertNotNull(request.bodyTree(), request.uri());
        assertEquals(ProxyUtil.MAPPER.readTree(request.body().getBytes()), request.bodyTree(), request.uri());
    }

    @SneakyThrows
    private static void assertConnectFailure(Response response) {
        assertEquals(502, response.status(), response.body());
        JsonNode error = ProxyUtil.MAPPER.readTree(response.body()).path("error");
        assertEquals("Failed to connect to upstream server", error.path("message").asText(), response.body());
        assertEquals("Failed to connect to upstream server", error.path("display_message").asText(), response.body());
    }

    static String embeddedChat(String plugin) {
        return """
                {"interfaces": {"openaiChatCompletions": {"plugin": "%s"}}}
                """.formatted(plugin);
    }

    Response adminPut(String path, String body) {
        return send(HttpMethod.PUT, path, null, body, "authorization", "admin");
    }

    Response adminGet(String path) {
        return send(HttpMethod.GET, path, null, "", "authorization", "admin");
    }
}
