package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage of the {@code openaiEmbeddings} interface: the typed {@code interfaces} map is
 * strict, while deployments configured before the split keep serving embeddings through the untyped
 * legacy {@code endpoint}.
 */
public class EmbeddingsApiTest extends ResourceBaseTest {

    private static final String RESPONSE = "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0,"
            + "\"embedding\":[0.1,0.2]}],\"usage\":{\"prompt_tokens\":4,\"total_tokens\":4}}";

    private static final String REQUEST = "{\"model\":\"%s\",\"input\":\"hello\"}";

    @DialConfigLocation("dial-config/openai-embeddings.json")
    @Test
    public void testDeclaredInterfaceRoutesToItsBaseUrl() throws IOException {
        AtomicReference<RecordedRequest> captured = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848)) {
            String path = "/openai/deployments/embeddings-iface/embeddings";
            server.map(HttpMethod.POST, path, request -> {
                captured.set(request);
                return TestWebServer.createResponse(200, RESPONSE, "Content-Type", "application/json");
            });

            Response response = post(path, REQUEST.formatted("embeddings-iface"));

            verify(response, 200, RESPONSE);
            // base_url + the exact ingress path
            assertEquals(path, captured.get().getPath());
        }
    }

    @DialConfigLocation("dial-config/openai-embeddings.json")
    @Test
    public void testQueryParamsReachTheUpstreamExactlyOnce() throws IOException {
        AtomicReference<RecordedRequest> captured = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848)) {
            String path = "/openai/deployments/embeddings-iface/embeddings";
            String upstream = path + "?api-version=2025-01-01-preview";
            // the upstream is mapped by path *and* query, so a dropped or duplicated query does not match
            server.map(HttpMethod.POST, upstream, request -> {
                captured.set(request);
                return TestWebServer.createResponse(200, RESPONSE, "Content-Type", "application/json");
            });

            Response response = send(HttpMethod.POST, path, "api-version=2025-01-01-preview",
                    REQUEST.formatted("embeddings-iface"), "content-type", "application/json");

            verify(response, 200, RESPONSE);
            assertEquals(upstream, captured.get().getPath());
        }
    }

    @DialConfigLocation("dial-config/openai-embeddings.json")
    @Test
    public void testChatOnlyInterfaceRefusesEmbeddings() throws IOException {
        try (TestWebServer server = new TestWebServer(4848)) {
            // the typed map is strict: a chat-only declaration does not serve /embeddings
            Response response = post("/openai/deployments/embeddings-chat-only/embeddings",
                    REQUEST.formatted("embeddings-chat-only"));

            assertEquals(503, response.status());
        }
    }

    @DialConfigLocation("dial-config/openai-embeddings.json")
    @Test
    public void testLegacyEndpointStillServesEmbeddings() throws IOException {
        AtomicReference<RecordedRequest> captured = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/openai/deployments/ada/embeddings", request -> {
                captured.set(request);
                return TestWebServer.createResponse(200, RESPONSE, "Content-Type", "application/json");
            });

            Response response = post("/openai/deployments/embeddings-legacy/embeddings",
                    REQUEST.formatted("embeddings-legacy"));

            verify(response, 200, RESPONSE);
            // the legacy endpoint is forwarded verbatim, ingress path and all
            assertEquals("/openai/deployments/ada/embeddings", captured.get().getPath());
        }
    }

    @DialConfigLocation("dial-config/openai-embeddings.json")
    @Test
    public void testOverrideNameRewritesPathSegment() throws IOException {
        AtomicReference<RecordedRequest> captured = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/openai/deployments/text-embedding-ada-002/embeddings", request -> {
                captured.set(request);
                return TestWebServer.createResponse(200, RESPONSE, "Content-Type", "application/json");
            });

            Response response = post("/openai/deployments/embeddings-override/embeddings",
                    REQUEST.formatted("embeddings-override"));

            verify(response, 200, RESPONSE);
            assertEquals("/openai/deployments/text-embedding-ada-002/embeddings", captured.get().getPath());
        }
    }

    @DialConfigLocation("dial-config/openai-embeddings.json")
    @Test
    public void testEmbeddingsOnlyDeploymentRefusesChatCompletions() throws IOException {
        try (TestWebServer server = new TestWebServer(4848)) {
            Response response = post("/openai/deployments/embeddings-iface/chat/completions",
                    "{\"model\":\"embeddings-iface\",\"messages\":[]}");

            assertEquals(503, response.status());
        }
    }

    @DialConfigLocation("dial-config/openai-embeddings.json")
    @Test
    public void testChatOnlyApplicationRefusesEmbeddings() throws IOException {
        try (TestWebServer server = new TestWebServer(4848)) {
            // the strict typed map applies to applications too (a legacy `endpoint` would still serve it)
            Response response = post("/openai/deployments/app-chat/embeddings", REQUEST.formatted("app-chat"));

            assertEquals(503, response.status());
        }
    }

    @DialConfigLocation("dial-config/openai-embeddings.json")
    @Test
    public void testListingSurfacesTheTypedInterface() throws JsonProcessingException {
        Response response = send(HttpMethod.GET, "/v1/deployments", null, null);
        verify(response, 200);
        Map<String, Set<String>> interfaces = collectInterfaces(ProxyUtil.MAPPER.readTree(response.body()));

        assertEquals(Set.of("embedding", "openaiEmbeddings"), interfaces.get("embeddings-iface"));
        // every deployment advertises exactly what it declares
        assertEquals(Set.of("embedding", "openaiChatCompletions"), interfaces.get("embeddings-chat-only"));
        assertEquals(Set.of("chat", "openaiChatCompletions"), interfaces.get("app-chat"));
    }

    @DialConfigLocation("dial-config/openai-embeddings.json")
    @Test
    public void testEmbeddingInterfaceFilterListsModelsOnly() throws JsonProcessingException {
        Response response = send(HttpMethod.GET, "/v1/deployments", "interface_type=embedding", null);
        verify(response, 200);
        Set<String> ids = collectInterfaces(ProxyUtil.MAPPER.readTree(response.body())).keySet();

        assertTrue(ids.contains("embeddings-iface"), ids.toString());
        assertTrue(ids.contains("embeddings-chat-only"), ids.toString());
        assertFalse(ids.contains("app-chat"), ids.toString());
    }

    private Response post(String path, String body) {
        return send(HttpMethod.POST, path, null, body, "content-type", "application/json");
    }

    private static Map<String, Set<String>> collectInterfaces(JsonNode body) {
        Map<String, Set<String>> result = new HashMap<>();
        for (JsonNode deployment : body) {
            Set<String> interfaces = new HashSet<>();
            for (JsonNode iface : deployment.get("interfaces")) {
                interfaces.add(iface.asText());
            }
            result.put(deployment.get("id").asText(), interfaces);
        }
        return result;
    }
}
