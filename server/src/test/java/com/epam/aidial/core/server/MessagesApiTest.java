package com.epam.aidial.core.server;

import com.epam.aidial.core.server.data.FeaturesData;
import com.epam.aidial.core.server.data.LimitStats;
import com.epam.aidial.core.server.util.ProxyUtil;
import io.vertx.core.http.HttpMethod;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MessagesApiTest extends ResourceBaseTest {

    private static final String MESSAGES_PATH = "/anthropic/v1/messages";
    private static final String COUNT_TOKENS_PATH = "/anthropic/v1/messages/count_tokens";

    // input_tokens=10 (excludes cache), cache_read=2, output_tokens=8, no total_tokens.
    // DIAL accounting: prompt = 10 + 2 = 12, total = 20 (OpenAI semantics: cached tokens are part of prompt).
    private static final String NON_STREAM_RESPONSE = "{\"id\":\"msg_01\",\"type\":\"message\",\"role\":\"assistant\","
            + "\"model\":\"claude-sonnet\",\"content\":[{\"type\":\"text\",\"text\":\"Who’s there?\"}],"
            + "\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":10,\"output_tokens\":8,\"cache_read_input_tokens\":2}}";

    private static String requestBody(String model, boolean stream) {
        return "{\"model\":\"" + model + "\",\"max_tokens\":100,\"stream\":" + stream
                + ",\"messages\":[{\"role\":\"user\",\"content\":\"Knock, knock\"}]}";
    }

    @Test
    public void testNonStreamingRoundTripAndUsage() throws IOException, InterruptedException {
        AtomicReference<RecordedRequest> captured = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848); CloseableHttpClient client = newClient()) {
            server.map(HttpMethod.POST, MESSAGES_PATH, request -> {
                captured.set(request);
                return TestWebServer.createResponse(200, NON_STREAM_RESPONSE, "Content-Type", "application/json");
            });

            Response response = post(client, MESSAGES_PATH, requestBody("claude-ns", false),
                    "api-key", "proxyKey1", "anthropic-version", "2023-06-01");

            assertEquals(200, response.status());
            assertEquals(NON_STREAM_RESPONSE, response.body());
            // exact ingress path is forwarded (base_url + request.uri())
            assertEquals(MESSAGES_PATH, captured.get().getPath());
            // Anthropic-specific headers pass through to the adapter
            assertEquals("2023-06-01", captured.get().getHeader("anthropic-version"));

            Thread.sleep(1000); // wait for core to save usage
            assertUsed("claude-ns", 20);
        }
    }

    @Test
    public void testStreamingAccumulatesUsageFromBothEvents() throws IOException, InterruptedException {
        String sse = readFixture("anthropic-messages-sse.txt");
        try (TestWebServer server = new TestWebServer(4848); CloseableHttpClient client = newClient()) {
            server.map(HttpMethod.POST, MESSAGES_PATH, request -> {
                MockResponse response = new MockResponse();
                response.setResponseCode(200);
                response.setHeader("Content-Type", "text/event-stream");
                response.setChunkedBody(sse, 200);
                return response;
            });

            Response response = post(client, MESSAGES_PATH, requestBody("claude-stream", true), "api-key", "proxyKey1");

            assertEquals(200, response.status());
            // events are re-serialized by the SSE listener chain, so assert content rather than exact bytes
            String body = response.body();
            assertTrue(body.contains("message_start"), body);
            assertTrue(body.contains("message_stop"), body);
            assertTrue(body.contains("Who"), body);

            Thread.sleep(1000);
            // input_tokens (10) + cache_read (3) from message_start + output_tokens (8, message_delta);
            // the prompt half must not be lost to the usage split across events.
            assertUsed("claude-stream", 21);
        }
    }

    @Test
    public void testCountTokensDoesNotChargeLimits() throws IOException, InterruptedException {
        AtomicReference<RecordedRequest> captured = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848); CloseableHttpClient client = newClient()) {
            server.map(HttpMethod.POST, COUNT_TOKENS_PATH, request -> {
                captured.set(request);
                return TestWebServer.createResponse(200, "{\"input_tokens\":5}", "Content-Type", "application/json");
            });

            Response response = post(client, COUNT_TOKENS_PATH, requestBody("claude-count", false), "api-key", "proxyKey1");

            assertEquals(200, response.status());
            assertEquals("{\"input_tokens\":5}", response.body());
            assertEquals(COUNT_TOKENS_PATH, captured.get().getPath());

            Thread.sleep(1000);
            assertUsed("claude-count", 0); // count_tokens must not charge
        }
    }

    @Test
    public void testAutoCachingPinsSecondTurnToSameUpstream() throws IOException, InterruptedException {
        AtomicReference<String> firstUpstreamKey = new AtomicReference<>();
        AtomicReference<String> secondUpstreamKey = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848); CloseableHttpClient client = newClient()) {
            server.map(HttpMethod.POST, MESSAGES_PATH, request -> {
                String upstreamKey = request.getHeader("X-UPSTREAM-KEY");
                if (firstUpstreamKey.get() == null) {
                    firstUpstreamKey.set(upstreamKey);
                } else {
                    secondUpstreamKey.set(upstreamKey);
                }
                long expireAt = Instant.now().plusSeconds(600).getEpochSecond();
                return TestWebServer.createResponse(200, NON_STREAM_RESPONSE,
                        "Content-Type", "application/json",
                        "X-DIAL-CACHE-BREAKPOINT-PATH", "prefix.body.messages[0].content[0]",
                        "X-DIAL-CACHE-EXPIRE-AT", String.valueOf(expireAt));
            });

            String firstTurn = "{\"model\":\"claude-cache\",\"max_tokens\":100,\"stream\":false,"
                    + "\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"}]}";
            Response first = post(client, MESSAGES_PATH, firstTurn, "api-key", "proxyKey1");
            assertEquals(200, first.status());
            Thread.sleep(1000); // wait for the async Redis update after the first turn

            String secondTurn = "{\"model\":\"claude-cache\",\"max_tokens\":100,\"stream\":false,"
                    + "\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"},{\"role\":\"assistant\",\"content\":\"Hi there\"}]}";
            Response second = post(client, MESSAGES_PATH, secondTurn, "api-key", "proxyKey1");
            assertEquals(200, second.status());

            assertNotNull(firstUpstreamKey.get());
            assertEquals(firstUpstreamKey.get(), secondUpstreamKey.get());
        }
    }

    @Test
    public void testXapiKeyAuthenticatesAndIsNotLeaked() throws IOException {
        AtomicReference<RecordedRequest> captured = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848); CloseableHttpClient client = newClient()) {
            server.map(HttpMethod.POST, MESSAGES_PATH, request -> {
                captured.set(request);
                return TestWebServer.createResponse(200, NON_STREAM_RESPONSE, "Content-Type", "application/json");
            });

            // Only x-api-key carries the DIAL key (no api-key, no Authorization) — mirrors Claude Code.
            Response response = post(client, MESSAGES_PATH, requestBody("claude-xapikey", false), "x-api-key", "proxyKey1");

            assertEquals(200, response.status());
            RecordedRequest upstream = captured.get();
            // client's x-api-key (the DIAL key) is stripped upstream ...
            assertNull(upstream.getHeader("x-api-key"));
            // ... while the per-request DIAL Api-Key is injected for the adapter.
            assertNotNull(upstream.getHeader("Api-Key"));
        }
    }

    @Test
    public void testBadXapiKeyRejected() throws IOException {
        try (TestWebServer server = new TestWebServer(4848); CloseableHttpClient client = newClient()) {
            Response response = post(client, MESSAGES_PATH, requestBody("claude-xapikey", false), "x-api-key", "bad-key");
            assertEquals(401, response.status());
        }
    }

    @Test
    public void testXapiKeyAcceptedOnAnyEndpointWhenOnlyCredential() throws IOException {
        try (CloseableHttpClient client = newClient()) {
            // The x-api-key fallback is global (consulted when neither api-key nor Authorization is
            // present), so it authenticates non-Anthropic endpoints too: 404 (unknown model), not 401.
            Response response = post(client, "/openai/v1/responses",
                    "{\"model\":\"no-such-model\",\"input\":\"hi\"}", "x-api-key", "proxyKey1");
            assertEquals(404, response.status());
        }
    }

    @Test
    public void testRateLimitReturns429() throws IOException, InterruptedException {
        try (TestWebServer server = new TestWebServer(4848); CloseableHttpClient client = newClient()) {
            server.map(HttpMethod.POST, MESSAGES_PATH,
                    request -> TestWebServer.createResponse(200, NON_STREAM_RESPONSE, "Content-Type", "application/json"));

            // claude-limited allows 10 tokens/minute; the first request passes and charges 20.
            Response first = post(client, MESSAGES_PATH, requestBody("claude-limited", false), "api-key", "proxyKey1");
            assertEquals(200, first.status());

            Thread.sleep(1000); // wait for core to save usage
            Response second = post(client, MESSAGES_PATH, requestBody("claude-limited", false), "api-key", "proxyKey1");
            assertEquals(429, second.status());
        }
    }

    @Test
    public void testExplicitUpstreamSendsKeyAndEndpointHeader() throws IOException {
        AtomicReference<RecordedRequest> captured = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848); CloseableHttpClient client = newClient()) {
            server.map(HttpMethod.POST, MESSAGES_PATH, request -> {
                captured.set(request);
                return TestWebServer.createResponse(200, NON_STREAM_RESPONSE, "Content-Type", "application/json");
            });

            Response response = post(client, MESSAGES_PATH, requestBody("claude-upstream", false), "api-key", "proxyKey1");

            assertEquals(200, response.status());
            // the upstream endpoint is propagated to the adapter (as for chat completions / responses)
            assertEquals("http://messages-upstream/v1/messages", captured.get().getHeader("X-UPSTREAM-ENDPOINT"));
            assertEquals("modelKey", captured.get().getHeader("X-UPSTREAM-KEY"));
        }
    }

    @Test
    public void testDeploymentIdHeaderCarriesRequestedModel() throws IOException {
        AtomicReference<RecordedRequest> captured = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848); CloseableHttpClient client = newClient()) {
            server.map(HttpMethod.POST, MESSAGES_PATH, request -> {
                captured.set(request);
                return TestWebServer.createResponse(200, NON_STREAM_RESPONSE, "Content-Type", "application/json");
            });

            // a client-supplied value must not survive: the header is set by the core, not forwarded
            Response response = post(client, MESSAGES_PATH, requestBody("claude-ns", false),
                    "api-key", "proxyKey1", "X-DIAL-DEPLOYMENT-ID", "spoofed");

            assertEquals(200, response.status());
            assertEquals("claude-ns", captured.get().getHeader("X-DIAL-DEPLOYMENT-ID"));
        }
    }

    @Test
    public void testDeploymentIdHeaderKeepsRequestedModelWhenOverrideNameApplied() throws IOException {
        AtomicReference<RecordedRequest> captured = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848); CloseableHttpClient client = newClient()) {
            server.map(HttpMethod.POST, MESSAGES_PATH, request -> {
                captured.set(request);
                return TestWebServer.createResponse(200, NON_STREAM_RESPONSE, "Content-Type", "application/json");
            });

            Response response = post(client, MESSAGES_PATH, requestBody("claude-override", false), "api-key", "proxyKey1");

            assertEquals(200, response.status());
            RecordedRequest upstream = captured.get();
            // the header keeps the DIAL deployment id ...
            assertEquals("claude-override", upstream.getHeader("X-DIAL-DEPLOYMENT-ID"));
            // ... while the body model is rewritten to the provider-side name
            assertTrue(upstream.getBody().readUtf8().contains("\"model\":\"claude-sonnet-4-5-20250929\""));
            assertEquals("claude-sonnet-4-5-20250929", upstream.getHeader("X-DIAL-OVERRIDE-NAME"));
        }
    }

    @Test
    public void testCountTokensSendsDeploymentIdHeader() throws IOException {
        AtomicReference<RecordedRequest> captured = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848); CloseableHttpClient client = newClient()) {
            server.map(HttpMethod.POST, COUNT_TOKENS_PATH, request -> {
                captured.set(request);
                return TestWebServer.createResponse(200, "{\"input_tokens\":5}", "Content-Type", "application/json");
            });

            Response response = post(client, COUNT_TOKENS_PATH, requestBody("claude-count", false), "api-key", "proxyKey1");

            assertEquals(200, response.status());
            assertEquals("claude-count", captured.get().getHeader("X-DIAL-DEPLOYMENT-ID"));
        }
    }

    @Test
    public void testDeploymentFeaturesHeaderSentToTranslatingAdapter() throws IOException {
        AtomicReference<RecordedRequest> captured = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848); CloseableHttpClient client = newClient()) {
            server.map(HttpMethod.POST, "/to-chat-completions" + MESSAGES_PATH, request -> {
                captured.set(request);
                return TestWebServer.createResponse(200, NON_STREAM_RESPONSE, "Content-Type", "application/json");
            });

            // a client-supplied value must not survive: the header is set by the core, not forwarded
            Response response = post(client, MESSAGES_PATH, requestBody("claude-to-chat-completions", false),
                    "api-key", "proxyKey1", "X-DIAL-DEPLOYMENT-FEATURES", "spoofed");

            assertEquals(200, response.status());
            FeaturesData features = ProxyUtil.convertToObject(
                    captured.get().getHeader("X-DIAL-DEPLOYMENT-FEATURES"), FeaturesData.class);
            assertNotNull(features);
            assertFalse(features.isMaxTokensSupported());
            assertFalse(features.isCustomTemperatureSupported());
            assertTrue(features.isTools());
        }
    }

    @Test
    public void testDeploymentFeaturesHeaderSentToResponsesTranslatingAdapter() throws IOException {
        AtomicReference<RecordedRequest> captured = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848); CloseableHttpClient client = newClient()) {
            server.map(HttpMethod.POST, "/to-responses" + MESSAGES_PATH, request -> {
                captured.set(request);
                return TestWebServer.createResponse(200, NON_STREAM_RESPONSE, "Content-Type", "application/json");
            });

            Response response = post(client, MESSAGES_PATH, requestBody("claude-to-responses", false), "api-key", "proxyKey1");

            assertEquals(200, response.status());
            assertNotNull(captured.get().getHeader("X-DIAL-DEPLOYMENT-FEATURES"));
        }
    }

    @Test
    public void testDeploymentFeaturesHeaderOmittedForPassThroughAdapter() throws IOException {
        AtomicReference<RecordedRequest> captured = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848); CloseableHttpClient client = newClient()) {
            server.map(HttpMethod.POST, MESSAGES_PATH, request -> {
                captured.set(request);
                return TestWebServer.createResponse(200, NON_STREAM_RESPONSE, "Content-Type", "application/json");
            });

            Response response = post(client, MESSAGES_PATH, requestBody("claude-ns", false), "api-key", "proxyKey1");

            assertEquals(200, response.status());
            assertNull(captured.get().getHeader("X-DIAL-DEPLOYMENT-FEATURES"));
        }
    }

    @Test
    public void testUnsupportedInterfaceReturns503() throws IOException {
        try (TestWebServer server = new TestWebServer(4848); CloseableHttpClient client = newClient()) {
            Response response = post(client, MESSAGES_PATH, requestBody("claude-no-iface", false), "api-key", "proxyKey1");
            assertEquals(503, response.status());
        }
    }

    private void assertUsed(String deployment, long expected) {
        Response response = send(HttpMethod.GET, "/v1/deployments/" + deployment + "/limits", null, null);
        verify(response, 200);
        LimitStats stats = ProxyUtil.convertToObject(response.body(), LimitStats.class);
        assertNotNull(stats);
        assertEquals(expected, stats.getDayTokenStats().getUsed());
        assertEquals(expected, stats.getMinuteTokenStats().getUsed());
    }

    private Response post(CloseableHttpClient client, String path, String body, String... headers) throws IOException {
        String uri = "http://127.0.0.1:" + serverPort + path;
        HttpUriRequestBase request = new HttpUriRequestBase(HttpMethod.POST.name(), URI.create(uri));
        request.setHeader("content-type", "application/json");
        for (int i = 0; i < headers.length; i += 2) {
            request.setHeader(headers[i], headers[i + 1]);
        }
        request.setEntity(new StringEntity(body, StandardCharsets.UTF_8));
        return client.execute(request, ResourceBaseTest::toResponse);
    }

    private static CloseableHttpClient newClient() {
        return HttpClientBuilder.create().disableAutomaticRetries().build();
    }

    private static String readFixture(String name) throws IOException {
        try (InputStream stream = MessagesApiTest.class.getClassLoader().getResourceAsStream(name)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
