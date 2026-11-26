package com.epam.aidial.core.server;

import com.epam.aidial.core.server.log.AnalyticsLogContext;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code analytics.errorRows} on: a row for every error answer Core gives itself, and for a stream that breaks.
 * Specs: {@code app-to-model-accounting} "Error rows for answers Core gives itself", "Error rows for a relay that breaks",
 * "No error row for what Monitoring does not log" and "One row per pass, and the passes that keep none"; {@code analytics-log}
 * "Error rows use the fields of any row".
 */
public class ErrorRowsApiTest extends BaseErrorRowsApiTest {

    private static final String MCP_BODY = "{\"payload\": \"foo\"}";

    @Override
    boolean isErrorRowsOn() {
        return true;
    }

    @Test
    void testSettingReachesTheStore() {
        assertTrue(dial.getProxy().getLogStore().isErrorRowsEnabled());
    }

    // "Error rows for answers Core gives itself": the origin's answers that leave no attempt

    @Test
    void testEveryUpstreamBusy() {
        putModel("m-busy");
        serve("m-busy", 429);

        verify(chat("m-busy"), 503);

        assertErrorRow(rows(1).get(0), "m-busy", 503);
    }

    @Test
    void testOrigin502() {
        putModel("m-502");
        serve("m-502", 502);

        verify(chat("m-502"), 502);

        assertErrorRow(rows(1).get(0), "m-502", 502);
    }

    @Test
    void testOrigin503() {
        putModel("m-503");
        serve("m-503", 503);

        // the client gets 502, not the origin's 503
        verify(chat("m-503"), 502);

        assertErrorRow(rows(1).get(0), "m-503", 502);
    }

    @Test
    void testOrigin504() {
        putModel("m-504");
        serve("m-504", 504);

        verify(chat("m-504"), 502);

        assertErrorRow(rows(1).get(0), "m-504", 502);
    }

    @Test
    void testTwoUpstreamsBothBusy() {
        putModelWithTwoUpstreams("m-both-busy");
        serve("m-both-busy", 429, 429);

        verify(chat("m-both-busy"), 503);

        assertEquals(List.of("m-both-busy#0", "m-both-busy#1"), adapterCalls);
        assertErrorRow(rows(1).get(0), "m-both-busy", 503);
    }

    @Test
    void testLastAttemptBusyAnotherFailed() {
        putModelWithTwoUpstreams("m-502-429");
        serve("m-502-429", 502, 429);

        // not every upstream is busy, so Core answers 502 although the last attempt got 429
        verify(chat("m-502-429"), 502);

        assertEquals(List.of("m-502-429#0", "m-502-429#1"), adapterCalls);
        assertErrorRow(rows(1).get(0), "m-502-429", 502);
    }

    @Test
    void testLastAttemptFailedAfterBusy() {
        putModelWithTwoUpstreams("m-429-502");
        serve("m-429-502", 429, 502);

        verify(chat("m-429-502"), 502);

        assertEquals(List.of("m-429-502#0", "m-429-502#1"), adapterCalls);
        assertErrorRow(rows(1).get(0), "m-429-502", 502);
    }

    // "Error rows for answers Core gives itself": no answer from the origin

    @Test
    void testConnectionRefused() {
        // nothing listens on this port
        putModel("m-refused", "http://localhost:4895" + adapterPath("m-refused"), "");

        verify(chat("m-refused"), 502);

        assertErrorRow(rows(1).get(0), "m-refused", 502);
    }

    @Test
    void testHostThatDoesNotResolve() {
        putModel("m-dns", "http://no-such-host.invalid:4895" + adapterPath("m-dns"), "");

        verify(chat("m-dns"), 502);

        assertErrorRow(rows(1).get(0), "m-dns", 502);
    }

    @Test
    void testNoResponseHeaders() {
        putModel("m-silent");
        serve("m-silent", NO_HEADERS);

        verify(chat("m-silent"), 502);

        assertErrorRow(rows(1).get(0), "m-silent", 502);
    }

