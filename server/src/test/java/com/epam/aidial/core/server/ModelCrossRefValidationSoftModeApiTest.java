package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Soft-write tests for slice 2S.13 cross-reference validation. With
 * {@code softValidation=true} on the request and {@code config.onInvalidEntity=skip},
 * Model writes with unknown interceptor refs commit; the next merged-config rebuild
 * surfaces the entity through the invalid-entity sibling store with
 * {@code status:invalid} and the cross-ref warning.
 */
public class ModelCrossRefValidationSoftModeApiTest extends ResourceBaseTest {

    private static final String MODEL_BODY_UNKNOWN_INTERCEPTOR = """
            {
              "type": "chat",
              "endpoint": "http://localhost:7001/openai/deployments/test-model/chat/completions",
              "interceptors": ["unknown-interceptor"]
            }
            """;

    private static final String MODEL_BODY_KNOWN_INTERCEPTOR = """
            {
              "type": "chat",
              "endpoint": "http://localhost:7001/openai/deployments/test-model/chat/completions",
              "interceptors": ["interceptor1"]
            }
            """;

    private static final String MODEL_BODY_NO_INTERCEPTORS = """
            {
              "type": "chat",
              "endpoint": "http://localhost:7001/openai/deployments/test-model/chat/completions"
            }
            """;

    @Override
    protected JsonObject additionalSettingsOverrides() {
        return new JsonObject()
                .put("config", new JsonObject().put("onInvalidEntity", "skip"));
    }

    @Test
    void testSoftModeCommitsUnknownRef() {
        Response post = send(HttpMethod.PUT, "/v1/models/platform/soft-cr-unknown", "softValidation=true",
                MODEL_BODY_UNKNOWN_INTERCEPTOR, "authorization", "admin", "If-None-Match", "*");
        verify(post, 200);
        assertNotNull(post.headers().get("etag"));
    }

    @Test
    void testSoftModeShowsStatusInvalidPostRebuild() {
        Response post = send(HttpMethod.PUT, "/v1/models/platform/soft-cr-invalid", "softValidation=true",
                MODEL_BODY_UNKNOWN_INTERCEPTOR, "authorization", "admin", "If-None-Match", "*");
        verify(post, 200);

        JsonNode body = waitForGetMatching(
                "/v1/models/platform/soft-cr-invalid",
                node -> "invalid".equals(node.path("status").asText()));
        JsonNode warnings = body.get("validationWarnings");
        assertNotNull(warnings, () -> "Expected validationWarnings: " + body);
        assertTrue(warnings.isArray() && warnings.size() >= 1,
                () -> "Expected at least one warning: " + body);
        assertEquals("interceptors[0]", warnings.get(0).get("field").asText());
    }

    @Test
    void testSoftModePutCommitsUnknownRef() {
        verify(send(HttpMethod.PUT, "/v1/models/platform/soft-cr-put", null,
                MODEL_BODY_NO_INTERCEPTORS, "authorization", "admin", "If-None-Match", "*"), 200);

        Response put = send(HttpMethod.PUT, "/v1/models/platform/soft-cr-put", "softValidation=true",
                MODEL_BODY_UNKNOWN_INTERCEPTOR, "authorization", "admin");
        verify(put, 200);

        JsonNode body = waitForGetMatching(
                "/v1/models/platform/soft-cr-put",
                node -> "invalid".equals(node.path("status").asText()));
        JsonNode warnings = body.get("validationWarnings");
        assertNotNull(warnings);
        assertEquals("interceptors[0]", warnings.get(0).get("field").asText());
    }

    @Test
    void testSoftModeKnownRefStillValid() {
        Response post = send(HttpMethod.PUT, "/v1/models/platform/soft-cr-good", null,
                MODEL_BODY_KNOWN_INTERCEPTOR, "authorization", "admin", "If-None-Match", "*");
        verify(post, 200);

        JsonNode body = waitForGetMatching(
                "/v1/models/platform/soft-cr-good",
                node -> "valid".equals(node.path("status").asText()));
        assertEquals("valid", body.get("status").asText());
    }

    // Soft mode admits only what the rebuild itself tolerates — cross-references, which a later
    // interceptor write repairs. A rebuild invariant is rejected even here: admitting it would write
    // a blob the next reload discards, and under onInvalidEntity=abort would stop the pod starting.

