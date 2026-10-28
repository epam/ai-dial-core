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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponsesSpendLimitApiTest extends ResourceBaseTest {

    private static final String CONFIG = "dial-config/codex-spend-limits.json";
    private static final String RESPONSES_PATH = "/openai/v1/responses";
    private static final String UNVERSIONED_PATH = "/openai/responses";
    private static final String APP_PATH = "/openai/deployments/app-spend/chat/completions";
    private static final String PRIMARY = "x-codex-primary-";
    private static final String SECONDARY = "x-codex-secondary-";
    private static final String NON_STREAM_RESPONSE = "{\"id\":\"resp_1\",\"object\":\"response\",\"status\":\"completed\","
            + "\"model\":\"gpt-spend\",\"output\":[],\"usage\":{\"input_tokens\":10,\"output_tokens\":10,\"total_tokens\":20}}";
    private static final String CHAT_ANSWER = "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"app-spend\","
            + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}]}";

    @Test
    @DialConfigLocation(CONFIG)
    void testStreamingTwoCappedWindows() throws IOException {
        seedSpend("spend", "8.2");
        String sse = readFixture("responses-sse.txt");
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, RESPONSES_PATH, request -> {
                MockResponse response = new MockResponse();
                response.setResponseCode(200);
                response.setHeader("Content-Type", "text/event-stream");
                response.setChunkedBody(sse, 200);
                return response;
            });

            Response response = postResponses(RESPONSES_PATH, requestBody("gpt-spend", true), "proxyKey1");

            assertEquals(200, response.status(), response.body());
            assertEquals("82", header(response, PRIMARY + "used-percent"));
            assertEquals("1440", header(response, PRIMARY + "window-minutes"));
            assertEquals(String.valueOf(expectedReset(CalendarPeriod.DAY)), header(response, PRIMARY + "reset-at"));
            assertEquals("8", header(response, SECONDARY + "used-percent"));
            assertEquals("43200", header(response, SECONDARY + "window-minutes"));
            assertEquals(String.valueOf(expectedReset(CalendarPeriod.MONTH)), header(response, SECONDARY + "reset-at"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testNonStreamingOneCappedWindow() {
        seedSpend("weekly", "30");
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, RESPONSES_PATH, request -> TestWebServer.createResponse(200, NON_STREAM_RESPONSE,
                    "Content-Type", "application/json"));

            Response response = postResponses(RESPONSES_PATH, requestBody("gpt-spend", false), "proxyKey3");

            assertEquals(200, response.status(), response.body());
            assertEquals("30", header(response, PRIMARY + "used-percent"));
            assertEquals("10080", header(response, PRIMARY + "window-minutes"));
            assertEquals(String.valueOf(expectedReset(CalendarPeriod.WEEK)), header(response, PRIMARY + "reset-at"));
            assertFalse(hasHeaderWithPrefix(response, SECONDARY), response.headers().toString());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testUnversionedPath() {
        seedSpend("spend", "8.2");
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, UNVERSIONED_PATH, request -> TestWebServer.createResponse(200, NON_STREAM_RESPONSE,
                    "Content-Type", "application/json"));

            Response response = postResponses(UNVERSIONED_PATH, requestBody("gpt-spend", false), "proxyKey1");

            assertEquals(200, response.status(), response.body());
            assertEquals("82", header(response, PRIMARY + "used-percent"));
            assertEquals("8", header(response, SECONDARY + "used-percent"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testPercentRoundedDown() {
        seedSpend("spend", "7.499");
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, RESPONSES_PATH, request -> TestWebServer.createResponse(200, NON_STREAM_RESPONSE,
                    "Content-Type", "application/json"));

            Response response = postResponses(RESPONSES_PATH, requestBody("gpt-spend", false), "proxyKey1");

            assertEquals(200, response.status(), response.body());
            assertEquals("74", header(response, PRIMARY + "used-percent"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testHeadersCarryStateBeforeTheRequest() {
        seedSpend("spend", "6");
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, RESPONSES_PATH, request -> {
                // the request's own cost lands while it is in flight; the answer still reports the spend read before it
                seedSpend("spend", "1");
                return TestWebServer.createResponse(200, NON_STREAM_RESPONSE, "Content-Type", "application/json");
            });

            Response first = postResponses(RESPONSES_PATH, requestBody("gpt-spend", false), "proxyKey1");
            Response second = postResponses(RESPONSES_PATH, requestBody("gpt-spend", false), "proxyKey1");

            assertEquals("60", header(first, PRIMARY + "used-percent"));
            assertEquals("70", header(second, PRIMARY + "used-percent"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testUncappedCallerGetsNoHeaders() {
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, RESPONSES_PATH, request -> TestWebServer.createResponse(200, NON_STREAM_RESPONSE,
                    "Content-Type", "application/json"));

            Response response = postResponses(RESPONSES_PATH, requestBody("gpt-spend", false), "proxyKey2");

            assertEquals(200, response.status(), response.body());
            assertFalse(hasHeaderWithPrefix(response, "x-codex-"), response.headers().toString());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testUpstreamErrorHasNoCodexHeaders() {
        seedSpend("spend", "8.2");
        String error = "{\"error\":{\"message\":\"bad\",\"type\":\"invalid_request_error\"}}";
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, RESPONSES_PATH, request -> TestWebServer.createResponse(400, error,
                    "Content-Type", "application/json",
                    "x-codex-primary-used-percent", "5"));

            Response response = postResponses(RESPONSES_PATH, requestBody("gpt-spend", false), "proxyKey1");

            assertEquals(400, response.status(), response.body());
            assertFalse(hasHeaderWithPrefix(response, "x-codex-"), response.headers().toString());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testUpstreamCodexHeadersRemovedForUncappedCaller() {
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, RESPONSES_PATH, request -> TestWebServer.createResponse(200, NON_STREAM_RESPONSE,
                    "Content-Type", "application/json",
                    "x-codex-primary-used-percent", "5",
                    "X-Codex-Active-Limit", "codex"));

            Response response = postResponses(RESPONSES_PATH, requestBody("gpt-spend", false), "proxyKey2");

            assertEquals(200, response.status(), response.body());
            assertFalse(hasHeaderWithPrefix(response, "x-codex-"), response.headers().toString());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testUpstreamCodexHeadersReplaced() throws IOException {
        seedSpend("spend", "8.2");
        String sse = readFixture("responses-sse.txt");
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, RESPONSES_PATH, request -> {
                MockResponse response = new MockResponse();
                response.setResponseCode(200);
                response.setHeader("Content-Type", "text/event-stream");
                response.setHeader("x-codex-primary-used-percent", "5");
                response.setChunkedBody(sse, 200);
                return response;
            });

            Response response = postResponses(RESPONSES_PATH, requestBody("gpt-spend", true), "proxyKey1");

            assertEquals(200, response.status(), response.body());
            assertEquals("82", header(response, PRIMARY + "used-percent"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testDayCapReachedRejectsBeforeUpstream() {
        seedSpend("spend", "10");
        AtomicInteger upstreamCalls = new AtomicInteger();
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, RESPONSES_PATH, request -> {
                upstreamCalls.incrementAndGet();
                return TestWebServer.createResponse(200, NON_STREAM_RESPONSE, "Content-Type", "application/json");
            });

            long reset = expectedReset(CalendarPeriod.DAY);
            Response response = postResponses(RESPONSES_PATH, requestBody("gpt-spend", false), "proxyKey1");

            assertEquals(429, response.status(), response.body());
            assertEquals(0, upstreamCalls.get());
            assertUsageLimitReached(response, reset);
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testReachedSpendCapWinsOverTokenLimit() throws InterruptedException {
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, RESPONSES_PATH, request -> TestWebServer.createResponse(200, NON_STREAM_RESPONSE,
                    "Content-Type", "application/json"));

            // gpt-burst allows 10 tokens/minute; the first request passes and charges 20
            Response first = postResponses(RESPONSES_PATH, requestBody("gpt-burst", false), "proxyKey1");
            assertEquals(200, first.status(), first.body());

            Thread.sleep(1000); // wait for core to save usage
            seedSpend("spend", "10");
            Response second = postResponses(RESPONSES_PATH, requestBody("gpt-burst", false), "proxyKey1");

            assertEquals(429, second.status(), second.body());
            assertEquals("usage_limit_reached", parse(second.body()).at("/error/type").asText());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testRollingTokenLimitKeepsTodaysAnswer() throws InterruptedException {
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, RESPONSES_PATH, request -> TestWebServer.createResponse(200, NON_STREAM_RESPONSE,
                    "Content-Type", "application/json"));

            Response first = postResponses(RESPONSES_PATH, requestBody("gpt-burst", false), "proxyKey1");
            assertEquals(200, first.status(), first.body());

            Thread.sleep(1000); // wait for core to save usage
            Response second = postResponses(RESPONSES_PATH, requestBody("gpt-burst", false), "proxyKey1");

            assertEquals(429, second.status(), second.body());
            assertNotNull(header(second, "retry-after"));
            JsonNode error = parse(second.body()).get("error");
            assertEquals("429", error.get("code").asText());
            assertFalse(error.has("type"), second.body());
            assertFalse(hasHeaderWithPrefix(second, "x-codex-"), second.headers().toString());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testUnreadableSpendRejectsCostLimitedCaller() {
        corruptSpend("spend");
        AtomicInteger upstreamCalls = new AtomicInteger();
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, RESPONSES_PATH, request -> {
                upstreamCalls.incrementAndGet();
                return TestWebServer.createResponse(200, NON_STREAM_RESPONSE, "Content-Type", "application/json");
            });

            Response response = postResponses(RESPONSES_PATH, requestBody("gpt-spend", false), "proxyKey1");

            assertEquals(429, response.status(), response.body());
            assertEquals(0, upstreamCalls.get());
            assertEquals("{\"error\":{\"code\":\"429\",\"message\":\"DIAL could not read your spend limit; request rejected\"}}", response.body());
            assertNull(header(response, "retry-after"));
            assertFalse(hasHeaderWithPrefix(response, "x-codex-"), response.headers().toString());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testLimitCheckFailureOtherwiseKeeps500() {
        corruptSpend("free");
        AtomicInteger upstreamCalls = new AtomicInteger();
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, RESPONSES_PATH, request -> {
                upstreamCalls.incrementAndGet();
                return TestWebServer.createResponse(200, NON_STREAM_RESPONSE, "Content-Type", "application/json");
            });

            Response response = postResponses(RESPONSES_PATH, requestBody("gpt-spend", false), "proxyKey2");

            assertEquals(500, response.status(), response.body());
            assertEquals(0, upstreamCalls.get());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testApplicationCallToModelGetsHeaders() {
        seedSpend("spend", "8.2");
        AtomicReference<Response> inner = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, RESPONSES_PATH, request -> TestWebServer.createResponse(200, NON_STREAM_RESPONSE,
                    "Content-Type", "application/json"));
            server.map(HttpMethod.POST, APP_PATH, request -> {
                inner.set(postResponses(RESPONSES_PATH, requestBody("gpt-spend", false), request.getHeader("Api-Key")));
                return TestWebServer.createResponse(200, CHAT_ANSWER, "Content-Type", "application/json");
            });

            Response outer = postChat("proxyKey1");

            assertEquals(200, outer.status(), outer.body());
            assertEquals(200, inner.get().status(), inner.get().body());
            assertEquals("82", header(inner.get(), PRIMARY + "used-percent"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testApplicationCallOverCapGetsUsageLimitReached() {
        // the app itself is not cost-limited on the way in, so the cap is reached only once the outer call passed
        AtomicReference<Response> inner = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, APP_PATH, request -> {
                seedSpend("spend", "10");
                inner.set(postResponses(RESPONSES_PATH, requestBody("gpt-spend", false), request.getHeader("Api-Key")));
                return TestWebServer.createResponse(200, CHAT_ANSWER, "Content-Type", "application/json");
            });

            Response outer = postChat("proxyKey1");

            assertEquals(200, outer.status(), outer.body());
            assertEquals(429, inner.get().status(), inner.get().body());
            assertUsageLimitReached(inner.get(), expectedReset(CalendarPeriod.DAY));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testTranslatedDeploymentGetsHeaders() throws InterruptedException {
        seedSpend("spend", "8.2");
        try (TestWebServer server = new TestWebServer(4848)) {
            // the translator is the upstream of the outer request; its call back to Core is its own business
            server.map(HttpMethod.POST, RESPONSES_PATH, request -> TestWebServer.createResponse(200, NON_STREAM_RESPONSE,
                    "Content-Type", "application/json"));

            // the response reports 20 tokens against a 10 tokens/minute limit: had the outer request been charged,
            // the second one would be rejected
            Response first = postResponses(RESPONSES_PATH, requestBody("gpt-translated", false), "proxyKey1");
            assertEquals(200, first.status(), first.body());
            assertEquals("82", header(first, PRIMARY + "used-percent"));
            Thread.sleep(1000); // a charge, if any, is written after the response

            Response second = postResponses(RESPONSES_PATH, requestBody("gpt-translated", false), "proxyKey1");
            assertEquals(200, second.status(), second.body());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testTranslatedDeploymentOverCapRejectsBeforeTranslator() {
        seedSpend("spend", "10");
        AtomicInteger translatorCalls = new AtomicInteger();
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, RESPONSES_PATH, request -> {
                translatorCalls.incrementAndGet();
                return TestWebServer.createResponse(200, NON_STREAM_RESPONSE, "Content-Type", "application/json");
            });

            Response response = postResponses(RESPONSES_PATH, requestBody("gpt-translated", false), "proxyKey1");

            assertEquals(429, response.status(), response.body());
            assertEquals(0, translatorCalls.get());
            assertUsageLimitReached(response, expectedReset(CalendarPeriod.DAY));
        }
    }

    private static void assertUsageLimitReached(Response response, long reset) {
        String formattedReset = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochSecond(reset));
        JsonNode error = parse(response.body()).get("error");
        assertEquals("usage_limit_reached", error.get("type").asText(), response.body());
        assertEquals("429", error.get("code").asText());
        assertEquals("DIAL daily spend limit reached (resets " + formattedReset + ")", error.get("message").asText());
        assertEquals("You've exceeded your daily cost limit", error.get("display_message").asText());
        assertEquals(reset, error.get("resets_at").asLong());
        assertEquals(1440, error.get("limit_window_minutes").asInt());
        assertEquals("100", header(response, PRIMARY + "used-percent"));
        assertEquals("1440", header(response, PRIMARY + "window-minutes"));
        assertEquals(String.valueOf(reset), header(response, PRIMARY + "reset-at"));
        long retryAfter = Long.parseLong(header(response, "retry-after"));
        assertTrue(retryAfter > 0 && retryAfter <= 86_400, String.valueOf(retryAfter));
    }

    private void seedSpend(String project, String dollars) {
        ResourceDescriptor descriptor = costsDescriptor(project);
        dial.getProxy().getResourceService().computeResource(descriptor, json -> {
            CostRateLimit limit = json == null ? new CostRateLimit() : ProxyUtil.convertToObject(json, CostRateLimit.class);
            limit.add(System.currentTimeMillis(), new RateLimitSchedule(), new BigDecimal(dollars));
            return ProxyUtil.convertToString(limit);
        });
    }

    private void corruptSpend(String project) {
        dial.getProxy().getResourceService().computeResource(costsDescriptor(project), json -> "not json");
    }

    private static ResourceDescriptor costsDescriptor(String project) {
        String bucket = "Keys/" + project + "/";
        return ResourceDescriptorFactory.fromEntityPath(ResourceTypes.LIMIT, bucket, bucket, "costs");
    }

    private static long expectedReset(CalendarPeriod period) {
        return CalendarWindowCalculator.nextPeriodStart(period, System.currentTimeMillis(), new RateLimitSchedule()) / 1000;
    }

    private Response postResponses(String path, String body, String apiKey) {
        return send(HttpMethod.POST, path, null, body, "content-type", "application/json", "api-key", apiKey);
    }

    private Response postChat(String apiKey) {
        return send(HttpMethod.POST, APP_PATH, null, "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                "content-type", "application/json", "api-key", apiKey);
    }

    private static String requestBody(String model, boolean stream) {
        return "{\"model\":\"" + model + "\",\"stream\":" + stream + ",\"store\":false,\"input\":\"hi\"}";
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
        try (InputStream stream = ResponsesSpendLimitApiTest.class.getClassLoader().getResourceAsStream(name)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
