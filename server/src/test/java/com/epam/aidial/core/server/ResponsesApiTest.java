package com.epam.aidial.core.server;

import com.epam.aidial.core.server.data.LimitStats;
import com.epam.aidial.core.server.util.EncryptedContentAffinityUtil;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.http.HttpMethod;
import okhttp3.mockwebserver.MockResponse;
import org.apache.hc.client5.http.classic.methods.HttpUriRequest;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertLinesMatch;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ResponsesApiTest extends ResourceBaseTest {

    @Test
    public void testResponsesApi() throws IOException, InterruptedException {
        String responseBody = getResponseBody();
        try (TestWebServer server = new TestWebServer(4848)) {
            try (CloseableHttpClient client = HttpClientBuilder.create().disableAutomaticRetries().build()) {
                TestWebServer.Handler handler = request -> {
                    MockResponse response = new MockResponse();
                    response.setResponseCode(200);
                    response.setChunkedBody(responseBody, 200);
                    return response;
                };
                server.map(HttpMethod.POST, "/openai/v1/responses", handler);
                HttpUriRequest httpUriRequest = createHttpUriRequest();
                String result = client.execute(
                        httpUriRequest,
                        response -> new String(response.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8));
                assertLinesMatch(responseBody.lines(), result.lines());
                // wait for core to save usage
                Thread.sleep(1000);

                Response response = send(HttpMethod.GET, "/v1/deployments/gpt-3-turbo/limits", null, null);

                verify(response, 200);
                LimitStats limitStats = ProxyUtil.convertToObject(response.body(), LimitStats.class);
                assertNotNull(limitStats);
                assertEquals(18, limitStats.getMinuteTokenStats().getUsed());
                assertEquals(18, limitStats.getDayTokenStats().getUsed());
                assertEquals(18, limitStats.getWeekTokenStats().getUsed());
                assertEquals(18, limitStats.getMonthTokenStats().getUsed());
            }
        }
    }

    @Test
    public void testAutoCachingPinsSecondTurnToSameUpstream() throws IOException, InterruptedException {
        AtomicReference<String> firstUpstreamKey = new AtomicReference<>();
        AtomicReference<String> secondUpstreamKey = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848); CloseableHttpClient client = HttpClientBuilder.create().disableAutomaticRetries().build()) {
            server.map(HttpMethod.POST, "/openai/v1/responses", request -> {
                String upstreamKey = request.getHeader("X-UPSTREAM-KEY");
                if (firstUpstreamKey.get() == null) {
                    firstUpstreamKey.set(upstreamKey);
                } else {
                    secondUpstreamKey.set(upstreamKey);
                }
                long expireAt = Instant.now().plusSeconds(600).getEpochSecond();
                return TestWebServer.createResponse(200,
                        "{\"id\":\"resp_1\",\"object\":\"response\",\"output\":[],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}",
                        "Content-Type", "application/json",
                        "X-DIAL-CACHE-BREAKPOINT-PATH", "prefix.body.input[0]",
                        "X-DIAL-CACHE-EXPIRE-AT", String.valueOf(expireAt));
            });

            String firstTurn = "{\"model\":\"responses-cache\",\"stream\":false,\"store\":false,"
                    + "\"input\":[{\"role\":\"user\",\"content\":\"Hello\"}]}";
            Response first = post(client, firstTurn);
            assertEquals(200, first.status());
            Thread.sleep(1000); // wait for the async Redis update after the first turn

            String secondTurn = "{\"model\":\"responses-cache\",\"stream\":false,\"store\":false,"
                    + "\"input\":[{\"role\":\"user\",\"content\":\"Hello\"},{\"role\":\"assistant\",\"content\":\"Hi there\"}]}";
            Response second = post(client, secondTurn);
            assertEquals(200, second.status());

            assertNotNull(firstUpstreamKey.get());
            assertEquals(firstUpstreamKey.get(), secondUpstreamKey.get());
        }
    }

    @Test
    public void testEncryptedContentAffinityPinsSecondTurnToSameUpstream() throws IOException {
        AtomicReference<String> firstUpstreamKey = new AtomicReference<>();
        AtomicReference<String> secondUpstreamKey = new AtomicReference<>();
        AtomicReference<String> secondRequestBody = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848); CloseableHttpClient client = HttpClientBuilder.create().disableAutomaticRetries().build()) {
            server.map(HttpMethod.POST, "/openai/v1/responses", request -> {
                String upstreamKey = request.getHeader("X-UPSTREAM-KEY");
                if (firstUpstreamKey.get() == null) {
                    firstUpstreamKey.set(upstreamKey);
                } else {
                    secondUpstreamKey.set(upstreamKey);
                    secondRequestBody.set(request.getBody().readUtf8());
                }
                return TestWebServer.createResponse(200,
                        "{\"id\":\"resp_1\",\"object\":\"response\",\"output\":["
                                + "{\"type\":\"reasoning\",\"id\":\"rs_1\",\"encrypted_content\":\"cipher-xyz\"}],"
                                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}",
                        "Content-Type", "application/json");
            });

            String firstTurn = "{\"model\":\"responses-cache\",\"stream\":false,\"store\":false,"
                    + "\"input\":[{\"role\":\"user\",\"content\":\"Hello\"}]}";
            Response first = post(client, firstTurn);
            assertEquals(200, first.status());

            JsonNode firstResponse = ProxyUtil.MAPPER.readTree(first.body());
            JsonNode reasoningItem = firstResponse.path("output").get(0);
            String wrappedId = reasoningItem.path("id").asText();
            String wrappedContent = reasoningItem.path("encrypted_content").asText();
            assertTrue(wrappedId.startsWith("dialenc_"), "id should be wrapped: " + wrappedId);
            assertTrue(wrappedContent.startsWith("dialenc:"), "encrypted_content should be wrapped: " + wrappedContent);

            String secondTurn = "{\"model\":\"responses-cache\",\"stream\":false,\"store\":false,"
                    + "\"input\":[{\"role\":\"user\",\"content\":\"Hello\"},"
                    + "{\"type\":\"reasoning\",\"id\":\"" + wrappedId + "\",\"encrypted_content\":\"" + wrappedContent + "\"},"
                    + "{\"role\":\"user\",\"content\":\"Continue\"}]}";
            Response second = post(client, secondTurn);
            assertEquals(200, second.status());

            assertNotNull(firstUpstreamKey.get());
            assertNotNull(secondUpstreamKey.get());
            assertEquals(firstUpstreamKey.get(), secondUpstreamKey.get());

            // the item forwarded upstream must be unwrapped back to its provider-native shape
            JsonNode forwarded = ProxyUtil.MAPPER.readTree(secondRequestBody.get());
            JsonNode forwardedReasoning = forwarded.path("input").get(1);
            assertEquals("rs_1", forwardedReasoning.path("id").asText());
            assertEquals("cipher-xyz", forwardedReasoning.path("encrypted_content").asText());
        }
    }

    @Test
    public void testEncryptedContentPassThroughForNoConfiguredUpstreamDeployment() throws IOException {
        try (TestWebServer server = new TestWebServer(4848); CloseableHttpClient client = HttpClientBuilder.create().disableAutomaticRetries().build()) {
            server.map(HttpMethod.POST, "/openai/v1/responses", request -> TestWebServer.createResponse(200,
                    "{\"id\":\"resp_1\",\"object\":\"response\",\"output\":["
                            + "{\"type\":\"reasoning\",\"id\":\"rs_1\",\"encrypted_content\":\"cipher-xyz\"}],"
                            + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}",
                    "Content-Type", "application/json"));

            String requestBody = "{\"model\":\"model-iface-only\",\"stream\":false,\"store\":false,"
                    + "\"input\":[{\"role\":\"user\",\"content\":\"Hello\"}]}";
            Response response = post(client, requestBody);
            assertEquals(200, response.status());

            JsonNode result = ProxyUtil.MAPPER.readTree(response.body());
            JsonNode reasoningItem = result.path("output").get(0);
            assertEquals("rs_1", reasoningItem.path("id").asText());
            assertEquals("cipher-xyz", reasoningItem.path("encrypted_content").asText());
        }
    }

    @Test
    public void testEncryptedContentAffinityWrapsAndUnwrapsForSingleUpstreamDeployment() throws IOException {
        AtomicReference<String> secondRequestBody = new AtomicReference<>();
        AtomicInteger requestCount = new AtomicInteger();
        try (TestWebServer server = new TestWebServer(4848); CloseableHttpClient client = HttpClientBuilder.create().disableAutomaticRetries().build()) {
            server.map(HttpMethod.POST, "/openai/v1/responses", request -> {
                if (requestCount.getAndIncrement() == 1) {
                    secondRequestBody.set(request.getBody().readUtf8());
                }
                return TestWebServer.createResponse(200,
                        "{\"id\":\"resp_1\",\"object\":\"response\",\"output\":["
                                + "{\"type\":\"reasoning\",\"id\":\"rs_1\",\"encrypted_content\":\"cipher-xyz\"}],"
                                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}",
                        "Content-Type", "application/json");
            });

            String firstTurn = "{\"model\":\"gpt-3-turbo\",\"stream\":false,\"store\":false,"
                    + "\"input\":[{\"role\":\"user\",\"content\":\"Hello\"}]}";
            Response first = post(client, firstTurn);
            assertEquals(200, first.status());

            JsonNode firstResponse = ProxyUtil.MAPPER.readTree(first.body());
            JsonNode reasoningItem = firstResponse.path("output").get(0);
            String wrappedId = reasoningItem.path("id").asText();
            String wrappedContent = reasoningItem.path("encrypted_content").asText();
            assertTrue(wrappedId.startsWith("dialenc_"), "id should be wrapped even with a single upstream: " + wrappedId);
            assertTrue(wrappedContent.startsWith("dialenc:"),
                    "encrypted_content should be wrapped even with a single upstream: " + wrappedContent);

            String secondTurn = "{\"model\":\"gpt-3-turbo\",\"stream\":false,\"store\":false,"
                    + "\"input\":[{\"role\":\"user\",\"content\":\"Hello\"},"
                    + "{\"type\":\"reasoning\",\"id\":\"" + wrappedId + "\",\"encrypted_content\":\"" + wrappedContent + "\"},"
                    + "{\"role\":\"user\",\"content\":\"Continue\"}]}";
            Response second = post(client, secondTurn);
            assertEquals(200, second.status());

            // the item forwarded upstream must be unwrapped back to its provider-native shape
            JsonNode forwarded = ProxyUtil.MAPPER.readTree(secondRequestBody.get());
            JsonNode forwardedReasoning = forwarded.path("input").get(1);
            assertEquals("rs_1", forwardedReasoning.path("id").asText());
            assertEquals("cipher-xyz", forwardedReasoning.path("encrypted_content").asText());
        }
    }

    @Test
    public void testConflictingEncryptedContentAffinityRejected() throws IOException {
        try (CloseableHttpClient client = HttpClientBuilder.create().disableAutomaticRetries().build()) {
            ObjectNode itemA = ProxyUtil.MAPPER.createObjectNode();
            itemA.put("type", "reasoning");
            itemA.put("id", "rs_1");
            itemA.put("encrypted_content", "cipher-1");
            EncryptedContentAffinityUtil.wrapOutputItem(itemA, "up-a");

            ObjectNode itemB = ProxyUtil.MAPPER.createObjectNode();
            itemB.put("type", "reasoning");
            itemB.put("id", "rs_2");
            itemB.put("encrypted_content", "cipher-2");
            EncryptedContentAffinityUtil.wrapOutputItem(itemB, "up-b");

            String requestBody = "{\"model\":\"responses-cache\",\"stream\":false,\"store\":false,"
                    + "\"input\":[" + itemA + "," + itemB + "]}";
            Response response = post(client, requestBody);

            assertEquals(400, response.status());
            JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
            assertEquals("conflicting_encrypted_content_affinity", body.path("error").path("code").asText());
        }
    }

    @Test
    public void testUnavailableUpstreamEncryptedContentAffinityRejected() throws IOException {
        try (CloseableHttpClient client = HttpClientBuilder.create().disableAutomaticRetries().build()) {
            ObjectNode item = ProxyUtil.MAPPER.createObjectNode();
            item.put("type", "reasoning");
            item.put("id", "rs_1");
            item.put("encrypted_content", "cipher-1");
            EncryptedContentAffinityUtil.wrapOutputItem(item, "up-nonexistent");

            String requestBody = "{\"model\":\"responses-cache\",\"stream\":false,\"store\":false,"
                    + "\"input\":[" + item + "]}";
            Response response = post(client, requestBody);

            assertEquals(409, response.status());
            JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
            assertEquals("encrypted_content_upstream_unavailable", body.path("error").path("code").asText());
        }
    }

    private Response post(CloseableHttpClient client, String body) throws IOException {
        String uri = "http://127.0.0.1:" + serverPort + "/openai/v1/responses";
        HttpUriRequestBase request = new HttpUriRequestBase(HttpMethod.POST.name(), URI.create(uri));
        request.setHeader("Authorization", "Bearer proxyKey1");
        request.setHeader("content-type", "application/json");
        request.setEntity(new StringEntity(body, StandardCharsets.UTF_8));
        return client.execute(request, ResourceBaseTest::toResponse);
    }

    private HttpUriRequest createHttpUriRequest() {
        String uri = "http://127.0.0.1:" + serverPort + "/openai/v1/responses";
        String requestBody = """
                {
                   "model": "gpt-3-turbo",
                   "stream": true,
                   "input": "Knock, knock"
                 }
                """;
        HttpUriRequest httpUriRequest = new HttpUriRequestBase(HttpMethod.POST.name(), URI.create(uri));
        httpUriRequest.setHeader("Authorization", "Bearer proxyKey1");
        httpUriRequest.setHeader("content-type", "application/json");
        httpUriRequest.setEntity(new StringEntity(requestBody));
        return httpUriRequest;
    }

    private static String getResponseBody() throws IOException {
        try (InputStream stream = ResponsesApiTest.class.getClassLoader().getResourceAsStream("responses-sse.txt")) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
