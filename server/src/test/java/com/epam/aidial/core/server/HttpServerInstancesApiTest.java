package com.epam.aidial.core.server;

import io.vertx.core.Future;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import lombok.SneakyThrows;
import okhttp3.mockwebserver.MockResponse;
import org.apache.hc.client5.http.classic.methods.HttpUriRequest;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("checkstyle:LineLength")
public class HttpServerInstancesApiTest extends ResourceBaseTest {

    private static final int CLIENTS = 32;
    private static final String STREAM = """
            data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":"first"}}],"usage":null}

            data: {"id":"chatcmpl-2","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":"second"}}],"usage":null}

            data: {"id":"chatcmpl-3","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":"stop","delta":{}}],"usage":{"completion_tokens":20,"prompt_tokens":20,"total_tokens":40}}

            data: [DONE]

            """;

    @Override
    protected JsonObject additionalSettingsOverrides() {
        // a negative port is one random port shared by all instances; with port 0 each instance would bind its own
        return new JsonObject().put("server", new JsonObject().put("instances", 4).put("port", -1));
    }

    @Test
    public void testConcurrentStreamedRequests() throws Exception {
        Set<String> threads = ConcurrentHashMap.newKeySet();
        Mockito.doAnswer(invocation -> {
            threads.add(Thread.currentThread().getName());
            return Future.succeededFuture(createClaims("user"));
        }).when(validator).extractClaims(Mockito.any());
        AtomicInteger originRequests = new AtomicInteger();

        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/chat/completions", request -> {
                originRequests.incrementAndGet();
                return new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "text/event-stream")
                        .setChunkedBody(STREAM, 16);
            });

            ExecutorService executor = Executors.newFixedThreadPool(CLIENTS);
            try {
                List<CompletableFuture<Response>> responses = IntStream.range(0, CLIENTS)
                        .mapToObj(i -> CompletableFuture.supplyAsync(this::sendStreamedChatOnOwnConnection, executor))
                        .toList();
                for (CompletableFuture<Response> future : responses) {
                    Response response = future.get(1, TimeUnit.MINUTES);
                    verify(response, 200);
                    String body = response.body();
                    assertTrue(body.indexOf("\"first\"") < body.indexOf("\"second\"") && body.indexOf("\"first\"") >= 0, body);
                    assertTrue(body.strip().endsWith("data: [DONE]"), body);
                }
            } finally {
                executor.shutdownNow();
            }
        }

        assertEquals(CLIENTS, originRequests.get());
        // design-level check: Vert.x handed the connections to more than one instance's event loop
        assertTrue(threads.size() > 1, threads::toString);
        assertTrue(threads.stream().allMatch(name -> name.startsWith("vert.x-eventloop-thread-")), threads::toString);
    }

    @Test
    public void testStopReleasesTheSharedPort() throws Exception {
        dial.stop();
        // stopped here; the teardown must not stop it again, a closed Vert.x rejects a second close
        dial = null;

        // undeploy closed the server of every instance, so the shared port binds again
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", serverPort));
        }
    }

    @SneakyThrows
    private Response sendStreamedChatOnOwnConnection() {
        HttpUriRequest request = new HttpUriRequestBase(HttpMethod.POST.name(),
                URI.create("http://127.0.0.1:" + serverPort + "/openai/deployments/gpt-3-turbo/chat/completions"));
        request.setHeader("authorization", "user");
        request.setHeader("content-type", "application/json");
        request.setEntity(new StringEntity("""
                {"model":"gpt-3-turbo","stream":true,"messages":[{"role":"user","content":"how are you?"}]}
                """));
        // a client per request, so each request comes on its own connection: connections are what Vert.x spreads
        try (CloseableHttpClient client = HttpClientBuilder.create().disableAutomaticRetries().build()) {
            return client.execute(request, ResourceBaseTest::toResponse);
        }
    }
}
