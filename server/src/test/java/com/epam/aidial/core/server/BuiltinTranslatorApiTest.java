package com.epam.aidial.core.server;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.server.data.LimitStats;
import com.epam.aidial.core.server.service.AdminManagedFieldsWriteMode;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.util.EtagHeader;
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
import java.util.concurrent.atomic.AtomicInteger;
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

    /**
     * Every request the origin receives, mapped or not.
     */
    private final AtomicInteger originRequests = new AtomicInteger();

    @Test
    public void testStreamingIsTranslatedInOnePass() throws IOException, InterruptedException {
        String sse = readFixture("responses-sse.txt");
        AtomicReference<RecordedRequest> captured = new AtomicReference<>();
        try (TestWebServer server = origin(request -> {
            captured.set(request);
            return eventStream(sse);
        }); CloseableHttpClient client = newClient()) {

            Response response = post(client, MESSAGES_PATH, requestBody(true), "api-key", "proxyKey1",
                    "anthropic-version", "2023-06-01");

            assertEquals(200, response.status());
            assertEquals(List.of("message_start", "ping", "content_block_start", "content_block_delta",
                    "content_block_delta", "content_block_delta", "content_block_delta", "content_block_stop",
                    "message_delta", "message_stop"), eventNames(response.body()));
            assertEquals("Who’s there?", deltaText(response.body()));
            // the client gets a DIAL id for the message, never the provider's own response id
            assertFalse(response.body().contains("resp_075fcfcf"), response.body());

            assertEquals(1, originRequests.get());
            RecordedRequest upstream = captured.get();
            assertEquals(RESPONSES_PATH, upstream.getPath());
            JsonNode body = ProxyUtil.MAPPER.readTree(upstream.getBody().readUtf8());
            assertEquals("gpt-5-upstream", body.path("model").asText());
            assertFalse(body.path("store").asBoolean(true));
            assertEquals(ProxyUtil.MAPPER.readTree("[\"reasoning.encrypted_content\"]"), body.path("include"));
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
    public void testBlocksOnceRefusedAreTranslated() throws IOException {
        String sse = readFixture("responses-sse.txt");
        AtomicReference<RecordedRequest> captured = new AtomicReference<>();
        try (TestWebServer server = origin(request -> {
            captured.set(request);
            return eventStream(sse);
        }); CloseableHttpClient client = newClient()) {
            String body = """
                    {"model":"gpt-builtin","max_tokens":100,"stream":true,"messages":[
                      {"role":"user","content":"hi"},
                      {"role":"assistant","content":[{"type":"compaction","content":"Earlier: we fixed X."}]},
                      {"role":"system","content":[{"type":"text","text":"a"},
                        {"type":"mid_conv_system","content":[{"type":"text","text":"b"}]}]},
                      {"role":"user","content":"continue"}]}""";

            Response response = post(client, MESSAGES_PATH, body, "api-key", "proxyKey1");

            assertEquals(200, response.status(), response.body());
            JsonNode input = ProxyUtil.MAPPER.readTree(captured.get().getBody().readUtf8()).path("input");
            assertEquals(ProxyUtil.MAPPER.readTree("""
                    [{"type":"message","role":"user","content":[{"type":"input_text","text":"hi"}]},
                     {"type":"message","role":"assistant","content":"Earlier: we fixed X."},
                     {"type":"message","role":"system","content":[{"type":"input_text","text":"a"},{"type":"input_text","text":"b"}]},
                     {"type":"message","role":"user","content":[{"type":"input_text","text":"continue"}]}]"""), input);
        }
    }

    @Test
    public void testToolsAreRefused() throws IOException {
        try (TestWebServer server = refusingOrigin(); CloseableHttpClient client = newClient()) {
            String body = requestBody(true).replace("\"messages\"",
                    "\"tools\":[{\"name\":\"get_weather\",\"input_schema\":{\"type\":\"object\"}}],\"messages\"");

            Response response = post(client, MESSAGES_PATH, body, "api-key", "proxyKey1");

            assertEquals(400, response.status());
            assertTrue(response.body().contains("Tools"), response.body());
            assertEquals(0, originRequests.get());
        }
    }

    @Test
    public void testMalformedConversationIsRefused() throws IOException {
        try (TestWebServer server = refusingOrigin(); CloseableHttpClient client = newClient()) {
            String unknownRole = "{\"model\":\"gpt-builtin\",\"max_tokens\":100,\"stream\":true,\"messages\":[{\"role\":\"tool\",\"content\":\"x\"}]}";
            Response response = post(client, MESSAGES_PATH, unknownRole, "api-key", "proxyKey1");

            assertEquals(400, response.status());
            assertEquals("Unknown message role: 'tool'", response.body());

            String notAnArray = "{\"model\":\"gpt-builtin\",\"max_tokens\":100,\"stream\":true,\"messages\":\"hi\"}";
            response = post(client, MESSAGES_PATH, notAnArray, "api-key", "proxyKey1");

            assertEquals(400, response.status());
            assertTrue(response.body().contains("'messages'"), response.body());
            assertEquals(0, originRequests.get());
        }
    }

    @Test
    public void testLimitIsCheckedBeforeTheUpstream() throws IOException, InterruptedException {
        String sse = readFixture("responses-sse.txt");
        try (TestWebServer server = origin(request -> eventStream(sse)); CloseableHttpClient client = newClient()) {
            String body = requestBody(true).replace("gpt-builtin", "gpt-builtin-limited");

            Response first = post(client, MESSAGES_PATH, body, "api-key", "proxyKey1");
            assertEquals(200, first.status(), first.body());
            Thread.sleep(1000); // wait for core to save usage: 18 tokens, over the role's limit of 10

            Response second = post(client, MESSAGES_PATH, body, "api-key", "proxyKey1");
            assertEquals(429, second.status(), second.body());
            assertEquals(1, originRequests.get());
        }
    }

    @Test
    public void testApplicationIsNotServedByBuiltinTranslator() throws IOException {
        // registered here rather than in the shared test config, whose application listings other tests compare whole
        putPublicApplication("app-builtin", ProxyUtil.convertToObject("""
                {"interfaces": {
                  "openaiResponses": {"base_url": "http://localhost:4848"},
                  "anthropicMessages": {"mode": "translator", "translator": "builtinResponses"}}}""", Application.class));
        try (TestWebServer server = refusingOrigin(); CloseableHttpClient client = newClient()) {
            String body = requestBody(true).replace("gpt-builtin", "applications/public/app-builtin");

            Response response = post(client, MESSAGES_PATH, body, "api-key", "proxyKey1");

            assertEquals(503, response.status(), response.body());
            assertEquals(0, originRequests.get());
        }
    }

    @Test
    public void testPerRequestKeyClosedOnTheTranslatedInterfaceIsRefused() {
        Application app = new Application();
        app.setEndpoint("http://localhost:4848/app");
        putPublicApplication("builtin-caller", app);
        AtomicReference<Response> callBack = new AtomicReference<>();
        try (TestWebServer server = refusingOrigin()) {
            // the application calls Core back with the per-request key it received
            server.map(HttpMethod.POST, "/app", request -> {
                callBack.set(send(HttpMethod.POST, MESSAGES_PATH, null, requestBody(true).replace("gpt-builtin", "gpt-builtin-closed"),
                        "api-key", request.getHeader(Proxy.HEADER_API_KEY), "content-type", Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON));
                return TestWebServer.createResponse(200, """
                        {"id":"id1","object":"chat.completion","created":1,"model":"builtin-caller",
                         "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"ok"}}],
                         "usage":{"completion_tokens":1,"prompt_tokens":1,"total_tokens":2}}""");
            });

            Response response = send(HttpMethod.POST, "/openai/deployments/applications/public/builtin-caller/chat/completions", null,
                    "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}", "content-type", Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON);

            verify(response, 200);
            assertEquals(403, callBack.get().status(), callBack.get().body());
            assertEquals(0, originRequests.get());
        }
    }

    @Test
    public void testNonStreamingIsRefused() throws IOException {
        try (TestWebServer server = refusingOrigin(); CloseableHttpClient client = newClient()) {

            Response response = post(client, MESSAGES_PATH, requestBody(false), "api-key", "proxyKey1");

            assertEquals(400, response.status());
            assertTrue(response.body().contains("non-streaming"), response.body());
            assertEquals(0, originRequests.get());
        }
    }

    @Test
    public void testUntranslatableContentIsRefused() throws IOException {
        try (TestWebServer server = refusingOrigin(); CloseableHttpClient client = newClient()) {
            String body = "{\"model\":\"gpt-builtin\",\"max_tokens\":100,\"stream\":true,\"messages\":[{\"role\":\"user\","
                    + "\"content\":[{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"AA==\"}}]}]}";

            Response response = post(client, MESSAGES_PATH, body, "api-key", "proxyKey1");

            assertEquals(400, response.status());
            assertTrue(response.body().contains("'image'"), response.body());
            assertEquals(0, originRequests.get());
        }
    }

    @Test
    public void testStreamWithoutTerminalEventEndsInError() throws IOException {
        String sse = readFixture("responses-sse.txt");
        String truncated = sse.substring(0, sse.indexOf("event: response.completed"));
        try (TestWebServer server = origin(request -> eventStream(truncated)); CloseableHttpClient client = newClient()) {
            Response response = post(client, MESSAGES_PATH, requestBody(true), "api-key", "proxyKey1");

            assertEquals(200, response.status());
            List<String> events = eventNames(response.body());
            assertEquals("error", events.get(events.size() - 1), response.body());
            assertFalse(events.contains("message_stop"), response.body());
        }
    }

    @Test
    public void testDeploymentWithInterceptorsIsRefused() throws IOException {
        try (TestWebServer server = refusingOrigin(); CloseableHttpClient client = newClient()) {
            String body = requestBody(true).replace("gpt-builtin", "gpt-builtin-intercepted");

            Response response = post(client, MESSAGES_PATH, body, "api-key", "proxyKey1");

            assertEquals(503, response.status());
            assertTrue(response.body().contains("interceptors"), response.body());
            assertEquals(0, originRequests.get());
        }
    }

    @Test
    public void testConsentOfTheTranslatedInterfaceIsRequired() throws IOException {
        // a translator service's pass back to Core is held to the openaiResponses consent, so this pass is too
        try (TestWebServer server = refusingOrigin(); CloseableHttpClient client = newClient()) {
            String body = requestBody(true).replace("gpt-builtin", "gpt-builtin-consent");

            Response response = post(client, MESSAGES_PATH, body, "Authorization", "user");

            assertEquals(403, response.status(), response.body());
            assertEquals(0, originRequests.get());
        }
    }

    @Test
    public void testCountTokensIsNotServedYet() throws IOException {
        try (CloseableHttpClient client = newClient()) {
            Response response = post(client, COUNT_TOKENS_PATH, requestBody(false), "api-key", "proxyKey1");

            assertEquals(503, response.status());
        }
    }

    private void putPublicApplication(String name, Application application) {
        dial.getProxy().getApplicationService().putApplication(ResourceDescriptorFactory.fromPublicUrl("applications/public/" + name),
                EtagHeader.ANY, null, application, false, AdminManagedFieldsWriteMode.INHERIT_ONLY);
    }

    /**
     * The origin at 4848: {@code POST /openai/v1/responses} answered by {@code responses}, anything else by a
     * 500, and every request counted in {@link #originRequests}.
     */
    private TestWebServer origin(TestWebServer.Handler responses) {
        TestWebServer server = new TestWebServer(4848, request -> {
            originRequests.incrementAndGet();
            return TestWebServer.createResponse(500, "unexpected");
        });
        server.map(HttpMethod.POST, RESPONSES_PATH, request -> {
            originRequests.incrementAndGet();
            return responses.map(request);
        });
        return server;
    }

    private TestWebServer refusingOrigin() {
        return origin(request -> TestWebServer.createResponse(500, "unexpected"));
    }

    private static MockResponse eventStream(String sse) {
        MockResponse response = new MockResponse();
        response.setResponseCode(200);
        response.setHeader("Content-Type", "text/event-stream");
        response.setChunkedBody(sse, 200);
        return response;
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
