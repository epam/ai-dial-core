package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import lombok.SneakyThrows;
import okhttp3.mockwebserver.MockResponse;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * request-id: with {@code tracing.errorRequestId} on, an error answer ends its message with the DIAL request id and
 * carries {@code X-Request-ID}. The trace id is read from {@code X-DIAL-TRACE-ID}, so both settings are on.
 */
class ErrorRequestIdApiTest extends ResourceBaseTest {

    private static final String CONFIG = "dial-config/error-request-id.json";
    private static final String ROUTER_CONFIG = "dial-config/router-decision.json";
    private static final String CHAT_PATH = "/openai/deployments/chat/chat/completions";
    private static final String CHAT_BODY = "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    private static final String MESSAGES_PATH = "/anthropic/v1/messages";
    private static final String COUNT_TOKENS_PATH = "/anthropic/v1/messages/count_tokens";
    private static final String RESPONSES_PATH = "/openai/v1/responses";

    @Override
    protected JsonObject additionalSettingsOverrides() {
        return new JsonObject().put("tracing", new JsonObject()
                .put("errorRequestId", true)
                .put("responseTraceHeaders", true));
    }

    @Test
    void testOwnPlainTextError() {
        Response response = call(MESSAGES_PATH, messagesBody("missing", false), "api-key", "proxyKey1");

        assertEquals(404, response.status());
        assertEquals("Unknown deployment: missing" + suffix(response), response.body());
        assertContentLength(response);
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testOwnJsonError() {
        Response response = call("/openai/deployments/closed/chat/completions", CHAT_BODY, "api-key", "proxyKey1");

        assertEquals(502, response.status());
        assertEquals("{\"error\":{\"message\":\"Failed to connect to upstream server" + suffix(response) + "\","
                + "\"display_message\":\"Failed to connect to upstream server\",\"code\":\"BAD_GATEWAY\"}}", response.body());
    }

    @Test
    void testErrorBeforeAuthentication() {
        Response response = call("/openai/deployments/m/chat/completions", CHAT_BODY);

        assertEquals(401, response.status());
        assertEquals("At least API-KEY or Authorization header must be provided" + suffix(response), response.body());
    }

    @Test
    void testBodySizeLimit() {
        Response response = call("/v1/conversations/" + bucket + "/folder/big", HttpMethod.PUT, "1".repeat(64 * 1024 * 1024 + 1),
                "api-key", "proxyKey1");

        assertEquals(413, response.status());
        assertEquals("Request body is too large" + suffix(response), response.body());
    }

    @Test
    void testOwnErrorRequestIdHeader() {
        Response response = call(MESSAGES_PATH, messagesBody("missing", false), "api-key", "proxyKey1");

        assertEquals(404, response.status());
        assertEquals(traceId(response), header(response, "X-Request-ID"));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testRelayedPlainText() {
        Response response = relayChat(403, "Access denied\n", "Content-Type", "text/plain; charset=utf-8");

        assertEquals(403, response.status());
        assertEquals("Access denied" + suffix(response), response.body());
        assertContentLength(response);
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testRelayedJsonMembersKept() {
        Response response = relayChat(400, "{\"error\":{\"message\":\"bad\",\"code\":\"invalid_value\"},\"param\":\"temperature\"}",
                "Content-Type", "application/json");

        assertEquals(400, response.status());
        assertEquals("{\"error\":{\"message\":\"bad" + suffix(response) + "\",\"code\":\"invalid_value\"},\"param\":\"temperature\"}",
                response.body());
        assertContentLength(response);
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testHtmlUnchanged() {
        Response response = relayChat(400, "<html><body>bad</body></html>", "Content-Type", "text/html");

        assertEquals(400, response.status());
        assertEquals("<html><body>bad</body></html>", response.body());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testJsonWithoutMessageUnchanged() {
        Response response = relayChat(422, "{\"detail\":\"bad input\"}", "Content-Type", "application/json");

        assertEquals(422, response.status());
        assertEquals("{\"detail\":\"bad input\"}", response.body());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testUndecodedEncodingUnchanged() {
        Response response = relayChat(400, "bad", "Content-Type", "text/plain", "Content-Encoding", "x-custom");

        assertEquals(400, response.status());
        assertEquals("bad", response.body());
        assertEquals("x-custom", header(response, "Content-Encoding"));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testEventStreamErrorUnchanged() {
        String events = "data: {\"error\":{\"message\":\"bad\"}}\n\n";
        Response response;
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, CHAT_PATH, 400, events, "Content-Type", "text/event-stream");
            response = call(CHAT_PATH, "{\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                    "api-key", "proxyKey1");
        }

        assertEquals(400, response.status());
        assertEquals(events, response.body());
        assertNull(header(response, "X-Request-ID"));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testOriginRequestIdKept() {
        Response response = relayChat(400, "{\"error\":{\"message\":\"bad\"}}", "Content-Type", "application/json", "X-Request-ID", "up-1");

        assertEquals(400, response.status());
        assertEquals("up-1", header(response, "X-Request-ID"));
        assertEquals("{\"error\":{\"message\":\"bad" + suffix(response) + "\"}}", response.body());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testEmptyBodyHeaderOnly() {
        Response response = relayChat(403, "");

        assertEquals(403, response.status());
        assertEquals("", response.body());
        assertEquals(traceId(response), header(response, "X-Request-ID"));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testEmbeddingsErrorUnchanged() {
        String error = "{\"error\":{\"message\":\"bad\"}}";
        Response response;
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/openai/deployments/embed/embeddings", 400, error, "Content-Type", "application/json");
            response = call("/openai/deployments/embed/embeddings", "{\"input\":\"hi\"}", "api-key", "proxyKey1");
        }

        assertEquals(400, response.status());
        assertEquals(error, response.body());
        assertEquals("chunked", header(response, "Transfer-Encoding"));
        assertNull(header(response, "X-Request-ID"));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testAliasErrorOnce() {
        Response response;
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, CHAT_PATH, 400, "{\"error\":{\"message\":\"bad\"}}", "Content-Type", "application/json");
            response = call("/openai/deployments/a/chat/completions", CHAT_BODY, "api-key", "proxyKey1");
        }

        assertEquals(400, response.status());
        assertEquals("{\"error\":{\"message\":\"bad" + suffix(response) + "\"}}", response.body());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testStreamingMessagesError() {
        String error = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                + "\"message\":\"prompt is too long: 250000 tokens > 200000 maximum\"}}";
        Response response;
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, MESSAGES_PATH, 400, error, "Content-Type", "application/json");
            response = call(MESSAGES_PATH, messagesBody("multi", true), "api-key", "proxyKey1");
        }

        assertEquals(400, response.status());
        assertEquals("{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                + "\"message\":\"prompt is too long: 250000 tokens > 200000 maximum" + suffix(response) + "\"}}", response.body());
        assertContentLength(response);
        assertNull(header(response, "Transfer-Encoding"));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testSuccessUntouched() {
        String message = "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],"
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
        Response response;
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, MESSAGES_PATH, 200, message, "Content-Type", "application/json");
            response = call(MESSAGES_PATH, messagesBody("multi", false), "api-key", "proxyKey1");
        }

        assertEquals(200, response.status());
        assertEquals(message, response.body());
        assertNull(header(response, "X-Request-ID"));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testCountTokensError() {
        Response response;
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, COUNT_TOKENS_PATH, 400,
                    "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"bad\"}}",
                    "Content-Type", "application/json");
            response = call(COUNT_TOKENS_PATH, messagesBody("multi", false), "api-key", "proxyKey1");
        }

        assertEquals(400, response.status());
        assertEquals("{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"bad" + suffix(response) + "\"}}",
                response.body());
        assertContentLength(response);
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testResponsesNonStreamingError() {
        Response response = relayResponses("{\"model\":\"multi\",\"store\":false,\"input\":\"hi\"}");

        assertEquals(400, response.status());
        assertEquals("{\"error\":{\"message\":\"bad" + suffix(response) + "\"}}", response.body());
        assertContentLength(response);
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testResponsesStreamingError() {
        Response response = relayResponses("{\"model\":\"multi\",\"store\":false,\"stream\":true,\"input\":\"hi\"}");

        assertEquals(400, response.status());
        assertEquals("{\"error\":{\"message\":\"bad" + suffix(response) + "\"}}", response.body());
        assertContentLength(response);
        assertNull(header(response, "Transfer-Encoding"));
    }

    @Test
    @DialConfigLocation(ROUTER_CONFIG)
    void testRouterErrorAnswer() {
        Response response;
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/decide", 400, "{\"error\": {\"message\": \"state either tier or target\"}}",
                    "content-type", "application/json");
            response = call("/openai/deployments/r1/chat/completions", CHAT_BODY, "api-key", "proxyKey1");
        }

        assertEquals(400, response.status());
        assertEquals("{\"error\":{\"message\":\"state either tier or target" + suffix(response) + "\"}}", response.body());
    }

    @Test
    void testMcpErrorAnswer() {
        Response response;
        try (TestWebServer ignored = new TestWebServer(9876, request -> new MockResponse().setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,\"message\":\"Invalid request\"}}"))) {
            response = call("/v1/toolset/git/mcp", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\"}", "api-key", "proxyKey1");
        }

        assertEquals(400, response.status());
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,\"message\":\"Invalid request" + suffix(response) + "\"}}",
                response.body());
    }

    private Response relayChat(int status, String body, String... headers) {
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, CHAT_PATH, status, body, headers);
            return call(CHAT_PATH, CHAT_BODY, "api-key", "proxyKey1");
        }
    }

    private Response relayResponses(String body) {
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, RESPONSES_PATH, 400, "{\"error\":{\"message\":\"bad\"}}", "Content-Type", "application/json");
            return call(RESPONSES_PATH, body, "api-key", "proxyKey1");
        }
    }

    private static String messagesBody(String model, boolean stream) {
        return "{\"model\":\"" + model + "\",\"max_tokens\":100,\"stream\":" + stream
                + ",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    }

    private Response call(String path, String body, String... headers) {
        return call(path, HttpMethod.POST, body, headers);
    }

    /**
     * Unlike {@code send}, adds no {@code api-key}. {@code Accept-Encoding: identity} keeps Core from compressing the
     * answer, so that its {@code Content-Length} can be checked.
     */
    @SneakyThrows
    private Response call(String path, HttpMethod method, String body, String... headers) {
        HttpUriRequestBase request = new HttpUriRequestBase(method.name(), URI.create("http://127.0.0.1:" + serverPort + path));
        request.setHeader("Content-Type", "application/json");
        request.setHeader("Accept-Encoding", "identity");
        for (int i = 0; i < headers.length; i += 2) {
            request.setHeader(headers[i], headers[i + 1]);
        }
        request.setEntity(new StringEntity(body, StandardCharsets.UTF_8));
        // no decompression: the client would reject the unknown Content-Encoding that one test relays
        try (CloseableHttpClient plainClient = HttpClientBuilder.create().disableAutomaticRetries().disableContentCompression().build()) {
            return plainClient.execute(request, ResourceBaseTest::toResponse);
        }
    }

    private static String suffix(Response response) {
        return " (DIAL request id: " + traceId(response) + ")";
    }

    private static String traceId(Response response) {
        String traceId = header(response, Proxy.HEADER_DIAL_TRACE_ID);
        assertNotNull(traceId, response.headers()::toString);
        return traceId;
    }

    private static void assertContentLength(Response response) {
        assertEquals(String.valueOf(response.body().getBytes(StandardCharsets.UTF_8).length), header(response, "Content-Length"));
    }

    private static String header(Response response, String name) {
        return response.headers().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }
}
