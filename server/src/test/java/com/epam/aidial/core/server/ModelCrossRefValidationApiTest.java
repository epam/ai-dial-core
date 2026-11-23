package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Strict-mode tests for slice 2S.13 cross-reference validation, U.0-amended (2026-05-20) to the
 * PUT-upsert wire shape. The Model write controller's interceptor cross-ref check rejects unknown
 * references at write time with HTTP 422 and a {@code {"validationWarnings": [...]}} body. The
 * underlying merged-config rebuild keeps soft-mode skip behavior; these tests exercise only the
 * strict pre-commit path (no {@code softValidation} parameter, or {@code softValidation=false}).
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

    @Test
    void testStrictWrite() {
        for (String query : new String[] {null, "softValidation=false"}) {
            verify(send(HttpMethod.PUT, "/v1/models/platform/cr-strict", query,
                    MODEL_BODY_UNKNOWN_INTERCEPTOR, "authorization", "admin"), 422);
            verify(send(HttpMethod.GET, "/v1/models/platform/cr-strict", null, "", "authorization", "admin"), 404);
        }
    }

    @Test
    void testNonBooleanValueAnswers400() {
        verify(send(HttpMethod.PUT, "/v1/models/platform/cr-non-boolean", "softValidation=yes",
                MODEL_BODY_NO_INTERCEPTORS, "authorization", "admin"), 400);
        verify(send(HttpMethod.GET, "/v1/models/platform/cr-non-boolean", null, "", "authorization", "admin"), 404);
    }

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

    /** The removed setting is not read: a write without the parameter is still strict. */
    public static class LegacySetting extends ResourceBaseTest {

        @Override
        protected JsonObject additionalSettingsOverrides() {
            return new JsonObject().put("config", new JsonObject().put("write", new JsonObject().put("softValidation", true)));
        }

        @Test
        void testRemovedSettingHasNoEffect() {
            verify(send(HttpMethod.PUT, "/v1/models/platform/cr-legacy", null,
                    MODEL_BODY_UNKNOWN_INTERCEPTOR, "authorization", "admin"), 422);
        }
    }
}
