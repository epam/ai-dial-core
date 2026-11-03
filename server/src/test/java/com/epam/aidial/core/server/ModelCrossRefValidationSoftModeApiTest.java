package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static com.epam.aidial.core.server.util.ResourceDescriptorFactory.fromDecoded;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Soft-mode tests for slice 2S.13 cross-reference validation. With
 * {@code config.write.softValidation=true} and {@code config.onInvalidEntity=skip},
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
                .put("config", new JsonObject()
                        .put("write", new JsonObject().put("softValidation", true))
                        .put("onInvalidEntity", "skip"));
    }

    @Test
    void testSoftModeCommitsUnknownRef() {
        Response post = send(HttpMethod.PUT, "/v1/models/platform/soft-cr-unknown", null,
                MODEL_BODY_UNKNOWN_INTERCEPTOR, "authorization", "admin", "If-None-Match", "*");
        verify(post, 200);
        assertNotNull(post.headers().get("etag"));
    }

    @Test
    void testSoftModeShowsStatusInvalidPostRebuild() {
        Response post = send(HttpMethod.PUT, "/v1/models/platform/soft-cr-invalid", null,
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
        assertEquals("degraded", healthBody().get("status").asText());
    }

    @Test
    void testRebuildUnderSkipIsInvalid() {
        putBlob("rebuild-skip-dangling", MODEL_BODY_UNKNOWN_INTERCEPTOR);
        putBlob("rebuild-skip-valid", MODEL_BODY_NO_INTERCEPTORS);

        reload();

        JsonNode dangling = waitForGetMatching("/v1/models/platform/rebuild-skip-dangling",
                node -> "invalid".equals(node.path("status").asText()));
        assertEquals("interceptors[0]", dangling.get("validationWarnings").get(0).get("field").asText());
        assertEquals("valid", waitForGetMatching("/v1/models/platform/rebuild-skip-valid",
                node -> "valid".equals(node.path("status").asText())).get("status").asText());
        assertTrue(healthBody().toString().contains("models/platform/rebuild-skip-dangling"));
    }

    @Test
    void testMalformedBlobSkippedUnderSkip() {
        putBlob("malformed-skip", "{ this is not json ");

        reload();

        JsonNode body = waitForGetMatching("/v1/models/platform/malformed-skip",
                node -> "invalid".equals(node.path("status").asText()));
        assertTrue(body.get("validationWarnings").size() >= 1, () -> "Expected a warning: " + body);
    }

    @Test
    void testSoftModePutCommitsUnknownRef() {
        verify(send(HttpMethod.PUT, "/v1/models/platform/soft-cr-put", null,
                MODEL_BODY_NO_INTERCEPTORS, "authorization", "admin", "If-None-Match", "*"), 200);

        Response put = send(HttpMethod.PUT, "/v1/models/platform/soft-cr-put", null,
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
        Response put = send(HttpMethod.PUT, "/v1/models/platform/soft-unserved-interface", null, body,
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
        Response put = send(HttpMethod.PUT, "/v1/models/platform/soft-bad-pricing", null, body,
                "authorization", "admin", "If-None-Match", "*");
        verify(put, 422);
        verify(send(HttpMethod.GET, "/v1/models/platform/soft-bad-pricing", null, "",
                "authorization", "admin"), 404);
    }

    private void putBlob(String name, String body) {
        ResourceService resourceService = dial.getProxy().getResourceService();
        ResourceDescriptor descriptor = fromDecoded(ResourceTypes.MODEL, ResourceDescriptor.PLATFORM_BUCKET,
                ResourceDescriptor.PLATFORM_LOCATION, name);
        resourceService.putResource(descriptor, body, EtagHeader.ANY, null, false);
    }

    private void reload() {
        Response response = operationRequest("/v1/ops/config/reload", null, "Authorization", "admin");
        assertEquals(200, response.status(), () -> "Body: " + response.body());
    }

    @SneakyThrows
    private JsonNode healthBody() {
        Response response = send(HttpMethod.GET, "/v1/admin/health/config", null, "", "authorization", "admin");
        verify(response, 200);
        return ProxyUtil.MAPPER.readTree(response.body());
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

    /** {@code softValidation: true} with {@code onInvalidEntity} left at its default, {@code abort}. */
    public static class SoftWriteUnderAbort extends ResourceBaseTest {
        @Override
        protected JsonObject additionalSettingsOverrides() {
            return new JsonObject()
                    .put("config", new JsonObject()
                            .put("write", new JsonObject().put("softValidation", true)));
        }

        @Test
        @SneakyThrows
        void testSoftWriteUnderAbortIsInvalid() {
            Response put = send(HttpMethod.PUT, "/v1/models/platform/soft-abort-invalid", null,
                    MODEL_BODY_UNKNOWN_INTERCEPTOR, "authorization", "admin", "If-None-Match", "*");
            verify(put, 200);
            assertNotNull(put.headers().get("etag"));

            Response get = send(HttpMethod.GET, "/v1/models/platform/soft-abort-invalid", null, "",
                    "authorization", "admin");
            verify(get, 200);
            JsonNode model = ProxyUtil.MAPPER.readTree(get.body());
            assertEquals("invalid", model.get("status").asText());
            assertEquals("interceptors[0]", model.get("validationWarnings").get(0).get("field").asText());

            Response health = send(HttpMethod.GET, "/v1/admin/health/config", null, "",
                    "authorization", "admin");
            assertEquals("degraded", ProxyUtil.MAPPER.readTree(health.body()).get("status").asText());
        }
    }
}
