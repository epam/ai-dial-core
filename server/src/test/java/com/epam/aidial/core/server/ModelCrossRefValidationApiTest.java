package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static com.epam.aidial.core.server.util.ResourceDescriptorFactory.fromDecoded;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Strict-mode tests for slice 2S.13 cross-reference validation, U.0-amended (2026-05-20) to the
 * PUT-upsert wire shape. The Model write controller's interceptor cross-ref check rejects unknown
 * references at write time with HTTP 422 and a {@code {"validationWarnings": [...]}} body, with the
 * default {@code softValidation=false}. The last tests cover a reference that dangles anyway (a blob
 * written outside the API, a deleted interceptor, a file model), which the default {@code abort}
 * mode records as an invalid entity.
 */
public class ModelCrossRefValidationApiTest extends ResourceBaseTest {

    private static final String MODEL_BODY_NO_INTERCEPTORS = """
            {
              "type": "chat",
              "endpoint": "http://localhost:7001/openai/deployments/test-model/chat/completions"
            }
            """;

    private static final String MODEL_BODY_KNOWN_INTERCEPTOR = """
            {
              "type": "chat",
              "endpoint": "http://localhost:7001/openai/deployments/test-model/chat/completions",
              "interceptors": ["interceptor1"]
            }
            """;

    private static final String MODEL_BODY_UNKNOWN_INTERCEPTOR = """
            {
              "type": "chat",
              "endpoint": "http://localhost:7001/openai/deployments/test-model/chat/completions",
              "interceptors": ["unknown-interceptor"]
            }
            """;

    private static final String MODEL_BODY_TWO_UNKNOWN = """
            {
              "type": "chat",
              "endpoint": "http://localhost:7001/openai/deployments/test-model/chat/completions",
              "interceptors": ["unknownA", "unknownB"]
            }
            """;

    private static final String MODEL_BODY_EMPTY_INTERCEPTORS = """
            {
              "type": "chat",
              "endpoint": "http://localhost:7001/openai/deployments/test-model/chat/completions",
              "interceptors": []
            }
            """;

    private static final String INTERCEPTOR_BODY = """
            {"endpoint": "http://localhost:7001/intercept"}
            """;

    @Test
    void testPutCreateKnownInterceptor() {
        Response put = send(HttpMethod.PUT, "/v1/models/platform/cr-known", null,
                MODEL_BODY_KNOWN_INTERCEPTOR, "authorization", "admin", "If-None-Match", "*");
        verify(put, 200);
        assertNotNull(put.headers().get("etag"));
    }

    @Test
    void testPutCreateUnknownInterceptorReturns422() throws Exception {
        Response put = send(HttpMethod.PUT, "/v1/models/platform/cr-unknown", null,
                MODEL_BODY_UNKNOWN_INTERCEPTOR, "authorization", "admin", "If-None-Match", "*");
        verify(put, 422);
        JsonNode body = ProxyUtil.MAPPER.readTree(put.body());
        JsonNode warnings = body.get("validationWarnings");
        assertNotNull(warnings, () -> "Expected validationWarnings array: " + put.body());
        assertTrue(warnings.isArray(), () -> "Expected array: " + put.body());
        assertEquals(1, warnings.size(), () -> "Expected one warning: " + put.body());
        assertEquals("interceptors[0]", warnings.get(0).get("field").asText());
        assertTrue(warnings.get(0).get("message").asText().contains("unknown-interceptor"),
                () -> "Expected ref name in message: " + put.body());
    }

    @Test
    void testPutCreateUnknownInterceptorNoCommit() {
        Response put = send(HttpMethod.PUT, "/v1/models/platform/cr-no-commit", null,
                MODEL_BODY_UNKNOWN_INTERCEPTOR, "authorization", "admin", "If-None-Match", "*");
        verify(put, 422);
        Response get = send(HttpMethod.GET, "/v1/models/platform/cr-no-commit", null, "",
                "authorization", "admin");
        verify(get, 404);
    }

    @Test
    void testPutCreateMultipleUnknownInterceptors() throws Exception {
        Response put = send(HttpMethod.PUT, "/v1/models/platform/cr-multi", null,
                MODEL_BODY_TWO_UNKNOWN, "authorization", "admin", "If-None-Match", "*");
        verify(put, 422);
        JsonNode warnings = ProxyUtil.MAPPER.readTree(put.body()).get("validationWarnings");
        assertEquals(2, warnings.size(), () -> "Expected two warnings: " + put.body());
        assertEquals("interceptors[0]", warnings.get(0).get("field").asText());
        assertEquals("interceptors[1]", warnings.get(1).get("field").asText());
    }

    @Test
    void testPutCreateEmptyInterceptorsArray() {
        Response put = send(HttpMethod.PUT, "/v1/models/platform/cr-empty", null,
                MODEL_BODY_EMPTY_INTERCEPTORS, "authorization", "admin", "If-None-Match", "*");
        verify(put, 200);
    }

    @Test
    void testPutCreateInterceptorsAbsent() {
        Response put = send(HttpMethod.PUT, "/v1/models/platform/cr-absent", null,
                MODEL_BODY_NO_INTERCEPTORS, "authorization", "admin", "If-None-Match", "*");
        verify(put, 200);
    }

    @Test
    void testPutUpdateUnknownInterceptorReturns422() {
        verify(send(HttpMethod.PUT, "/v1/models/platform/cr-put-bad", null,
                MODEL_BODY_NO_INTERCEPTORS, "authorization", "admin", "If-None-Match", "*"), 200);

        Response put = send(HttpMethod.PUT, "/v1/models/platform/cr-put-bad", null,
                MODEL_BODY_UNKNOWN_INTERCEPTOR, "authorization", "admin");
        verify(put, 422);
    }

