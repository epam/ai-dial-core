package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import okhttp3.Headers;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class FeaturesApiTest extends ResourceBaseTest {

    private static String[] convertHeadersToFlatArray(Headers headers) {
        return StreamSupport.stream(headers.spliterator(), false)
                .flatMap(header -> Stream.of(header.getFirst(), header.getSecond()))
                .toArray(String[]::new);
    }

    private static Headers filterHeaders(Headers headers, Headers mask) {
        Headers.Builder filteredHeaders = new Headers.Builder();
        for (Map.Entry<String, List<String>> entry : headers.toMultimap().entrySet()) {
            String key = entry.getKey();
            if (mask.names().contains(key.toLowerCase())) {
                for (String value : entry.getValue()) {
                    filteredHeaders.add(key, value);
                }
            }
        }
        return filteredHeaders.build();
    }

    @Test
    void testRateEndpointModel() {
        String inboundPath = "/v1/chat-gpt-35-turbo/rate";
        String upstream = "http://localhost:7001/upstream/v1/deployments/gpt-35-turbo/rate_response";
        String body = """
                {
                  "rate": true,
                  "responseId": "LLM response ID",
                  "comment": "user may put an optional comment here on LLM response"
                }
                """;
        testUpstreamEndpoint(inboundPath, upstream, HttpMethod.POST, body);
    }

    @Test
    void testConfigurationEndpointModel() {
        String inboundPath = "/v1/deployments/chat-gpt-35-turbo/configuration";
        String upstream = "http://localhost:7001/upstream/v1/deployments/gpt-35-turbo/model_config";
        testUpstreamEndpoint(inboundPath, upstream, HttpMethod.GET);
    }

    @Test
    void testRateEndpointApplication() {
        String inboundPath = "/v1/app/rate";
        String upstream = "http://localhost:7001/openai/deployments/10k/rate_response";
        String body = """
                {
                  "rate": true,
                  "responseId": "LLM response ID",
                  "comment": "user may put an optional comment here on LLM response"
                }
                """;
        testUpstreamEndpoint(inboundPath, upstream, HttpMethod.POST, body);
    }

    @Test
    void testTokenizeEndpoint() {
        String inboundPath = "/v1/deployments/chat-gpt-35-turbo/tokenize";
        String upstream = "http://localhost:7001/upstream/v1/deployments/gpt-35-turbo/tokenizer";
        testUpstreamEndpoint(inboundPath, upstream);
    }

    @Test
    void testTruncatePromptEndpoint() {
        String inboundPath = "/v1/deployments/chat-gpt-35-turbo/truncate_prompt";
        String upstream = "http://localhost:7001/upstream/v1/deployments/gpt-35-turbo/trim_history";
        testUpstreamEndpoint(inboundPath, upstream);
    }

    @Test
    void testConfigurationEndpointApplication() {
        String inboundPath = "/v1/deployments/app/configuration";
        String upstream = "http://localhost:7001/openai/deployments/10k/config";
        testUpstreamEndpoint(inboundPath, upstream, HttpMethod.GET);
    }

    void testUpstreamEndpoint(String inboundPath, String upstream) {
        testUpstreamEndpoint(inboundPath, upstream, HttpMethod.POST);
    }

    void testUpstreamEndpoint(String inboundPath, String upstream, HttpMethod method) {
        testUpstreamEndpoint(inboundPath, upstream, method, null);
    }

    @SneakyThrows
    void testUpstreamEndpoint(String inboundPath, String upstream, HttpMethod method, String body) {
        Headers requestExtraHeaders = new Headers.Builder().add("foo", "bar").build();
        String[] requestExtraHeadersArray = convertHeadersToFlatArray(requestExtraHeaders);

        URI uri = URI.create(upstream);
        try (TestWebServer server = new TestWebServer(uri.getPort())) {
            server.map(method, uri.getPath(), request -> {
                Headers responseHeaders = filterHeaders(request.getHeaders(), requestExtraHeaders);
                String path = request.getPath();
                if (path.endsWith("model_config")) {
                    assertEquals("http://localhost:7001", request.getHeader(Proxy.HEADER_UPSTREAM_ENDPOINT));
                }
                if (path.endsWith("rate_response")) {
                    return handleRateResponse(request, responseHeaders);
                } else {
                    return TestWebServer.createResponse(200, "PONG", convertHeadersToFlatArray(responseHeaders));
                }
            });

            Response response = send(method, inboundPath, null, body == null ? "" : body, requestExtraHeadersArray);
            verify(response, 200, "PONG", requestExtraHeadersArray);
        }
    }

    @SneakyThrows
    private MockResponse handleRateResponse(RecordedRequest request, Headers responseHeaders) {
        JsonNode body = ProxyUtil.MAPPER.readTree(request.getBody().inputStream());
        int status = 200;
        String path = request.getPath();
        if (path.contains("app")) {
            if (body.has("comment")) {
                status = 400;
            }
        } else if (path.contains("gpt-35-turbo")) {
            if (!body.has("comment")) {
                status = 400;
            }
        }
        return TestWebServer.createResponse(status, "PONG", convertHeadersToFlatArray(responseHeaders));
    }
}
