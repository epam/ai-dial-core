package com.epam.aidial.core.server;

import com.epam.aidial.core.server.data.LimitStats;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code POST /anthropic/v1/messages} for a model whose {@code anthropicMessages} interface is served by a
 * builtin translator: one pass, sent to the model's own {@code openaiResponses} interface.
 */
public class BuiltinTranslatorApiTest extends ResourceBaseTest {

    private static final String MESSAGES_PATH = "/anthropic/v1/messages";
    private static final String COUNT_TOKENS_PATH = "/anthropic/v1/messages/count_tokens";
    private static final String RESPONSES_PATH = "/openai/v1/responses";

    private static String requestBody(boolean stream) {
        return "{\"model\":\"gpt-builtin\",\"max_tokens\":100,\"stream\":" + stream
                + ",\"system\":\"Be brief\",\"messages\":[{\"role\":\"user\",\"content\":\"Knock, knock\"}]}";
    }

    @Test
    public void testStreamingIsTranslatedInOnePass() throws IOException, InterruptedException {
        String sse = readFixture("responses-sse.txt");
        AtomicReference<RecordedRequest> captured = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848); CloseableHttpClient client = newClient()) {
            server.map(HttpMethod.POST, RESPONSES_PATH, request -> {
                captured.set(request);
                MockResponse response = new MockResponse();
                response.setResponseCode(200);
                response.setHeader("Content-Type", "text/event-stream");
                response.setChunkedBody(sse, 200);
                return response;
            });

            Response response = post(client, MESSAGES_PATH, requestBody(true), "api-key", "proxyKey1",
                    "anthropic-version", "2023-06-01");

            assertEquals(200, response.status());
            assertEquals(List.of("message_start", "ping", "content_block_start", "content_block_delta",
                    "content_block_delta", "content_block_delta", "content_block_delta", "content_block_stop",
                    "message_delta", "message_stop"), eventNames(response.body()));
            assertEquals("Who’s there?", deltaText(response.body()));
            // the client gets a DIAL id for the message, never the provider's own response id
            assertFalse(response.body().contains("resp_075fcfcf"), response.body());

            RecordedRequest upstream = captured.get();
            assertEquals(RESPONSES_PATH, upstream.getPath());
            JsonNode body = ProxyUtil.MAPPER.readTree(upstream.getBody().readUtf8());
            assertEquals("gpt-5-upstream", body.path("model").asText());
            assertFalse(body.path("store").asBoolean(true));
            assertTrue(body.path("stream").asBoolean());
            assertEquals("Be brief", body.path("instructions").asText());
            assertEquals("Knock, knock", body.path("input").path(0).path("content").path(0).path("text").asText());
            assertEquals(100, body.path("max_output_tokens").asInt());

            assertNotNull(upstream.getHeader("api-key"));
            assertNotEquals("proxyKey1", upstream.getHeader("api-key"));
            assertEquals("http://responses-upstream/v1/responses", upstream.getHeader("X-UPSTREAM-ENDPOINT"));
            assertEquals("upstreamKey", upstream.getHeader("X-UPSTREAM-KEY"));
            assertNull(upstream.getHeader("anthropic-version"));
            assertNull(upstream.getHeader("X-DIAL-DEPLOYMENT-ID"));

            Thread.sleep(1000); // wait for core to save usage
            // the Responses usage (10 in, 8 out) reaches the limits once, through the Anthropic usage
            assertUsed("gpt-builtin", 18);
        }
    }

    @Test
    public void testNonStreamingIsRefused() throws IOException {
        try (TestWebServer server = new TestWebServer(4848); CloseableHttpClient client = newClient()) {
            server.map(HttpMethod.POST, RESPONSES_PATH, request -> TestWebServer.createResponse(500, "unexpected"));

            Response response = post(client, MESSAGES_PATH, requestBody(false), "api-key", "proxyKey1");

            assertEquals(400, response.status());
            assertTrue(response.body().contains("non-streaming"), response.body());
        }
    }

    @Test
    public void testUntranslatableContentIsRefused() throws IOException {
        try (TestWebServer server = new TestWebServer(4848); CloseableHttpClient client = newClient()) {
            server.map(HttpMethod.POST, RESPONSES_PATH, request -> TestWebServer.createResponse(500, "unexpected"));
            String body = "{\"model\":\"gpt-builtin\",\"max_tokens\":100,\"stream\":true,\"messages\":[{\"role\":\"user\","
                    + "\"content\":[{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"AA==\"}}]}]}";

            Response response = post(client, MESSAGES_PATH, body, "api-key", "proxyKey1");

            assertEquals(400, response.status());
            assertTrue(response.body().contains("'image'"), response.body());
        }
    }

    @Test
    public void testStreamWithoutTerminalEventEndsInError() throws IOException {
        String sse = readFixture("responses-sse.txt");
        String truncated = sse.substring(0, sse.indexOf("event: response.completed"));
        try (TestWebServer server = new TestWebServer(4848); CloseableHttpClient client = newClient()) {
            server.map(HttpMethod.POST, RESPONSES_PATH, request -> {
                MockResponse response = new MockResponse();
                response.setResponseCode(200);
                response.setHeader("Content-Type", "text/event-stream");
                response.setChunkedBody(truncated, 200);
                return response;
            });

            Response response = post(client, MESSAGES_PATH, requestBody(true), "api-key", "proxyKey1");

            assertEquals(200, response.status());
            List<String> events = eventNames(response.body());
            assertEquals("error", events.get(events.size() - 1), response.body());
            assertFalse(events.contains("message_stop"), response.body());
        }
    }

    @Test
    public void testDeploymentWithInterceptorsIsRefused() throws IOException {
        try (TestWebServer server = new TestWebServer(4848); CloseableHttpClient client = newClient()) {
            server.map(HttpMethod.POST, RESPONSES_PATH, request -> TestWebServer.createResponse(500, "unexpected"));
            String body = requestBody(true).replace("gpt-builtin", "gpt-builtin-intercepted");

            Response response = post(client, MESSAGES_PATH, body, "api-key", "proxyKey1");

            assertEquals(503, response.status());
            assertTrue(response.body().contains("interceptors"), response.body());
        }
    }

    @Test
    public void testCountTokensIsNotServedYet() throws IOException {
        try (CloseableHttpClient client = newClient()) {
            Response response = post(client, COUNT_TOKENS_PATH, requestBody(false), "api-key", "proxyKey1");

            assertEquals(503, response.status());
        }
    }

    @SneakyThrows
    private static String deltaText(String sse) {
        StringBuilder text = new StringBuilder();
        for (String line : sse.lines().filter(line -> line.startsWith("data: ")).toList()) {
            JsonNode data = ProxyUtil.MAPPER.readTree(line.substring("data: ".length()));
            text.append(data.path("delta").path("text").asText(""));
        }
        return text.toString();
    }

    private static List<String> eventNames(String sse) {
        return sse.lines()
                .filter(line -> line.startsWith("event: "))
                .map(line -> line.substring("event: ".length()))
                .toList();
    }

    private void assertUsed(String deployment, long expected) {
        Response response = send(HttpMethod.GET, "/v1/deployments/" + deployment + "/limits", null, null);
        verify(response, 200);
        LimitStats stats = ProxyUtil.convertToObject(response.body(), LimitStats.class);
        assertNotNull(stats);
        assertEquals(expected, stats.getDayTokenStats().getUsed());
    }

    private Response post(CloseableHttpClient client, String path, String body, String... headers) throws IOException {
        HttpUriRequestBase request = new HttpUriRequestBase(HttpMethod.POST.name(), URI.create("http://127.0.0.1:" + serverPort + path));
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
        try (InputStream stream = BuiltinTranslatorApiTest.class.getClassLoader().getResourceAsStream(name)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
