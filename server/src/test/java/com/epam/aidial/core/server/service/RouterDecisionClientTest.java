package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.DeploymentInterface;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Router;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.RouterDecision;
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
        context = mock(ProxyContext.class);
        when(context.getProxy()).thenReturn(proxy);
        when(context.getRequest()).thenReturn(request);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void candidatesInOrder() throws Exception {
        server.enqueue(json(200, "{\"candidates\": [\"a\", \"b\"], \"reason\": \"cheap\"}"));

        assertEquals(List.of("a", "b"), decide(router(null), null));
    }

    @Test
    void shorthandNoLongerRead() {
        server.enqueue(json(200, "{\"deployment\": \"a\", \"ttl\": 60}"));

        assertEquals(502, failure(router(null)).getStatus().getCode());
    }

    @Test
    void invalidVerdictsAnswered502() {
        for (String body : List.of("{\"candidates\": []}", "{\"model\": \"a\"}", "not json", "{\"candidates\": [\"a\", 1]}",
                "{\"candidates\": [\" \"]}")) {
            server.enqueue(json(200, body));

            assertEquals(502, failure(router(null)).getStatus().getCode(), body);
        }
    }

    @Test
    void routerClientErrorRelayed() {
        String body = "{\"error\": {\"message\": \"state either tier or target\"}}";
        server.enqueue(json(400, body));

        HttpException error = failure(router(null));

        assertEquals(400, error.getStatus().getCode());
        assertEquals(body, error.getMessage());
        assertEquals("application/json", error.getHeaders().get("content-type"));
    }

    @Test
    void unlistedRouterClientErrorStaysClientError() {
        server.enqueue(json(418, "{}"));

        assertEquals(400, failure(router(null)).getStatus().getCode());
    }

    @Test
    void routerServerErrorAnswered502() {
        server.enqueue(json(500, "{}"));

        assertEquals(502, failure(router(null)).getStatus().getCode());
    }

    @Test
    void slowRouterAnswered504() {
        server.enqueue(json(200, "{\"candidates\": [\"a\"]}").setHeadersDelay(2, TimeUnit.SECONDS));
        long start = System.currentTimeMillis();
        assertEquals(504, failure(router(100L)).getStatus().getCode());
        assertTrue(System.currentTimeMillis() - start < 1000);
    }

    @Test
    void unreachableRouterAnswered502() throws Exception {
        Router router = router(null);
        server.shutdown();

        assertEquals(502, failure(router).getStatus().getCode());
    }

    @Test
    void decideRequestHeaders() throws Exception {
        server.enqueue(json(200, "{\"candidates\": [\"a\"]}"));
        clientHeaders.add("Authorization", "Bearer secret").add("api-key", "p1-key")
                .add("X-CONVERSATION-ID", "c1").add("X-DIAL-ROUTER-TIER", "high");
        Router router = router(null);
        router.setDefaultHeaders(Map.of("X-DIAL-ROUTER-TIER", "medium", "X-DEFAULT", "d"));

        RouterDecisionClient.decide(context, router, InterfaceType.ANTHROPIC_MESSAGES, null, "decide-key", List.of())
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("POST", request.getMethod());
        assertEquals("/decide", request.getPath());
        assertNull(request.getHeader("Authorization"));
        assertEquals("decide-key", request.getHeader("api-key"));
        assertEquals("c1", request.getHeader("X-CONVERSATION-ID"));
        assertEquals("high", request.getHeader("X-DIAL-ROUTER-TIER"));
        assertEquals("d", request.getHeader("X-DEFAULT"));
        assertEquals("r1", request.getHeader("X-DIAL-ROUTER"));
        assertEquals("anthropicMessages", request.getHeader("X-DIAL-API"));
        assertEquals(0, request.getBodySize());
    }

    @Test
    void attemptsSentAndClientRouterHeadersDropped() throws Exception {
        server.enqueue(json(200, "{\"candidates\": [\"b\"]}"));
        clientHeaders.add("X-DIAL-ROUTER-ATTEMPTS", "forged").add("X-DIAL-ROUTER-NOTICE", "true");

        RouterDecisionClient.decide(context, router(null), InterfaceType.OPENAI_CHAT_COMPLETIONS, null, "decide-key",
                        List.of(new RouterDecision.Attempt("a", 502, "body", true, false)))
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("[{\"deployment\":\"a\",\"status\":502,\"reason\":\"body\",\"received\":true}]",
                request.getHeader("X-DIAL-ROUTER-ATTEMPTS"));
        assertNull(request.getHeader("X-DIAL-ROUTER-NOTICE"));
    }

    @Test
    void noticeCarriesTheAttemptsAndNoKey() throws Exception {
        server.enqueue(json(200, "{}"));
        clientHeaders.add("X-CONVERSATION-ID", "c1").add("X-DIAL-ROUTER-NOTICE", "false");

        Router router = router(null);
        router.setDefaultHeaders(Map.of("X-DEFAULT", "d"));
        RouterDecisionClient.notice(context, router, InterfaceType.OPENAI_CHAT_COMPLETIONS, List.of(new RouterDecision.Attempt("a", 200, "stream", true, true)));

        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("POST", request.getMethod());
        assertEquals("/decide", request.getPath());
        assertEquals("true", request.getHeader("X-DIAL-ROUTER-NOTICE"));
        assertEquals("[{\"deployment\":\"a\",\"status\":200,\"reason\":\"stream\",\"received\":true,\"partial\":true}]",
                request.getHeader("X-DIAL-ROUTER-ATTEMPTS"));
        assertEquals("c1", request.getHeader("X-CONVERSATION-ID"));
        assertEquals("d", request.getHeader("X-DEFAULT"));
        assertEquals("r1", request.getHeader("X-DIAL-ROUTER"));
        assertNull(request.getHeader("api-key"));
        assertEquals(0, request.getBodySize());
    }

    @Test
    void nonAsciiDeploymentNamesEscaped() throws Exception {
        server.enqueue(json(200, "{\"candidates\": [\"b\"]}"));

        RouterDecisionClient.decide(context, router(null), InterfaceType.OPENAI_CHAT_COMPLETIONS, null, "decide-key",
                        List.of(new RouterDecision.Attempt("modèle", 502, "upstream")))
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertTrue(server.takeRequest(5, TimeUnit.SECONDS).getHeader("X-DIAL-ROUTER-ATTEMPTS").contains("mod\\u00E8le"));
    }

    private List<String> decide(Router router, Buffer body) throws Exception {
        return RouterDecisionClient.decide(context, router, InterfaceType.OPENAI_CHAT_COMPLETIONS, body, "decide-key", List.of())
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private HttpException failure(Router router) {
        ExecutionException error = assertThrows(ExecutionException.class, () -> decide(router, null));
        return assertInstanceOf(HttpException.class, error.getCause());
    }

    /**
     * A router in decision mode at the mock server's root, serving chat completions and Messages.
     */
    private Router router(Long timeoutMs) {
        Router router = new Router();
        router.setName("r1");
        router.setBaseUrl(server.url("/").toString());
        router.setMode(Router.Mode.DECISION);
        Router.Decision decision = new Router.Decision();
        decision.setTimeoutMs(timeoutMs);
        router.setDecision(decision);
        router.setInterfaces(Map.of("openaiChatCompletions", new DeploymentInterface(), "anthropicMessages", new DeploymentInterface()));
        return router;
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status).setBody(body).setHeader("content-type", "application/json");
    }
}
