package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.DeploymentInterface;
import com.epam.aidial.core.config.InterfaceMode;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Router;
import com.epam.aidial.core.plugin.OriginHost;
import com.epam.aidial.core.plugin.OriginPlugin;
import com.epam.aidial.core.plugin.OriginRequest;
import com.epam.aidial.core.plugin.OriginResponse;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.config.EmbeddedOrigins;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.RouterDecision;
import com.epam.aidial.core.server.upstream.OriginClient;
import com.epam.aidial.core.server.upstream.ProxyRequest;
import com.epam.aidial.core.server.upstream.ProxyResponse;
import com.epam.aidial.core.storage.http.HttpException;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.streams.ReadStream;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RouterDecisionClientTest {

    private Vertx vertx;
    private HttpClient client;
    private Proxy proxy;
    private EmbeddedOrigins embeddedOrigins;
    private MockWebServer server;
    private ProxyContext context;
    private final MultiMap clientHeaders = MultiMap.caseInsensitiveMultiMap();

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        client = vertx.createHttpClient();
        server = new MockWebServer();
        server.start();

        proxy = mock(Proxy.class);
        when(proxy.getVertx()).thenReturn(vertx);
        when(proxy.getClient()).thenReturn(client);
        embeddedOrigins = mock(EmbeddedOrigins.class);
        when(proxy.getOriginClient()).thenReturn(new OriginClient(client, vertx, embeddedOrigins));
        when(proxy.getClientOptions()).thenReturn(new HttpClientOptions().setConnectTimeout(5000));
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.headers()).thenReturn(clientHeaders);
        context = mock(ProxyContext.class);
        when(context.getProxy()).thenReturn(proxy);
        when(context.getRequest()).thenReturn(request);
        when(context.getConfig()).thenReturn(new Config());
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

        RouterDecisionClient.decide(context, router, InterfaceType.ANTHROPIC_MESSAGES, null, null, key(), List.of())
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

        RouterDecisionClient.decide(context, router(null), InterfaceType.OPENAI_CHAT_COMPLETIONS, null, null, key(),
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

        RouterDecisionClient.decide(context, router(null), InterfaceType.OPENAI_CHAT_COMPLETIONS, null, null, key(),
                        List.of(new RouterDecision.Attempt("modèle", 502, "upstream")))
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertTrue(server.takeRequest(5, TimeUnit.SECONDS).getHeader("X-DIAL-ROUTER-ATTEMPTS").contains("mod\\u00E8le"));
    }

    // an interface served in embedded mode: the plugin answers the decide request in process

    @Test
    void embeddedDecideReadFromThePlugin() throws Exception {
        AtomicReference<OriginRequest> seen = new AtomicReference<>();
        plugin((request, host) -> {
            seen.set(request);
            return Future.succeededFuture(answer(200, "{\"candidates\": [\"x\"]}", true));
        });

        assertEquals(List.of("x"), decide(embeddedRouter(null), null));
        assertEquals("/decide", seen.get().uri());
        assertEquals("decide-key", seen.get().headers().get("api-key"));
        assertEquals("r1", seen.get().headers().get("X-DIAL-ROUTER"));
    }

    @Test
    void noCancelAfterTheRouterAnswered() throws Exception {
        AtomicReference<OriginHost> refused = new AtomicReference<>();
        plugin((request, host) -> {
            refused.set(host);
            return Future.succeededFuture(answer(400, "{\"error\": {}}", true));
        });
        assertEquals(400, failure(embeddedRouter(null)).getStatus().getCode());

        AtomicReference<OriginHost> failed = new AtomicReference<>();
        plugin((request, host) -> {
            failed.set(host);
            return Future.failedFuture(new IllegalStateException("router failed"));
        });
        assertEquals(502, failure(embeddedRouter(null)).getStatus().getCode());

        Thread.sleep(200);
        assertFalse(refused.get().cancelled().isComplete(), "a plugin that answered is not told it was cancelled");
        assertFalse(failed.get().cancelled().isComplete(), "nor one whose answer failed");
    }

    @Test
    void cancelAtTheDeadline() throws Exception {
        AtomicReference<OriginHost> kept = new AtomicReference<>();
        plugin((request, host) -> {
            kept.set(host);
            return Promise.<OriginResponse>promise().future();
        });

        assertEquals(504, failure(embeddedRouter(100L)).getStatus().getCode());
        kept.get().cancelled().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void cancelWhenTheBodyStallsPastTheDeadline() throws Exception {
        AtomicReference<OriginHost> kept = new AtomicReference<>();
        plugin((request, host) -> {
            kept.set(host);
            return Future.succeededFuture(answer(200, "{\"candidates\": ", false));
        });

        assertEquals(504, failure(embeddedRouter(100L)).getStatus().getCode());
        kept.get().cancelled().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void requestCreatedAfterTheDeadlineIsReset() throws Exception {
        ProxyRequest late = mock(ProxyRequest.class);
        when(late.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        when(late.send()).thenReturn(Promise.<ProxyResponse>promise().future());
        Promise<ProxyRequest> connecting = Promise.promise();
        OriginClient origins = mock(OriginClient.class);
        when(origins.http(any())).thenReturn(connecting.future());
        when(proxy.getOriginClient()).thenReturn(origins);

        assertEquals(504, failure(router(100L)).getStatus().getCode());
        connecting.complete(late);

        verify(late, timeout(5000)).reset();
    }

    private List<String> decide(Router router, Buffer body) throws Exception {
        return RouterDecisionClient.decide(context, router, InterfaceType.OPENAI_CHAT_COMPLETIONS, body, null, key(), List.of())
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

    private static ApiKeyData key() {
        ApiKeyData key = new ApiKeyData();
        key.setPerRequestKey("decide-key");
        return key;
    }

    private void plugin(OriginPlugin plugin) {
        when(embeddedOrigins.acquire(any(), eq("p1"))).thenReturn(new EmbeddedOrigins.Lease(plugin, () -> {
        }));
    }

    /**
     * The router of {@link #router} with its chat completions served in embedded mode by plugin {@code p1}.
     */
    private Router embeddedRouter(Long timeoutMs) {
        Router router = router(timeoutMs);
        DeploymentInterface embedded = new DeploymentInterface();
        embedded.setMode(InterfaceMode.EMBEDDED);
        embedded.setPlugin("p1");
        router.setInterfaces(Map.of("openaiChatCompletions", embedded));
        return router;
    }

    /**
     * A plugin's answer with {@code body}, ended unless {@code ends} is false, as a body that stalls.
     */
    private static OriginResponse answer(int status, String body, boolean ends) {
        return new OriginResponse() {
            @Override
            public int statusCode() {
                return status;
            }

            @Override
            public MultiMap headers() {
                return MultiMap.caseInsensitiveMultiMap().add("content-type", "application/json");
            }

            @Override
            public ReadStream<Buffer> body() {
                return new OneShotStream(body, ends);
            }

            @Override
            public void cancel() {
            }
        };
    }

    /**
     * Emits its one chunk on the first resume, then ends unless it stalls.
     */
    private static final class OneShotStream implements ReadStream<Buffer> {

        private final String body;
        private final boolean ends;
        private Handler<Buffer> handler;
        private Handler<Void> end;

        OneShotStream(String body, boolean ends) {
            this.body = body;
            this.ends = ends;
        }

        @Override
        public ReadStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
            return this;
        }

        @Override
        public ReadStream<Buffer> handler(Handler<Buffer> handler) {
            this.handler = handler;
            return this;
        }

        @Override
        public ReadStream<Buffer> pause() {
            return this;
        }

        @Override
        public ReadStream<Buffer> resume() {
            if (handler != null) {
                Handler<Buffer> once = handler;
                handler = null;
                once.handle(Buffer.buffer(body));
                if (ends && end != null) {
                    end.handle(null);
                }
            }
            return this;
        }

        @Override
        public ReadStream<Buffer> fetch(long amount) {
            return resume();
        }

        @Override
        public ReadStream<Buffer> endHandler(Handler<Void> endHandler) {
            this.end = endHandler;
            return this;
        }
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status).setBody(body).setHeader("content-type", "application/json");
    }
}
