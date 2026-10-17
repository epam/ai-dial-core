package com.epam.aidial.core.server;

import com.epam.aidial.core.server.log.JsonLogCapture;
import com.epam.aidial.core.server.security.ExtractedClaims;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import lombok.SneakyThrows;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InFlightRequestLimitApiTest extends ResourceBaseTest {

    private static final String CHAT_URI = "/openai/deployments/gpt-3-turbo/chat/completions";
    private static final String CHAT_REQUEST = "{\"model\":\"gpt-3-turbo\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    private static final String CHAT_ANSWER = "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"gpt-35-turbo\","
            + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}]}";

    private final CountDownLatch arrived = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger upstreamCalls = new AtomicInteger();

    @Override
    protected JsonObject additionalSettingsOverrides() {
        return new JsonObject().put("proxy", new JsonObject().put("maxInFlightRequests", 1));
    }

    @Test
    void testRejectsPastTheLimit_AndAdmitsAgainOnceTheResponseEnds() throws Exception {
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/chat/completions", request -> holdFirstCall());

            CompletableFuture<Response> first = CompletableFuture.supplyAsync(this::sendChat);
            assertTrue(arrived.await(10, TimeUnit.SECONDS));

            Response rejected = sendChat();
            verify(rejected, 503);
            assertEquals("1", rejected.headers().get("retry-after"));
            // a header alone does not exempt a request from the count
            verify(send(HttpMethod.POST, CHAT_URI, null, CHAT_REQUEST, "content-type", "application/json", "upgrade", "websocket"), 503);
            verify(send(HttpMethod.GET, "/health"), 200);
            // the limit is checked after authentication
            verify(send(HttpMethod.POST, CHAT_URI, null, CHAT_REQUEST, "content-type", "application/json", "api-key", "unknown-key"), 401);

            release.countDown();
            verify(first.get(10, TimeUnit.SECONDS), 200);
            verify(sendChat(), 200);
            assertEquals(2, upstreamCalls.get());
        }
    }

    @Test
    void testAdmitsAgainOnceTheClientCloses() throws Exception {
        try (TestWebServer server = new TestWebServer(4848);
                CloseableHttpClient leaving = HttpClientBuilder.create().disableAutomaticRetries().build();
                JsonLogCapture logs = JsonLogCapture.attach(CHAT_URI)) {
            server.map(HttpMethod.POST, "/chat/completions", request -> holdFirstCall());

            CompletableFuture.runAsync(() -> sendChat(leaving));
            assertTrue(arrived.await(10, TimeUnit.SECONDS));
            HttpServerResponse heldResponse = logs.awaitProxyContext().getResponse();

            leaving.close();
            long deadline = System.currentTimeMillis() + 10_000;
            while (!heldResponse.closed()) {
                assertTrue(System.currentTimeMillis() < deadline, "The core did not observe the client disconnect");
                Thread.sleep(10);
            }

            verify(sendChat(), 200);
            release.countDown();
        }
    }

    /**
     * Authorization is asynchronous, so the client may leave before the request is counted.
     * Neither handler fires after a close, so the request gives its slot back at once.
     */
    @Test
    void testAdmitsAgainWhenTheClientLeavesDuringAuthorization() throws Exception {
        Promise<ExtractedClaims> claims = Promise.promise();
        CountDownLatch authorizing = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
            authorizing.countDown();
            return claims.future();
        }).when(validator).extractClaims("held");

        try (CloseableHttpClient leaving = HttpClientBuilder.create().disableAutomaticRetries().build()) {
            CompletableFuture.runAsync(() -> sendHeldAuthorization(leaving));
            assertTrue(authorizing.await(10, TimeUnit.SECONDS));
        }
        // over loopback the core sees the close well within this
        Thread.sleep(500);
        claims.complete(createClaims("user"));

        awaitFreeSlot("The request whose client left during authorization kept its slot");
    }

    /**
     * Core resets the response when the upstream drops mid-stream. A reset skips the response endHandler,
     * so the closed connection gives the slot back.
     */
    @Test
    void testAdmitsAgainOnceTheCoreResetsTheResponse() throws Exception {
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/chat/completions", new MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setChunkedBody("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"}}]}\n\n".repeat(20), 64)
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
            try {
                sendChat();
            } catch (Exception e) {
                // the core dropped the connection
            }

            awaitFreeSlot("The reset response kept its slot");
        }
    }

    /**
     * A per-request key belongs to a request admitted earlier, so the application's call back into Core
     * is not counted: rejecting it would fail the request that holds the only slot.
     */
    @Test
    void testCallWithPerRequestKeyIsNotCounted() {
        try (TestWebServer server = new TestWebServer(7001)) {
            AtomicInteger callStatus = new AtomicInteger();
            server.map(HttpMethod.POST, "/openai/deployments/10k/chat/completions", request -> {
                callStatus.set(send(HttpMethod.GET, "/v1/bucket", null, null, "api-key", request.getHeader("api-key")).status());
                return new MockResponse().setResponseCode(200).setBody(CHAT_ANSWER).setHeader("Content-Type", "application/json");
            });

            verify(send(HttpMethod.POST, "/openai/deployments/app/chat/completions", null, CHAT_REQUEST, "content-type", "application/json"), 200);
            assertEquals(200, callStatus.get());
        }
    }

    /**
     * An open subscription never ends its response, so it gives the slot back once it is set up.
     */
    @Test
    void testSubscriptionGivesItsSlotBackOnceSetUp() throws Exception {
        try (EventStream ignored = subscribe("""
                {"resources": [{"url": "conversations/%s/folder/conversation"}]}
                """.formatted(bucket))) {
            awaitFreeSlot("The open subscription kept its slot");
        }
    }

    /**
     * An upgraded WebSocket never ends its HTTP response, so its handler gives the slot back once the upgrade
     * succeeds. Without that, every WebSocket would hold a slot forever.
     */
    @Test
    void testWebSocketGivesItsSlotBackOnceUpgraded() throws Exception {
        CountDownLatch opened = new CountDownLatch(1);
        try (MockWebServer upstream = new MockWebServer()) {
            upstream.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    return new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                    });
                }
            });
            upstream.start(9876);

            Request request = new Request.Builder()
                    .url("ws://localhost:" + serverPort + "/v1/websocket")
                    .addHeader("api-key", "vstore_user_key")
                    .build();
            WebSocket webSocket = new OkHttpClient().newWebSocket(request, new WebSocketListener() {
                @Override
                public void onOpen(WebSocket webSocket, okhttp3.Response response) {
                    opened.countDown();
                }
            });
            try {
                assertTrue(opened.await(10, TimeUnit.SECONDS));
                awaitFreeSlot("The open WebSocket kept its slot");
            } finally {
                webSocket.close(1000, "done");
            }
        }
    }

    private void awaitFreeSlot(String message) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (send(HttpMethod.GET, "/openai/deployments").status() == 503) {
            assertTrue(System.currentTimeMillis() < deadline, message);
            Thread.sleep(50);
        }
    }

    @SneakyThrows
    private MockResponse holdFirstCall() {
        if (upstreamCalls.incrementAndGet() == 1) {
            arrived.countDown();
            release.await(10, TimeUnit.SECONDS);
        }
        return new MockResponse().setResponseCode(200).setBody(CHAT_ANSWER).setHeader("Content-Type", "application/json");
    }

    private Response sendChat() {
        return send(HttpMethod.POST, CHAT_URI, null, CHAT_REQUEST, "content-type", "application/json");
    }

    @SneakyThrows
    private void sendChat(CloseableHttpClient client) {
        HttpPost request = new HttpPost("http://127.0.0.1:" + serverPort + CHAT_URI);
        request.setHeader("api-key", "proxyKey1");
        request.setHeader("content-type", "application/json");
        request.setEntity(new StringEntity(CHAT_REQUEST));
        try {
            client.execute(request, response -> null);
        } catch (IOException e) {
            // the client closed its own connection
        }
    }

    @SneakyThrows
    private void sendHeldAuthorization(CloseableHttpClient client) {
        HttpGet request = new HttpGet("http://127.0.0.1:" + serverPort + "/openai/deployments");
        request.setHeader("authorization", "held");
        try {
            client.execute(request, response -> null);
        } catch (IOException e) {
            // the client closed its own connection
        }
    }
}
