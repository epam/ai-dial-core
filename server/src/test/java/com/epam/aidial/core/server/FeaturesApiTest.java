package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import okhttp3.Headers;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

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
        testUpstreamEndpoint(inboundPath, upstream);
    }

    @Test
    void testRateEndpointApplication() {
        String inboundPath = "/v1/app/rate";
        String upstream = "http://localhost:7001/openai/deployments/10k/rate_response";
        testUpstreamEndpoint(inboundPath, upstream);
    }

    @Test
    void testRateEndpointAssistant() {
        String inboundPath = "/v1/search-assistant/rate";
        String upstream = "http://localhost:7001/openai/deployments/search_assistant/rate_response";
        testUpstreamEndpoint(inboundPath, upstream);
    }

    @Test
    void testRateEndpointAssistantDefaultResponse() {
        // The rate endpoint is unset. Checking the default empty response.
        String inboundPath = "/v1/assistant/rate";
        Response response = send(HttpMethod.POST, inboundPath);
        verify(response, 200, "");
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

    @SneakyThrows
    void testUpstreamEndpoint(String inboundPath, String upstream, HttpMethod method) {
        Headers requestExtraHeaders = new Headers.Builder().add("foo", "bar").build();
        String[] requestExtraHeadersArray = convertHeadersToFlatArray(requestExtraHeaders);

        URI uri = URI.create(upstream);
        try (TestWebServer server = new TestWebServer(uri.getPort())) {
            server.map(method, uri.getPath(), request -> {
                Headers responseHeaders = filterHeaders(request.getHeaders(), requestExtraHeaders);
                return TestWebServer.createResponse(200, "PONG", convertHeadersToFlatArray(responseHeaders));
            });

            Response response = send(method, inboundPath, null, "", requestExtraHeadersArray);
            verify(response, 200, "PONG", requestExtraHeadersArray);
        }
    }
}
