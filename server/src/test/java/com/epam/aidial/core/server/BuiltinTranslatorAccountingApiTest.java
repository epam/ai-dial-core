package com.epam.aidial.core.server;

import com.epam.aidial.core.server.data.LimitStats;
import com.epam.aidial.core.server.log.AnalyticsLogContext;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The records, limits and routing of {@code POST /anthropic/v1/messages} for a model served by a builtin translator:
 * one analytics row, billed as the Responses pass of a translator service would be, upstream cache affinity on the
 * translated body, retries and a client that leaves.
 */
class BuiltinTranslatorAccountingApiTest extends ResourceBaseTest {

    private static final String CONFIG = "dial-config/builtin-translator-accounting.json";
    private static final String MODEL = "gpt-builtin-priced";
    private static final String MESSAGES_PATH = "/anthropic/v1/messages";
    private static final String RESPONSES_PATH = "/openai/v1/responses";
    private static final String PLAIN_USAGE = "{\"input_tokens\":10,\"output_tokens\":8,\"total_tokens\":18}";
    private static final String MESSAGES_BODY = messagesBody("[{\"role\":\"user\",\"content\":\"Knock, knock\"}]");

    @Test
    @DialConfigLocation(CONFIG)
    void testOneClientViewRowWithCost() {
        try (TestWebServer server = origin(request -> eventStream(responsesStream(PLAIN_USAGE)))) {
            Response response = post(MESSAGES_PATH, MESSAGES_BODY);
            verify(response, 200);

            List<AnalyticsLogContext> rows = logStore.await(MESSAGES_PATH, 1);
            assertEquals(1, rows.size());
            AnalyticsLogContext row = rows.get(0);
            assertEquals(MODEL, row.getDeploymentName());
            assertEquals(MESSAGES_PATH, row.getRequestUri());
            JsonNode requestBody = parse(row.getRequestBody().toString());
            assertEquals(MODEL, requestBody.path("model").asText());
            assertEquals(parse(MESSAGES_BODY).path("messages"), requestBody.path("messages"));
            // the interface default the client did not set
            assertEquals(0.3, requestBody.path("temperature").asDouble(), requestBody.toString());
            List<String> events = eventNames(row.getResponseBody().toString());
            assertTrue(events.contains("message_start"), events.toString());
            assertTrue(events.contains("message_stop"), events.toString());
            assertFalse(events.stream().anyMatch(event -> event.startsWith("response.")), events.toString());
            TokenUsage usage = row.getTokenUsage();
            assertEquals(18, usage.getTotalTokens());
            assertCost("0.26", usage.getAggCost());
            assertCost("0.26", usage.getCost());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testUpstreamErrorWritesOneRow() {
        try (TestWebServer server = origin(request -> TestWebServer.createResponse(400,
                "{\"error\":{\"message\":\"Invalid value\",\"code\":\"invalid_value\"}}", "Content-Type", "application/json"))) {
            Response response = post(MESSAGES_PATH, MESSAGES_BODY);
            verify(response, 400);

            List<AnalyticsLogContext> rows = logStore.await(MESSAGES_PATH, 1);
            assertEquals(1, rows.size());
            assertEquals(400, rows.get(0).getResponseStatusCode());
            assertNull(rows.get(0).getTokenUsage());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testBilledAsItsResponsesPass() {
        assertBilledAsItsResponsesPass(PLAIN_USAGE, 18, "0.26");
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testTokensCountedFromTheAnthropicUsage() {
        // no total_tokens upstream: the count is the Anthropic input plus output, not the upstream total
        try (TestWebServer server = origin(request -> eventStream(responsesStream("{\"input_tokens\":10,\"output_tokens\":8}")))) {
            verify(post(MESSAGES_PATH, MESSAGES_BODY), 200);
            logStore.await(MESSAGES_PATH, 1);

            assertEquals(18, usage().getDayTokenStats().getUsed());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testCachedTokensBilledAsItsResponsesPass() {
        // 60 x 0.01 + 40 x 0.001 + 10 x 0.02
        assertBilledAsItsResponsesPass("{\"input_tokens\":100,\"input_tokens_details\":{\"cached_tokens\":40},\"output_tokens\":10,\"total_tokens\":110}",
                110, "0.84");
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testReasoningTokensBilledAsItsResponsesPass() {
        assertBilledAsItsResponsesPass("{\"input_tokens\":10,\"output_tokens\":30,\"output_tokens_details\":{\"reasoning_tokens\":12},\"total_tokens\":40}",
                40, "0.70");
    }

    /**
     * Sends the same upstream answer once on the Responses interface and once through the builtin translator, and
     * compares what each adds to the caller's token, cost and request records of the model.
     */
    private void assertBilledAsItsResponsesPass(String upstreamUsage, long tokens, String cost) {
        try (TestWebServer server = origin(request -> eventStream(responsesStream(upstreamUsage)))) {
            Response responses = post(RESPONSES_PATH, "{\"model\":\"" + MODEL + "\",\"stream\":true,\"store\":false,"
                    + "\"input\":[{\"role\":\"user\",\"content\":\"Knock, knock\"}]}");
            verify(responses, 200);
            logStore.await(RESPONSES_PATH, 1);
            LimitStats afterResponses = usage();

            Response messages = post(MESSAGES_PATH, MESSAGES_BODY);
            verify(messages, 200);
            logStore.await(MESSAGES_PATH, 1);
            LimitStats afterMessages = usage();

            assertEquals(tokens, afterResponses.getDayTokenStats().getUsed());
            assertEquals(tokens, afterMessages.getDayTokenStats().getUsed() - afterResponses.getDayTokenStats().getUsed());
            assertCost(cost, afterResponses.getDayCostStats().getUsed());
            assertCost(cost, afterMessages.getDayCostStats().getUsed().subtract(afterResponses.getDayCostStats().getUsed()));
            assertEquals(1, afterResponses.getDayRequestStats().getUsed());
            assertEquals(2, afterMessages.getDayRequestStats().getUsed());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testSecondTurnPinnedToFirstUpstream() throws InterruptedException {
        List<RecordedRequest> requests = assertSecondTurn("prefix.body.input[0]");

        assertEquals(requests.get(0).getHeader(Proxy.HEADER_UPSTREAM_KEY), requests.get(1).getHeader(Proxy.HEADER_UPSTREAM_KEY));
        assertEquals("prefix.body.input[0]", requests.get(1).getHeader(Proxy.HEADER_CACHE_BREAKPOINT_PATH));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testMessagesPrefixIsNotCached() throws InterruptedException {
        List<RecordedRequest> requests = assertSecondTurn("prefix.body.messages[0]");

        // nothing stored, so Core names the last breakpoint of the second body
        assertEquals("prefix.body.input[2]", requests.get(1).getHeader(Proxy.HEADER_CACHE_BREAKPOINT_PATH));
    }

    /**
     * The first turn is answered with {@code breakpointPath}; returns the two upstream requests.
     */
    private List<RecordedRequest> assertSecondTurn(String breakpointPath) throws InterruptedException {
        List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
        try (TestWebServer server = origin(request -> {
            requests.add(request);
            return eventStream(responsesStream(PLAIN_USAGE))
                    .setHeader(Proxy.HEADER_CACHE_BREAKPOINT_PATH, breakpointPath)
                    .setHeader(Proxy.HEADER_CACHE_EXPIRE_AT, String.valueOf(Instant.now().plusSeconds(600).getEpochSecond()));
        })) {
            verify(post(MESSAGES_PATH, messagesBody("[{\"role\":\"user\",\"content\":\"Hello\"}]")), 200);
            logStore.await(MESSAGES_PATH, 1);
            Thread.sleep(1000); // the cache entry is written after the answer, off the event loop

            verify(post(MESSAGES_PATH, messagesBody("[{\"role\":\"user\",\"content\":\"Hello\"},"
                    + "{\"role\":\"assistant\",\"content\":\"Hi there\"},{\"role\":\"user\",\"content\":\"Tell me more\"}]")), 200);

            assertEquals(2, requests.size());
            return requests;
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testRetriableAnswerRetriedOnNextUpstream() {
        List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
        try (TestWebServer server = origin(request -> {
            requests.add(request);
            return requests.size() == 1 ? TestWebServer.createResponse(503, "busy") : eventStream(responsesStream(PLAIN_USAGE));
        })) {
            Response response = post(MESSAGES_PATH, MESSAGES_BODY);

            verify(response, 200);
            assertEquals("2", header(response, Proxy.HEADER_UPSTREAM_ATTEMPTS));
            List<String> events = eventNames(response.body());
            assertEquals(1, events.stream().filter("message_start"::equals).count(), events.toString());
            assertEquals(1, events.stream().filter("message_stop"::equals).count(), events.toString());
            assertTrue(response.body().contains("\"text\":\"Hi\""), response.body());

            assertEquals(2, requests.size());
            assertEquals(requests.get(0).getBody().readUtf8(), requests.get(1).getBody().readUtf8());
            assertNotEquals(requests.get(0).getHeader(Proxy.HEADER_UPSTREAM_KEY), requests.get(1).getHeader(Proxy.HEADER_UPSTREAM_KEY));

            logStore.await(MESSAGES_PATH, 1);
            assertEquals(18, usage().getDayTokenStats().getUsed());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    @SneakyThrows
    void testClientGoneUpstreamReadToEnd() {
        // slow enough that the client is gone long before the usage arrives in the last event
        try (TestWebServer server = origin(request -> eventStream(responsesStream(PLAIN_USAGE)).throttleBody(64, 100, TimeUnit.MILLISECONDS));
                Socket socket = new Socket("127.0.0.1", serverPort)) {
            socket.setSoTimeout(10_000);
            byte[] body = MESSAGES_BODY.getBytes(StandardCharsets.UTF_8);
            OutputStream out = socket.getOutputStream();
            out.write(("POST " + MESSAGES_PATH + " HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\napi-key: proxyKey1\r\n"
                    + "Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(body);
            out.flush();

            InputStream in = socket.getInputStream();
            StringBuilder received = new StringBuilder();
            byte[] chunk = new byte[1024];
            while (!received.toString().contains("event: message_start")) {
                int read = in.read(chunk);
                assertTrue(read > 0, "The stream ended before its first event: " + received);
                received.append(new String(chunk, 0, read, StandardCharsets.UTF_8));
            }
            assertFalse(received.toString().contains("message_stop"), received.toString());
            socket.close();

            List<AnalyticsLogContext> rows = logStore.await(MESSAGES_PATH, 1);
            assertEquals(1, rows.size());
            // the usage comes only in the upstream's last event
            assertEquals(18, usage().getDayTokenStats().getUsed());
        }
    }

    private LimitStats usage() {
        Response response = send(HttpMethod.GET, "/v1/deployments/" + MODEL + "/usage", null, null);
        verify(response, 200);
        return ProxyUtil.convertToObject(response.body(), LimitStats.class);
    }

    private Response post(String path, String body) {
        return send(HttpMethod.POST, path, null, body, "content-type", "application/json");
    }

    /**
     * The origin at 4848: {@code POST /openai/v1/responses} answered by {@code responses}, anything else by a 500.
     */
    private static TestWebServer origin(TestWebServer.Handler responses) {
        TestWebServer server = new TestWebServer(4848, request -> TestWebServer.createResponse(500, "unexpected"));
        server.map(HttpMethod.POST, RESPONSES_PATH, responses);
        return server;
    }

    private static String messagesBody(String messages) {
        return "{\"model\":\"" + MODEL + "\",\"max_tokens\":100,\"stream\":true,\"messages\":" + messages + "}";
    }

    /**
     * An upstream Responses stream of the text {@code Hi} whose terminal event reports {@code usage}.
     */
    private static String responsesStream(String usage) {
        return sseEvents(
                "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_1\",\"model\":\"gpt-builtin-priced-upstream\"}}",
                "{\"type\":\"response.content_part.added\",\"output_index\":0,\"content_index\":0,\"part\":{\"type\":\"output_text\"}}",
                "{\"type\":\"response.output_text.delta\",\"output_index\":0,\"content_index\":0,\"delta\":\"Hi\"}",
                "{\"type\":\"response.content_part.done\",\"output_index\":0,\"content_index\":0,\"part\":{\"type\":\"output_text\"}}",
                "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"object\":\"response\",\"status\":\"completed\",\"output\":[],"
                        + "\"usage\":" + usage + "}}");
    }

    private static void assertCost(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> "cost " + actual);
    }

    @SneakyThrows
    private static JsonNode parse(String json) {
        return ProxyUtil.MAPPER.readTree(json);
    }
}
