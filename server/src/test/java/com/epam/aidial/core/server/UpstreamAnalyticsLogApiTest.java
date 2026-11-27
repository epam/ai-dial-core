package com.epam.aidial.core.server;

import com.epam.aidial.core.server.log.AnalyticsLogContext;
import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The upstream named in the analytics row (spec: analytics-log, "Upstream in the analytics row" and "Upstream of
 * translator and feature passes").
 */
class UpstreamAnalyticsLogApiTest extends ResourceBaseTest {

    private static final String CONFIG = "dial-config/upstream-analytics.json";
    private static final String COMPLETION = "{\"id\":\"c1\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,"
            + "\"message\":{\"role\":\"assistant\",\"content\":\"Who's there?\"},\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":3,\"total_tokens\":5}}";
    private static final String MESSAGES_PATH = "/anthropic/v1/messages";
    private static final String MESSAGES_ANSWER = "{\"id\":\"msg_01\",\"type\":\"message\",\"role\":\"assistant\","
            + "\"model\":\"m-translated\",\"content\":[{\"type\":\"text\",\"text\":\"Who's there?\"}],"
            + "\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":10,\"output_tokens\":8}}";

    @Test
    @DialConfigLocation(CONFIG)
    void testUpstreamsToldApartOnlyById() {
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/openai/deployments/m-regions/chat/completions", request -> {
                String extraData = request.getHeader("X-UPSTREAM-EXTRA-DATA");
                return extraData != null && extraData.contains("r2")
                        ? TestWebServer.createResponse(200, COMPLETION, "Content-Type", "application/json")
                        : TestWebServer.createResponse(503, "{\"error\":{\"message\":\"busy\"}}", "Content-Type", "application/json");
            });

            verify(postChat("m-regions"), 200);

            AnalyticsLogContext row = rowOf("m-regions");
            assertEquals("b", row.getUpstreamId());
            assertNull(row.getUpstreamEndpoint());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testUpstreamWithEndpointAndNoId() {
        try (TestWebServer server = completions("m-endpoint-no-id")) {
            verify(postChat("m-endpoint-no-id"), 200);

            AnalyticsLogContext row = rowOf("m-endpoint-no-id");
            assertEquals("http://localhost:4848/up-a", row.getUpstreamEndpoint());
            assertNull(row.getUpstreamId());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testUpstreamWithoutId() {
        try (TestWebServer server = completions("m-no-id")) {
            server.map(HttpMethod.POST, "/openai/deployments/m-blank-id/chat/completions", 200, COMPLETION,
                    "Content-Type", "application/json");

            verify(postChat("m-no-id"), 200);
            verify(postChat("m-blank-id"), 200);

            assertNull(rowOf("m-no-id").getUpstreamId());
            assertNull(rowOf("m-blank-id").getUpstreamId());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testModelWithoutUpstreams() {
        try (TestWebServer server = completions("m-plain")) {
            verify(postChat("m-plain"), 200);

            AnalyticsLogContext row = rowOf("m-plain");
            assertEquals("http://localhost:4848/openai/deployments/m-plain/chat/completions", row.getUpstreamEndpoint());
            assertNull(row.getUpstreamId());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testPassSentToTranslator() {
        try (TestWebServer server = new TestWebServer(4848)) {
            // the translator answers itself here; its call back to Core would be a pass, and a row, of its own
            server.map(HttpMethod.POST, MESSAGES_PATH, 200, MESSAGES_ANSWER, "Content-Type", "application/json");

            verify(send(HttpMethod.POST, MESSAGES_PATH, null,
                    "{\"model\":\"m-translated\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":\"Knock, knock\"}]}",
                    "content-type", "application/json", "api-key", "proxyKey1", "anthropic-version", "2023-06-01"), 200);

            assertEquals("a", rowOf("m-translated").getUpstreamId());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testFeatureCall() {
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/m-features/tokenize", 200, "{\"outputs\":[]}", "Content-Type", "application/json");

            verify(send(HttpMethod.POST, "/v1/deployments/m-features/tokenize", null, "{\"inputs\":[]}",
                    "content-type", "application/json", "api-key", "proxyKey1"), 200);

            AnalyticsLogContext row = rowOf("m-features");
            assertNull(row.getUpstreamId());
            assertNull(row.getUpstreamEndpoint());
        }
    }

    private static TestWebServer completions(String model) {
        TestWebServer server = new TestWebServer(4848);
        server.map(HttpMethod.POST, "/openai/deployments/" + model + "/chat/completions", 200, COMPLETION,
                "Content-Type", "application/json");
        return server;
    }

    private Response postChat(String model) {
        return send(HttpMethod.POST, "/openai/deployments/" + model + "/chat/completions", null,
                "{\"messages\":[{\"role\":\"user\",\"content\":\"Knock, knock\"}]}",
                "content-type", "application/json", "api-key", "proxyKey1");
    }

    private AnalyticsLogContext rowOf(String deployment) {
        awaitCondition(() -> findRow(deployment) != null);
        return findRow(deployment);
    }

    private AnalyticsLogContext findRow(String deployment) {
        return List.copyOf(analyticsRows).stream()
                .filter(row -> deployment.equals(row.getDeploymentName()))
                .findFirst()
                .orElse(null);
    }
}
