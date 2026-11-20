package com.epam.aidial.core.server;

import com.epam.aidial.core.server.log.AnalyticsLogContext;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@code analytics.errorRows} absent, which is off: Core behaves as it did before the setting existed. Specs:
 * {@code analytics-log} "Error rows are off by default"; {@code app-to-model-accounting} "Error rows for answers Core gives
 * itself" and "Error rows for a relay that breaks", scenarios "Setting off", and "Passes without an analytics row".
 */
public class ErrorRowsDisabledApiTest extends BaseErrorRowsApiTest {

    @Override
    boolean isErrorRowsOn() {
        return false;
    }

    @Test
    void testSettingAbsentIsOff() {
        assertFalse(dial.getProxy().getLogStore().isErrorRowsEnabled());
    }

    @Test
    void testSettingOffEveryUpstreamBusy() {
        putModel("m-busy");
        serve("m-busy", 429);

        verify(chat("m-busy"), 503);

        rows(0);
    }

    @Test
    void testSettingOffNoRoute() {
        putModel("m-no-route");
        serve("m-no-route", 503);

        verify(chat("m-no-route"), 502);

        rows(0);
    }

    @Test
    void testSettingOffTwoUpstreamsFailed() {
        putModelWithTwoUpstreams("m-two-failed");
        serve("m-two-failed", 502, 429);

        verify(chat("m-two-failed"), 502);

        rows(0);
    }

    @Test
    void testSettingOffConnectionRefused() {
        putModel("m-refused", "http://localhost:4895" + adapterPath("m-refused"), "");

        verify(chat("m-refused"), 502);

        rows(0);
    }

    @Test
    void testSettingOffRefusals() {
        putModel("m-forbidden", adapterUrl("m-forbidden"), ", \"userRoles\": [\"some-other-role\"]");
        putModel("m-refusals");

        verify(chat("m-forbidden"), 403);
        verify(chat("no-such-model"), 404);
        verify(send(HttpMethod.POST, adapterPath("m-refusals"), null, CHAT_BODY, "content-type", "text/plain"), 415);
        verify(send(HttpMethod.POST, adapterPath("m-refusals"), null, "{not json", "content-type", "application/json"), 400);

        rows(0);
    }

    @Test
    void testSettingOffOtherSurfaces() {
        // a feature call to an unknown deployment, an MCP call to an unknown toolset and a refused route
        verify(send(HttpMethod.POST, "/v1/deployments/no-such-model/tokenize", null, "{}", "content-type", "application/json"), 404);
        verify(send(HttpMethod.POST, "/v1/toolset/unknown/mcp", null, "{\"payload\": \"foo\"}"), 404);
        verify(send(HttpMethod.POST, "/v1/vector_store/1", null, "x", "api-key", "vstore_user_key"), 403);

        rows(0);
    }

    @Test
    @SneakyThrows
    void testSettingOffOriginBreaks() {
        try (RawOrigin origin = new RawOrigin(RAW_PORT, RawOrigin.closesAfterHeaders())) {
            putModel("m-closed", rawUrl("m-closed"), "");

            try {
                chat("m-closed");
            } catch (Exception e) {
                // the connection ends without an answer
            }

            rows(0);
        }
    }

    @Test
    void testSettingOffSuccessRow() {
        putModel("m-ok");
        serve("m-ok", 200);

        verify(chat("m-ok"), 200);

        AnalyticsLogContext row = rows(1).get(0);
        assertEquals(200, row.getResponseStatusCode());
    }

    @Test
    void testModelBehindInterceptorsOneRow() {
        putModel("m-behind", adapterUrl("m-behind"), ", \"interceptors\": [\"interceptor1\"]");
        serve("m-behind", 200);

        try (TestWebServer interceptor = new TestWebServer(4088)) {
            // the interceptor hands the request back to Core with the key it was given
            interceptor.map(HttpMethod.POST, "/api/v1/interceptor/handle", request -> {
                Response callback = send(HttpMethod.POST, "/openai/deployments/interceptor/chat/completions", null,
                        request.getBody().readUtf8(), "api-key", request.getHeader("api-key"), "content-type", "application/json");
                return TestWebServer.createResponse(callback.status(), callback.body(), "content-type", "application/json");
            });

            verify(chat("m-behind"), 200);
        }

        // one row, for the model, and none for the interceptor
        AnalyticsLogContext row = rows(1).get(0);
        assertEquals("m-behind", row.getDeploymentName());
        assertEquals(200, row.getResponseStatusCode());
    }
}
