package com.epam.aidial.core.server;

import com.epam.aidial.core.server.log.JsonLogCapture;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import lombok.SneakyThrows;
import okhttp3.mockwebserver.MockResponse;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.junit.jupiter.api.Test;

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
            verify(send(HttpMethod.GET, "/health"), 200);

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
}