    @Test
    void testLastAttemptWithoutStatus() {
        putModelWithTwoUpstreams("m-502-silent");
        serve("m-502-silent", 502, NO_HEADERS);

        // the first attempt got a status, the last one got none
        verify(chat("m-502-silent"), 502);

        assertErrorRow(rows(1).get(0), "m-502-silent", 502);
    }

    // "Error rows for answers Core gives itself": Core refuses

    @Test
    @SneakyThrows
    void testRateLimit() {
        // gpt-3-turbo allows 100 tokens a minute: the first answer reports 200 and uses it up
        try (TestWebServer limited = new TestWebServer(4848)) {
            limited.map(HttpMethod.POST, "/chat/completions", 200, """
                    {"id":"c","object":"chat.completion","model":"mock",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"hello"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":100,"completion_tokens":100,"total_tokens":200}}""", "content-type", "application/json");
            String path = "/openai/deployments/gpt-3-turbo/chat/completions";

            verify(send(HttpMethod.POST, path, null, CHAT_BODY, "content-type", "application/json"), 200);
            awaitCondition(() -> send(HttpMethod.POST, path, null, CHAT_BODY, "content-type", "application/json").status() == 429);
        }

        // the first call's row, and the row of the call that Core refused
        List<AnalyticsLogContext> rows = rows(2);
        assertEquals(200, rows.get(0).getResponseStatusCode());
        assertErrorRow(rows.get(1), "gpt-3-turbo", 429);
    }

    @Test
    void testForbiddenDeployment() {
        putModel("m-forbidden", adapterUrl("m-forbidden"), ", \"userRoles\": [\"some-other-role\"]");

        verify(chat("m-forbidden"), 403);

        // Core checks the role before it puts the deployment on the pass, so the row has none, as for a 404
        AnalyticsLogContext row = rows(1).get(0);
        assertEquals(403, row.getResponseStatusCode());
        assertNull(row.getDeploymentName());
    }

    @Test
    void testUnknownDeployment() {
        verify(chat("no-such-model"), 404);

        AnalyticsLogContext row = rows(1).get(0);
        assertEquals(404, row.getResponseStatusCode());
        assertNull(row.getDeploymentName());
    }

    @Test
    void testWrongContentType() {
        putModel("m-content-type");

        verify(send(HttpMethod.POST, adapterPath("m-content-type"), null, CHAT_BODY, "content-type", "text/plain"), 415);

        assertEquals(415, rows(1).get(0).getResponseStatusCode());
    }

    @Test
    void testBodyThatIsNotJson() {
        putModel("m-not-json");

        verify(send(HttpMethod.POST, adapterPath("m-not-json"), null, "{not json", "content-type", "application/json"), 400);

        assertEquals(400, rows(1).get(0).getResponseStatusCode());
    }

    @Test
    void testUnknownUpstreamId() {
        putModelWithTwoUpstreams("m-upstream-id");

        verify(send(HttpMethod.POST, adapterPath("m-upstream-id"), null, CHAT_BODY, "content-type", "application/json",
                Proxy.HEADER_UPSTREAM_ID, "no-such-upstream"), 400);

        assertErrorRow(rows(1).get(0), "m-upstream-id", 400);
    }

    // "Error rows for answers Core gives itself": the other surfaces and the decided request

    @Test
    void testApplicationExhausted() {
        putApplication("app-busy", adapterUrl("app-busy"));
        serve("app-busy", 429);

        verify(chat("app-busy"), 503);

        assertErrorRow(rows(1).get(0), "app-busy", 503);
    }

    @Test
    void testV1ChatCompletionsExhausted() {
        putModel("m-v1");
        serve("m-v1", 429);

        verify(send(HttpMethod.POST, "/openai/v1/chat/completions", null,
                "{\"model\":\"m-v1\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}", "content-type", "application/json"), 503);

        assertErrorRow(rows(1).get(0), "m-v1", 503);
    }

    @Test
    void testResponsesExhausted() {
        putModel("m-responses", adapterUrl("m-responses"),
                ", \"responsesEndpoint\": \"http://localhost:" + ADAPTER_PORT + "/openai/v1/responses\"");
        adapter.map(HttpMethod.POST, "/openai/v1/responses", reply(429));

        verify(send(HttpMethod.POST, "/openai/v1/responses", null, "{\"model\":\"m-responses\",\"input\":\"hi\"}",
                "content-type", "application/json"), 503);

        assertErrorRow(rows(1).get(0), "m-responses", 503);
    }