    @Test
    void testPutUpdateKnownInterceptor() {
        verify(send(HttpMethod.PUT, "/v1/models/platform/cr-put-good", null,
                MODEL_BODY_NO_INTERCEPTORS, "authorization", "admin", "If-None-Match", "*"), 200);

        Response put = send(HttpMethod.PUT, "/v1/models/platform/cr-put-good", null,
                MODEL_BODY_KNOWN_INTERCEPTOR, "authorization", "admin");
        verify(put, 200);
    }

    // The tests below do not go through the strict write gate: they write blobs straight to storage, or
    // delete an interceptor, and then look at what Core does with a model whose reference dangles.
    // Default settings: softValidation=false, onInvalidEntity=abort.

    @Test
    void testRebuildUnderAbortRecordsDanglingModel() {
        putBlob("rebuild-dangling", MODEL_BODY_UNKNOWN_INTERCEPTOR);
        putBlob("rebuild-valid", MODEL_BODY_NO_INTERCEPTORS);

        reload();

        JsonNode dangling = adminGet("/v1/models/platform/rebuild-dangling");
        assertEquals("invalid", dangling.get("status").asText());
        assertEquals("interceptors[0]", dangling.get("validationWarnings").get(0).get("field").asText());
        assertEquals("valid", adminGet("/v1/models/platform/rebuild-valid").get("status").asText());
        assertFalse(listedModelIds().contains("rebuild-dangling"));
        assertTrue(listedModelIds().contains("rebuild-valid"));
        assertTrue(skippedIds().contains("models/platform/rebuild-dangling"));
    }

    @Test
    void testOtherViolationsStillAbort() {
        String danglingWithBadPricing = """
                {
                  "type": "chat",
                  "endpoint": "http://localhost:7001/openai/deployments/test-model/chat/completions",
                  "interceptors": ["unknown-interceptor"],
                  "pricing": {"unit": "char_without_whitespace", "prompt": "0.1", "cacheRead": "0.01"}
                }
                """;
        putBlob("abort-mixed", danglingWithBadPricing);

        Response reload = operationRequest("/v1/ops/config/reload", null, "Authorization", "admin");

        assertEquals(500, reload.status(), () -> "Body: " + reload.body());
        assertTrue(listedModelIds().contains("chat-gpt-35-turbo"), "the previous config must still be served");
    }

    @Test
    void testInterceptorDeleteUnderAbort() {
        verify(send(HttpMethod.PUT, "/v1/interceptors/platform/del-i1", null, INTERCEPTOR_BODY,
                "authorization", "admin", "If-None-Match", "*"), 200);
        verify(send(HttpMethod.PUT, "/v1/models/platform/del-model", null,
                MODEL_BODY_UNKNOWN_INTERCEPTOR.replace("unknown-interceptor", "del-i1"),
                "authorization", "admin", "If-None-Match", "*"), 200);
        assertTrue(listedModelIds().contains("del-model"));

        verify(send(HttpMethod.DELETE, "/v1/interceptors/platform/del-i1", null, "",
                "authorization", "admin"), 204);

        JsonNode model = adminGet("/v1/models/platform/del-model");
        assertEquals("invalid", model.get("status").asText());
        assertEquals("interceptors[0]", model.get("validationWarnings").get(0).get("field").asText());
        assertFalse(listedModelIds().contains("del-model"));
    }

    @Test
    void testInterceptorCreatedLater() {
        verify(send(HttpMethod.PUT, "/v1/interceptors/platform/back-i3", null, INTERCEPTOR_BODY,
                "authorization", "admin", "If-None-Match", "*"), 200);
        verify(send(HttpMethod.PUT, "/v1/models/platform/back-model", null,
                MODEL_BODY_UNKNOWN_INTERCEPTOR.replace("unknown-interceptor", "back-i3"),
                "authorization", "admin", "If-None-Match", "*"), 200);
        verify(send(HttpMethod.DELETE, "/v1/interceptors/platform/back-i3", null, "",
                "authorization", "admin"), 204);
        assertEquals("invalid", adminGet("/v1/models/platform/back-model").get("status").asText());

        verify(send(HttpMethod.PUT, "/v1/interceptors/platform/back-i3", null, INTERCEPTOR_BODY,
                "authorization", "admin", "If-None-Match", "*"), 200);
        reload();

        assertEquals("valid", adminGet("/v1/models/platform/back-model").get("status").asText());
        assertTrue(listedModelIds().contains("back-model"));
    }

    @Test
    @DialConfigLocation("dial-config/dangling-interceptor-file-model.json")
    void testFileModelWithDanglingInterceptorUnderAbort() {
        assertFalse(listedModelIds().contains("dangling-file-model"));
        assertTrue(listedModelIds().contains("valid-file-model"));
        assertTrue(skippedIds().contains("models/platform/dangling-file-model"));
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
    private JsonNode adminGet(String path) {
        Response response = send(HttpMethod.GET, path, null, "", "authorization", "admin");
        verify(response, 200);
        return ProxyUtil.MAPPER.readTree(response.body());
    }

    private Set<String> listedModelIds() {
        Set<String> ids = new HashSet<>();
        adminGet("/openai/models").get("data").forEach(model -> ids.add(model.path("id").asText()));
        return ids;
    }

    private Set<String> skippedIds() {
        Set<String> ids = new HashSet<>();
        adminGet("/v1/admin/health/config").get("skipped").forEach(entry -> ids.add(entry.get("id").asText()));
        return ids;
    }
}