    @Test
    void testSoftModeStillRejectsUnservedInterface() {
        String body = """
                {
                  "type": "chat",
                  "endpoint": "http://localhost:7001/openai/deployments/test-model/chat/completions",
                  "interfaces": {"openaiChatCompletions": {}}
                }
                """;
        Response put = send(HttpMethod.PUT, "/v1/models/platform/soft-unserved-interface", "softValidation=true", body,
                "authorization", "admin", "If-None-Match", "*");
        verify(put, 422);
        assertTrue(put.body().contains("interfaces.openaiChatCompletions"),
                () -> "Expected the offending field in the body: " + put.body());
        assertTrue(put.body().contains("declares no base_url"),
                () -> "Expected the rebuild's own message: " + put.body());

        // the blob must never have been written — otherwise it poisons the next rebuild
        verify(send(HttpMethod.GET, "/v1/models/platform/soft-unserved-interface", null, "",
                "authorization", "admin"), 404);
    }

    @Test
    void testSoftModeStillRejectsBadCachePricing() {
        String body = """
                {
                  "type": "chat",
                  "endpoint": "http://localhost:7001/openai/deployments/test-model/chat/completions",
                  "pricing": {"unit": "char_without_whitespace", "prompt": "0.1", "cacheRead": "0.01"}
                }
                """;
        Response put = send(HttpMethod.PUT, "/v1/models/platform/soft-bad-pricing", "softValidation=true", body,
                "authorization", "admin", "If-None-Match", "*");
        verify(put, 422);
        verify(send(HttpMethod.GET, "/v1/models/platform/soft-bad-pricing", null, "",
                "authorization", "admin"), 404);
    }

    @Test
    void testSoftWriteSurvivesRebuildUnderSkip() {
        verify(send(HttpMethod.PUT, "/v1/models/platform/soft-survives", "softValidation=true",
                MODEL_BODY_UNKNOWN_INTERCEPTOR, "authorization", "admin"), 200);

        verify(operationRequest("/v1/ops/config/reload", null, "Authorization", "admin"), 200);

        JsonNode body = waitForGetMatching("/v1/models/platform/soft-survives",
                node -> "invalid".equals(node.path("status").asText()));
        assertEquals("interceptors[0]", body.at("/validationWarnings/0/field").asText());
    }

    @Test
    void testInterceptorCreatedLaterUnderSkip() {
        verify(send(HttpMethod.PUT, "/v1/models/platform/soft-later", "softValidation=true",
                MODEL_BODY_UNKNOWN_INTERCEPTOR.replace("unknown-interceptor", "soft-later-i"), "authorization", "admin"), 200);
        waitForGetMatching("/v1/models/platform/soft-later", node -> "invalid".equals(node.path("status").asText()));

        verify(send(HttpMethod.PUT, "/v1/interceptors/platform/soft-later-i", null,
                "{\"endpoint\": \"http://localhost:7001/intercept\"}", "authorization", "admin"), 200);

        waitForGetMatching("/v1/models/platform/soft-later", node -> "valid".equals(node.path("status").asText()));
    }

    /** The default {@code config.onInvalidEntity: abort}: a soft write is recorded the same way. */
    public static class AbortMode extends ResourceBaseTest {

        @Test
        @SneakyThrows
        void testSoftWriteUnderAbort() {
            Response put = send(HttpMethod.PUT, "/v1/models/platform/soft-cr-invalid", "softValidation=true",
                    MODEL_BODY_UNKNOWN_INTERCEPTOR, "authorization", "admin", "If-None-Match", "*");
            verify(put, 200);
            assertNotNull(put.headers().get("etag"));

            Response get = send(HttpMethod.GET, "/v1/models/platform/soft-cr-invalid", null, "", "authorization", "admin");
            verify(get, 200);
            JsonNode body = ProxyUtil.MAPPER.readTree(get.body());
            assertEquals("invalid", body.get("status").asText());
            assertEquals("interceptors[0]", body.at("/validationWarnings/0/field").asText());

            JsonNode health = ProxyUtil.MAPPER.readTree(
                    send(HttpMethod.GET, "/v1/admin/health/config", null, "", "authorization", "admin").body());
            assertEquals("degraded", health.get("status").asText());
        }
    }

    private JsonNode waitForGetMatching(String url, Predicate<JsonNode> predicate) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        JsonNode last = null;
        while (System.nanoTime() < deadline) {
            Response r = send(HttpMethod.GET, url, null, "", "authorization", "admin");
            if (r.status() == 200 && r.body() != null) {
                try {
                    JsonNode node = ProxyUtil.MAPPER.readTree(r.body());
                    last = node;
                    if (predicate.test(node)) {
                        return node;
                    }
                } catch (Exception ignored) {
                    // keep polling
                }
            }
            sleepShort();
        }
        fail("GET " + url + " did not match within timeout. Last body: " + last);
        return null;
    }

    private static void sleepShort() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting", e);
        }
    }
}
