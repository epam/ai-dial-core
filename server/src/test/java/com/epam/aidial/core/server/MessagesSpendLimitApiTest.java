package com.epam.aidial.core.server;

import com.epam.aidial.core.config.RateLimitSchedule;
import com.epam.aidial.core.server.limiter.CalendarPeriod;
import com.epam.aidial.core.server.limiter.CalendarWindowCalculator;
import com.epam.aidial.core.server.limiter.CostRateLimit;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagesSpendLimitApiTest extends ResourceBaseTest {

    private static final String CONFIG = "dial-config/anthropic-spend-limits.json";
    private static final String MESSAGES_PATH = "/anthropic/v1/messages";
    private static final String COUNT_TOKENS_PATH = "/anthropic/v1/messages/count_tokens";
    private static final String CHAT_COMPLETIONS_PATH = "/openai/deployments/gpt-spend/chat/completions";
    private static final String UNIFIED = "anthropic-ratelimit-unified-";
    private static final String OVERAGE = UNIFIED + "overage-";
    private static final String NON_STREAM_RESPONSE = "{\"id\":\"msg_01\",\"type\":\"message\",\"role\":\"assistant\","
            + "\"model\":\"claude-spend\",\"content\":[{\"type\":\"text\",\"text\":\"Who's there?\"}],"
            + "\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":10,\"output_tokens\":8,\"cache_read_input_tokens\":2}}";

    @Test
    @DialConfigLocation(CONFIG)
    void testNonStreamingAtEightyTwoPercent() {
        seedSpend("spend", "8.2");
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, MESSAGES_PATH, request -> TestWebServer.createResponse(200, NON_STREAM_RESPONSE,
                    "Content-Type", "application/json",
                    "anthropic-ratelimit-unified-status", "allowed",
                    "anthropic-ratelimit-tokens-remaining", "5"));

            Response response = postMessages(requestBody("claude-spend", false), "proxyKey1");

            assertEquals(200, response.status(), response.body());
            assertEquals(NON_STREAM_RESPONSE, response.body());
            assertWarningHeaders(response, expectedDailyReset());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testStreamingAtEightyTwoPercent() throws IOException {
        seedSpend("spend", "8.2");
        String sse = readFixture("anthropic-messages-sse.txt");
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, MESSAGES_PATH, request -> {
                MockResponse response = new MockResponse();
                response.setResponseCode(200);
                response.setHeader("Content-Type", "text/event-stream");
                response.setHeader("anthropic-ratelimit-unified-status", "allowed");
                response.setHeader("anthropic-ratelimit-tokens-remaining", "5");
                response.setChunkedBody(sse, 200);
                return response;
            });

            Response response = postMessages(requestBody("claude-spend", true), "proxyKey1");

            assertEquals(200, response.status(), response.body());
            assertTrue(response.body().contains("message_stop"), response.body());
            assertWarningHeaders(response, expectedDailyReset());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testBelowThresholdHasNoSurpassedThreshold() {
        seedSpend("spend", "5");
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, MESSAGES_PATH, request -> TestWebServer.createResponse(200, NON_STREAM_RESPONSE,
                    "Content-Type", "application/json"));

            Response response = postMessages(requestBody("claude-spend", false), "proxyKey1");

            assertEquals(200, response.status(), response.body());
            assertEquals("allowed", header(response, UNIFIED + "status"));
            assertEquals("overage", header(response, UNIFIED + "representative-claim"));
            assertEquals("allowed", header(response, OVERAGE + "status"));
            assertEquals("0.50", header(response, OVERAGE + "utilization"));
            assertNull(header(response, OVERAGE + "surpassed-threshold"));
            assertEquals(String.valueOf(expectedDailyReset()), header(response, UNIFIED + "reset"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testUpstreamErrorIsStrippedWithoutSpendHeaders() {
        seedSpend("spend", "8.2");
        String error = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"bad\"}}";
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, MESSAGES_PATH, request -> TestWebServer.createResponse(400, error,
                    "Content-Type", "application/json",
                    "anthropic-ratelimit-unified-status", "allowed",
                    "anthropic-ratelimit-tokens-remaining", "5"));

            Response response = postMessages(requestBody("claude-spend", false), "proxyKey1");

            assertEquals(400, response.status(), response.body());
            assertEquals(error, response.body());
            assertFalse(hasHeaderWithPrefix(response, "anthropic-ratelimit-"), response.headers().toString());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testSpendCapReachedRejectsBeforeUpstream() {
        seedSpend("spend", "10");
        AtomicInteger upstreamCalls = new AtomicInteger();
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, MESSAGES_PATH, request -> {
                upstreamCalls.incrementAndGet();
                return TestWebServer.createResponse(200, NON_STREAM_RESPONSE, "Content-Type", "application/json");
            });

            long reset = expectedDailyReset();
            Response response = postMessages(requestBody("claude-spend", false), "proxyKey1");

            assertEquals(429, response.status(), response.body());
            assertEquals(0, upstreamCalls.get());
            String formattedReset = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)
                    .format(Instant.ofEpochSecond(reset));
            assertEquals("{\"type\":\"error\",\"error\":{\"type\":\"billing_error\",\"message\":\"DIAL spend limit reached (daily; resets "
                    + formattedReset + ")\"}}", response.body());
            long retryAfter = Long.parseLong(header(response, "retry-after"));
            assertTrue(retryAfter > 0 && retryAfter <= 86_400, String.valueOf(retryAfter));
            assertEquals("false", header(response, "x-should-retry"));
            assertEquals("rejected", header(response, UNIFIED + "status"));
            assertEquals(String.valueOf(reset), header(response, UNIFIED + "reset"));
            assertEquals(String.valueOf(reset), header(response, OVERAGE + "reset"));
            assertEquals("1", header(response, OVERAGE + "utilization"));
            assertEquals("1", header(response, OVERAGE + "surpassed-threshold"));
            assertEquals("daily", header(response, OVERAGE + "period"));
            assertEquals("org_spend_cap_reached", header(response, OVERAGE + "disabled-reason"));
            assertNull(header(response, UNIFIED + "representative-claim"));
            assertNull(header(response, OVERAGE + "status"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testUncappedUserGetsNoHeaders() {
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, MESSAGES_PATH, request -> TestWebServer.createResponse(200, NON_STREAM_RESPONSE,
                    "Content-Type", "application/json"));

            Response response = postMessages(requestBody("claude-spend", false), "proxyKey2");

            assertEquals(200, response.status(), response.body());
            assertFalse(hasHeaderWithPrefix(response, UNIFIED), response.headers().toString());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testRollingTokenLimitAsksClientToRetry() throws InterruptedException {
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, MESSAGES_PATH, request -> TestWebServer.createResponse(200, NON_STREAM_RESPONSE,
                    "Content-Type", "application/json"));

            // claude-burst allows 10 tokens/minute; the first request passes and charges 20.
            Response first = postMessages(requestBody("claude-burst", false), "proxyKey1");
            assertEquals(200, first.status(), first.body());

            Thread.sleep(1000); // wait for core to save usage
            Response second = postMessages(requestBody("claude-burst", false), "proxyKey1");

            assertEquals(429, second.status(), second.body());
            assertEquals("true", header(second, "x-should-retry"));
            long retryAfter = Long.parseLong(header(second, "retry-after"));
            assertTrue(retryAfter >= 1 && retryAfter <= 60, String.valueOf(retryAfter));
            JsonNode error = parse(second.body()).get("error");
            assertEquals("rate_limit_error", error.get("type").asText());
            String message = error.get("message").asText();
            assertTrue(message.startsWith("DIAL per-minute token limit for claude-burst reached; retry in"), message);
            assertFalse(hasHeaderWithPrefix(second, UNIFIED), second.headers().toString());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testCountTokensUnchanged() {
        seedSpend("spend", "10");
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, COUNT_TOKENS_PATH, request -> TestWebServer.createResponse(200, "{\"input_tokens\":5}",
                    "Content-Type", "application/json",
                    "anthropic-ratelimit-tokens-remaining", "5"));

            Response response = send(HttpMethod.POST, COUNT_TOKENS_PATH, null, requestBody("claude-spend", false),
                    "content-type", "application/json", "api-key", "proxyKey1");

            assertEquals(200, response.status(), response.body());
            assertEquals("5", header(response, "anthropic-ratelimit-tokens-remaining"));
            assertFalse(hasHeaderWithPrefix(response, UNIFIED), response.headers().toString());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testChatCompletionsKeepsAzureFormat() {
        seedSpend("spend", "10");
        try (TestWebServer server = new TestWebServer(4848)) {
            Response response = send(HttpMethod.POST, CHAT_COMPLETIONS_PATH, null,
                    "{\"messages\":[{\"role\":\"user\",\"content\":\"Knock, knock\"}]}",
                    "content-type", "application/json", "api-key", "proxyKey1");

            assertEquals(429, response.status(), response.body());
            JsonNode error = parse(response.body()).get("error");
            assertEquals("429", error.get("code").asText());
            assertFalse(error.has("type"), response.body());
            assertFalse(hasHeaderWithPrefix(response, "anthropic-ratelimit-"), response.headers().toString());
            assertNull(header(response, "x-should-retry"));
        }
    }

    void seedSpend(String project, String dollars) {
        String bucket = "Keys/" + project + "/";
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromEntityPath(ResourceTypes.LIMIT, bucket, bucket, "costs");
        dial.getProxy().getResourceService().computeResource(descriptor, json -> {
            CostRateLimit limit = new CostRateLimit();
            limit.add(System.currentTimeMillis(), new RateLimitSchedule(), new BigDecimal(dollars));
            return ProxyUtil.convertToString(limit);
        });
    }

    private static long expectedDailyReset() {
        return CalendarWindowCalculator.nextPeriodStart(CalendarPeriod.DAY, System.currentTimeMillis(), new RateLimitSchedule()) / 1000;
    }

    private static void assertWarningHeaders(Response response, long reset) {
        assertEquals("allowed_warning", header(response, UNIFIED + "status"));
        assertEquals("overage", header(response, UNIFIED + "representative-claim"));
        assertEquals("allowed_warning", header(response, OVERAGE + "status"));
        assertEquals("0.82", header(response, OVERAGE + "utilization"));
        assertEquals("0.75", header(response, OVERAGE + "surpassed-threshold"));
        assertEquals(String.valueOf(reset), header(response, UNIFIED + "reset"));
        assertEquals(String.valueOf(reset), header(response, OVERAGE + "reset"));
        assertNull(header(response, "anthropic-ratelimit-tokens-remaining"));
    }

    private Response postMessages(String body, String apiKey) {
        return send(HttpMethod.POST, MESSAGES_PATH, null, body,
                "content-type", "application/json", "api-key", apiKey, "anthropic-version", "2023-06-01");
    }

    private static String requestBody(String model, boolean stream) {
        return "{\"model\":\"" + model + "\",\"max_tokens\":100,\"stream\":" + stream
                + ",\"messages\":[{\"role\":\"user\",\"content\":\"Knock, knock\"}]}";
    }

    private static String header(Response response, String name) {
        return response.headers().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(entry -> entry.getValue())
                .findFirst()
                .orElse(null);
    }

    private static boolean hasHeaderWithPrefix(Response response, String prefix) {
        return response.headers().keySet().stream().anyMatch(name -> name.regionMatches(true, 0, prefix, 0, prefix.length()));
    }

    private static JsonNode parse(String body) {
        try {
            return ProxyUtil.MAPPER.readTree(body);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String readFixture(String name) throws IOException {
        try (InputStream stream = MessagesSpendLimitApiTest.class.getClassLoader().getResourceAsStream(name)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