    @Test
    void testMessagesExhausted() {
        verify(adminPut("/v1/models/platform/m-messages", "{\"type\": \"chat\", \"interfaces\": "
                + "{\"anthropicMessages\": {\"base_url\": \"http://localhost:" + ADAPTER_PORT + "\"}}}"), 200);
        adapter.map(HttpMethod.POST, "/anthropic/v1/messages", reply(429));

        verify(send(HttpMethod.POST, "/anthropic/v1/messages", null,
                "{\"model\":\"m-messages\",\"max_tokens\":16,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                "content-type", "application/json"), 503);

        assertErrorRow(rows(1).get(0), "m-messages", 503);
    }

    @Test
    @DialConfigLocation("dial-config/router-decision.json")
    void testDecidedRequestFails() {
        AtomicInteger candidateCalls = new AtomicInteger();
        AtomicInteger decisions = new AtomicInteger();
        try (TestWebServer router = new TestWebServer(4848, request -> {
            if (request.getPath().endsWith("/decide")) {
                decisions.incrementAndGet();
                return TestWebServer.createResponse(200, "{\"candidates\": [\"gpt-4\"]}", "content-type", "application/json");
            }
            candidateCalls.incrementAndGet();
            return reply(502);
        })) {
            Response response = send(HttpMethod.POST, "/openai/deployments/r-two/chat/completions", null, CHAT_BODY,
                    "content-type", "application/json");

            assertTrue(response.status() >= 400, response.body());
            assertTrue(candidateCalls.get() >= 1);
        }

        // the router row (which has a decision) as with the setting off, and one row for the candidate
        sleep();
        List<AnalyticsLogContext> candidateRows = analyticsRows.stream().filter(row -> row.getDecision() == null).toList();
        assertEquals(1, candidateRows.size(), () -> "decide calls " + decisions.get() + ", candidate calls " + candidateCalls.get() + ", rows "
                + analyticsRows.stream().map(row -> row.getDeploymentName() + "/" + row.getResponseStatusCode() + "/"
                + (row.getDecision() != null)).toList());
        assertEquals("gpt-4", candidateRows.get(0).getDeploymentName());
        assertTrue(analyticsRows.stream().anyMatch(row -> row.getDecision() != null));
    }

    // "Error rows for answers Core gives itself": feature calls, the MCP proxy and routes

    @Test
    void testFeatureCallConnectionError() {
        // nothing listens on port 7001, where the tokenize endpoint of this model is
        verify(send(HttpMethod.POST, "/v1/deployments/chat-gpt-35-turbo/tokenize", null, "{}",
                "content-type", "application/json"), 502);

        assertEquals(502, rows(1).get(0).getResponseStatusCode());
    }

    @Test
    void testFeatureCallUnknownDeployment() {
        verify(send(HttpMethod.POST, "/v1/deployments/no-such-model/tokenize", null, "{}",
                "content-type", "application/json"), 404);

        AnalyticsLogContext row = rows(1).get(0);
        assertEquals(404, row.getResponseStatusCode());
        assertNull(row.getDeploymentName());
    }

    @Test
    void testFeatureWithoutEndpointWritesOneRow() {
        // the rate feature needs no endpoint: Core answers 200 itself and saves its row, as it does with the setting off
        putModel("m-no-rate");

        verify(send(HttpMethod.POST, "/v1/m-no-rate/rate", null, "{\"rate\": true, \"responseId\": \"r1\"}",
                "content-type", "application/json"), 200);

        AnalyticsLogContext row = rows(1).get(0);
        assertEquals(200, row.getResponseStatusCode());
        assertEquals("m-no-rate", row.getDeploymentName());
    }

    @Test
    void testFeatureCallRelayedErrorHasOneRow() {
        try (TestWebServer origin = new TestWebServer(7001)) {
            origin.map(HttpMethod.POST, "/upstream/v1/deployments/gpt-35-turbo/tokenizer", 500, "{}",
                    "content-type", "application/json");

            verify(send(HttpMethod.POST, "/v1/deployments/chat-gpt-35-turbo/tokenize", null, "{}",
                    "content-type", "application/json"), 500);
        }

        // the origin's answer is relayed and has its row: the setting adds none
        assertEquals(500, rows(1).get(0).getResponseStatusCode());
    }

    @Test
    void testMcpUnknownToolset() {
        verify(send(HttpMethod.POST, "/v1/toolset/unknown/mcp", null, MCP_BODY), 404);

        assertEquals(404, rows(1).get(0).getResponseStatusCode());
    }

    @Test
    void testMcpServerUnreachable() {
        // nothing listens on port 9876, where the toolset `git` is
        Response response = send(HttpMethod.POST, "/v1/toolset/git/mcp", null, MCP_BODY);

        assertTrue(response.status() == 502 || response.status() == 503, response.body());
        assertEquals(response.status(), rows(1).get(0).getResponseStatusCode());
    }

    @Test
    void testMcpRelayedErrorHasOneRow() {
        try (TestWebServer origin = new TestWebServer(9876, request ->
                TestWebServer.createResponse(500, "{\"error\":\"x\"}", "content-type", "application/json"))) {
            verify(send(HttpMethod.POST, "/v1/toolset/git/mcp", null, MCP_BODY), 500);
        }

        // Core relays the origin's answer through respond(int, Buffer) and saves the row after it: a hook there would write two
        assertEquals(500, rows(1).get(0).getResponseStatusCode());
    }

    @Test
    void testRouteRefusedByRole() {
        verify(send(HttpMethod.POST, "/v1/vector_store/1", null, "x", "api-key", "vstore_user_key"), 403);

        assertEquals(403, rows(1).get(0).getResponseStatusCode());
    }

    @Test
    void testRouteNotFound() {
        verify(send(HttpMethod.POST, "/unexpected", null, "x", "api-key", "vstore_user_key"), 404);

        assertEquals(404, rows(1).get(0).getResponseStatusCode());
    }

    @Test
    void testRouteUpstreamBusy() {
        try (TestWebServer origin = new TestWebServer(9876, request -> new MockResponse().setResponseCode(429))) {
            verify(send(HttpMethod.POST, "/tasks/task1", null, "x"), 503);
        }

        assertEquals(503, rows(1).get(0).getResponseStatusCode());
    }

    @Test
    void testRouteRelayedErrorHasOneRow() {
        try (TestWebServer origin = new TestWebServer(9876, request -> new MockResponse().setResponseCode(500))) {
            verify(send(HttpMethod.POST, "/tasks/task1", null, "x"), 500);
        }

        assertEquals(500, rows(1).get(0).getResponseStatusCode());
    }

    // "Error rows use the fields of any row"

    @Test
    void testErrorRowFields() {
        putModel("m-fields");
        serve("m-fields", 503);

        Response response = chat("m-fields");

        verify(response, 502);
        AnalyticsLogContext row = rows(1).get(0);
        String traceId = response.headers().get("X-DIAL-TRACE-ID");
        assertNotNull(traceId);
        assertEquals(traceId, row.getTraceId());
        assertNotNull(row.getRequestBody());
        assertTrue(row.getRequestBody().toString().contains("\"messages\""));
        // Core's own answer is not kept, so the row's response body is empty
        assertNull(row.getResponseBody());
        // the exhausted route has no current upstream
        assertNull(row.getUpstreamEndpoint());
        assertNull(row.getTokenUsage());
        assertEquals("POST", row.getRequestMethod());
    }

    @Test
    void testSuccessUnchanged() {
        putModel("m-ok");
        serve("m-ok", 200);

        verify(chat("m-ok"), 200);

        AnalyticsLogContext row = rows(1).get(0);
        assertEquals(200, row.getResponseStatusCode());
        assertEquals(20, row.getTokenUsage().getTotalTokens());
    }

    // "No error row for what Monitoring does not log"

    @Test
    void testUnknownKeyWritesNoRow() {
        putModel("m-key");

        verify(send(HttpMethod.POST, adapterPath("m-key"), null, CHAT_BODY, "api-key", "wrong", "content-type", "application/json"), 401);

        rows(0);
    }

    @Test
    void testBodyTooLargeWritesNoRow() {
        putModel("m-large");
        String large = "{\"messages\":[{\"role\":\"user\",\"content\":\"" + "x".repeat(30000) + "\"}]}";

        verify(send(HttpMethod.POST, adapterPath("m-large"), null, large, "content-type", "application/json"), 413);

        rows(0);
    }

    @Test
    void testRelayedOriginError() {
        putModel("m-500");
        serve("m-500", 500);

        verify(chat("m-500"), 500);

        assertErrorRow(rows(1).get(0), "m-500", 500);
    }

    @Test
    @DialConfigLocation("dial-config/budget-spend-caps.json")
    void testMessagesNoBudget() {
        // the user's only budget is for another model, so Core refuses claude-spend with the Anthropic 402 itself
        verify(send(HttpMethod.PUT, "/v1/policies/platform/projA-a1", null, """
                {"priority":10,"kind":"budget","quantity":"cost","object":["claude-other"],"per":["subject"],"cost":{"day":10}}""",
                "authorization", "admin"), 200);
        verify(send(HttpMethod.PUT, "/v1/policy_assignments/platform/projA/a1", null, """
                {"policy":"projA-a1","costObject":"projA",
                 "subject":[{"source":"title","function":"EQUAL","targets":["Manager"]}]}""",
                "authorization", "admin"), 200);

        verify(send(HttpMethod.POST, "/anthropic/v1/messages", null,
                "{\"model\":\"claude-spend\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                "content-type", "application/json", "authorization", "user@projA", "anthropic-version", "2023-06-01"), 402);

        assertErrorRow(rows(1).get(0), "claude-spend", 402);
    }

    // "One row per pass, and the passes that keep none"

    @Test
    void testRetryThatSucceeds() {
        putModelWithTwoUpstreams("m-retry-ok");
        serve("m-retry-ok", 502, 200);

        verify(chat("m-retry-ok"), 200);

        assertEquals(List.of("m-retry-ok#0", "m-retry-ok#1"), adapterCalls);
        assertEquals(200, rows(1).get(0).getResponseStatusCode());
    }

    @Test
    void testInterceptorFailureWritesNoRow() {
        putModel("m-interceptor", adapterUrl("m-interceptor"), ", \"interceptors\": [\"interceptor1\"]");
        serve("m-interceptor", 200);

        // nothing listens at the interceptor's endpoint: Core answers 502 itself, through the interceptor's respond helper
        Response response = chat("m-interceptor");

        assertEquals(502, response.status(), response.body());
        rows(0);
    }

    @Test
    @DialConfigLocation("dial-config/router-decision.json")
    void testRouterDecisionFails() {
        // r-down decides, and nothing listens at its base url: no candidate is chosen
        Response response = send(HttpMethod.POST, "/openai/deployments/r-down/chat/completions", null, CHAT_BODY,
                "content-type", "application/json");

        assertTrue(response.status() >= 400, response.body());
        rows(0);
    }

    @Test
    @DialConfigLocation("dial-config/router-decision.json")
    void testProxyModeRouterFails() {
        // r2 does not decide: it is the origin of its own pass, and a failed pass of it gets a row like any other
        try (TestWebServer router = new TestWebServer(4848, request -> reply(502))) {
            verify(send(HttpMethod.POST, "/openai/deployments/r2/chat/completions", null, CHAT_BODY,
                    "content-type", "application/json"), 502);
        }

        assertErrorRow(rows(1).get(0), "r2", 502);
    }

    // "Error rows for a relay that breaks"

    @Test
    @SneakyThrows
    void testOriginClosesAfterHeaders() {
        try (RawOrigin origin = new RawOrigin(RAW_PORT, RawOrigin.closesAfterHeaders())) {
            putModel("m-closed", rawUrl("m-closed"), "");

            // the client gets no complete answer: the connection ends without one
            ignoringFailure(() -> chat("m-closed"));

            AnalyticsLogContext row = rows(1).get(0);
            assertEquals("m-closed", row.getDeploymentName());
            assertEquals(200, row.getResponseStatusCode());
            assertNull(row.getTokenUsage());
        }
    }

    @Test
    @SneakyThrows
    void testOriginBreaksMidStream() {
        try (RawOrigin origin = new RawOrigin(RAW_PORT, RawOrigin.breaksMidStream())) {
            putModel("m-broken", rawUrl("m-broken"), "");

            String received = readUntilTheEnd(streamed("m-broken"));

            assertTrue(!received.contains("[DONE]"), received);
            AnalyticsLogContext row = rows(1).get(0);
            assertEquals("m-broken", row.getDeploymentName());
            assertEquals(200, row.getResponseStatusCode());
        }
    }

    @Test
    @SneakyThrows
    void testClientLeavesModel() {
        try (RawOrigin origin = new RawOrigin(RAW_PORT, RawOrigin.slowStream())) {
            putModel("m-leave", rawUrl("m-leave"), "");

            leaveAfterTheFirstBytes(streamed("m-leave"));

            // Core reads the origin's stream to its end: one row, the one written with the setting off
            AnalyticsLogContext row = rows(1).get(0);
            assertEquals("m-leave", row.getDeploymentName());
            assertEquals(200, row.getResponseStatusCode());
            assertEquals(20, row.getTokenUsage().getTotalTokens());
        }
    }

    @Test
    @SneakyThrows
    void testApplicationOriginBreaks() {
        try (RawOrigin origin = new RawOrigin(RAW_PORT, RawOrigin.breaksMidStream())) {
            putApplication("app-broken", rawUrl("app-broken"));

            readUntilTheEnd(streamed("app-broken"));

            AnalyticsLogContext row = rows(1).get(0);
            assertEquals("app-broken", row.getDeploymentName());
            assertEquals(200, row.getResponseStatusCode());
        }
    }

    @Test
    @SneakyThrows
    void testClientLeavesApplication() {
        try (RawOrigin origin = new RawOrigin(RAW_PORT, RawOrigin.slowStream())) {
            putApplication("app-leave", rawUrl("app-leave"));

            leaveAfterTheFirstBytes(streamed("app-leave"));

            AnalyticsLogContext row = rows(1).get(0);
            assertEquals("app-leave", row.getDeploymentName());
            assertEquals(200, row.getResponseStatusCode());
        }
    }

    @Test
    @SneakyThrows
    void testBrokenStreamOfBackgroundRequestBeforeItsJobIsRecorded() {
        try (RawOrigin origin = new RawOrigin(RAW_PORT, RawOrigin.breaksMidStream())) {
            putModel("m-background", adapterUrl("m-background"),
                    ", \"responsesEndpoint\": \"http://localhost:" + RAW_PORT + "/openai/v1/responses\"");

            ignoringFailure(() -> send(HttpMethod.POST, "/openai/v1/responses", null,
                    "{\"model\":\"m-background\",\"input\":\"hi\",\"stream\":true,\"background\":true}", "content-type", "application/json"));

            // no event carried a response id, so no job was recorded and no poller writes a row: this pass writes it
            assertErrorRow(rows(1).get(0), "m-background", 200);
        }
    }

    @SneakyThrows
    private static void sleep() {
        Thread.sleep(1000);
    }

    private static void ignoringFailure(Callable<?> call) {
        try {
            call.call();
        } catch (Exception e) {
            // the cut connection is what the test is about
        }
    }

    @SneakyThrows
    private static String readUntilTheEnd(HttpResponse<InputStream> response) {
        try (InputStream body = response.body()) {
            return new String(body.readAllBytes());
        } catch (IOException e) {
            // the connection was cut: what arrived before is not needed here
            return "";
        }
    }

    @SneakyThrows
    private static void leaveAfterTheFirstBytes(HttpResponse<InputStream> response) {
        InputStream body = response.body();
        assertTrue(body.read(new byte[16]) > 0);
        // closing the body closes the connection: the client is gone
        body.close();
    }
}
