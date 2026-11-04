package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.DeploymentInterface;
import com.epam.aidial.core.config.InterfaceMode;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Router;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.storage.http.HttpException;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServerRequest;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RouterDecisionClientTest {

    private Vertx vertx;
    private HttpClient client;
    private MockWebServer server;
    private ProxyContext context;
    private final MultiMap clientHeaders = MultiMap.caseInsensitiveMultiMap();

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        client = vertx.createHttpClient();
        server = new MockWebServer();
        server.start();

        Proxy proxy = mock(Proxy.class);
        when(proxy.getVertx()).thenReturn(vertx);
        when(proxy.getClient()).thenReturn(client);
        when(proxy.getClientOptions()).thenReturn(new HttpClientOptions().setConnectTimeout(5000));
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.headers()).thenReturn(clientHeaders);
        Key key = new Key();
        key.setProject("p1");
        context = mock(ProxyContext.class);
        when(context.getProxy()).thenReturn(proxy);
        when(context.getRequest()).thenReturn(request);
        when(context.getKey()).thenReturn(key);
        when(context.getUserRoles()).thenReturn(List.of("default"));
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void candidatesInOrder() throws Exception {
        server.enqueue(json(200, "{\"candidates\": [\"a\", \"b\"], \"reason\": \"cheap\"}"));

        assertEquals(List.of("a", "b"), decide(entry(null), null));
    }

    @Test
    void shorthandReadAsOneCandidate() throws Exception {
        server.enqueue(json(200, "{\"deployment\": \"a\", \"ttl\": 60}"));

        assertEquals(List.of("a"), decide(entry(null), null));
    }

    @Test
    void invalidVerdictsAnswered502() {
        for (String body : List.of("{\"candidates\": []}", "{\"model\": \"a\"}", "not json", "{\"candidates\": [\"a\", 1]}",
                "{\"candidates\": [\" \"]}")) {
            server.enqueue(json(200, body));

            assertEquals(502, failure(entry(null)).getStatus().getCode(), body);
        }
    }

    @Test
    void routerClientErrorRelayed() {
        String body = "{\"error\": {\"message\": \"state either tier or target\"}}";
        server.enqueue(json(400, body));

        HttpException error = failure(entry(null));

        assertEquals(400, error.getStatus().getCode());
        assertEquals(body, error.getMessage());
        assertEquals("application/json", error.getHeaders().get("content-type"));
    }

    @Test
    void unlistedRouterClientErrorStaysClientError() {
        server.enqueue(json(418, "{}"));

        assertEquals(400, failure(entry(null)).getStatus().getCode());
    }

    @Test
    void routerServerErrorAnswered502() {
        server.enqueue(json(500, "{}"));

        assertEquals(502, failure(entry(null)).getStatus().getCode());
    }

    @Test
    void slowRouterAnswered504() {
        server.enqueue(json(200, "{\"deployment\": \"a\"}").setHeadersDelay(2, TimeUnit.SECONDS));
        DeploymentInterface entry = entry(null);
        entry.setDecisionTimeoutMs(100L);

        long start = System.currentTimeMillis();
        assertEquals(504, failure(entry).getStatus().getCode());
        assertTrue(System.currentTimeMillis() - start < 1000);
    }

    @Test
    void unreachableRouterAnswered502() throws Exception {
        String url = server.url("/decide").toString();
        server.shutdown();
        DeploymentInterface entry = new DeploymentInterface(url);
        entry.setMode(InterfaceMode.DECISION);

        assertEquals(502, failure(entry).getStatus().getCode());
    }

    @Test
    void decideRequestHeaders() throws Exception {
        server.enqueue(json(200, "{\"deployment\": \"a\"}"));
        clientHeaders.add("Authorization", "Bearer secret").add("X-DIAL-USER-INFO", "forged").add("api-key", "p1-key")
                .add("X-CONVERSATION-ID", "c1").add("X-DIAL-ROUTER-TIER", "high");
        Router router = router(Map.of("X-DIAL-ROUTER-TIER", "medium", "X-DEFAULT", "d"));

        RouterDecisionClient.decide(context, router, entry(null), InterfaceType.ANTHROPIC_MESSAGES, null)
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("POST", request.getMethod());
        assertEquals("/decide", request.getPath());
        assertNull(request.getHeader("Authorization"));
        assertNull(request.getHeader("api-key"));
        assertEquals("{\"roles\":[\"default\"],\"project\":\"p1\"}", request.getHeader("X-DIAL-USER-INFO"));
        assertEquals("c1", request.getHeader("X-CONVERSATION-ID"));
        assertEquals("high", request.getHeader("X-DIAL-ROUTER-TIER"));
        assertEquals("d", request.getHeader("X-DEFAULT"));
        assertEquals("r1", request.getHeader("X-DIAL-ROUTER"));
        assertEquals("anthropicMessages", request.getHeader("X-DIAL-API"));
        assertEquals(0, request.getBodySize());
    }

    private List<String> decide(DeploymentInterface entry, Buffer body) throws Exception {
        return RouterDecisionClient.decide(context, router(Map.of()), entry, InterfaceType.OPENAI_CHAT_COMPLETIONS, body)
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private HttpException failure(DeploymentInterface entry) {
        ExecutionException error = assertThrows(ExecutionException.class, () -> decide(entry, null));
        return assertInstanceOf(HttpException.class, error.getCause());
    }

    private DeploymentInterface entry(Long timeoutMs) {
        DeploymentInterface entry = new DeploymentInterface(server.url("/decide").toString());
        entry.setMode(InterfaceMode.DECISION);
        entry.setDecisionTimeoutMs(timeoutMs);
        return entry;
    }

    private static Router router(Map<String, String> defaultHeaders) {
        Router router = new Router();
        router.setName("r1");
        router.setDefaultHeaders(defaultHeaders);
        return router;
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status).setBody(body).setHeader("content-type", "application/json");
    }
}
