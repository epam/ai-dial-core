package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ProxyUtil;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverridePathsWriteApiTest extends ResourceBaseTest {

    private static final String VALID = """
            {"endpoint":"http://localhost:4848/legacy", "interfaces":{"openaiResponses":{
              "base_url":"http://localhost:4848",
              "overridePaths":{"getOpenaiResponsesById":"/valid/{id}"}
            }}}
            """;
    private static final String INVALID = VALID.replace("/valid/{id}", "");

    @ParameterizedTest
    @ValueSource(strings = {
            "models/platform", "interceptors/platform", "applications/platform",
            "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST"
    })
    void invalidPutDoesNotCreateOrOverwriteResource(String parent) throws Exception {
        String path = "/v1/" + parent + "/override-write";
        boolean platform = parent.endsWith("/platform");
        String[] auth = platform ? new String[]{"authorization", "admin"} : new String[0];

        Response invalidCreate = send(HttpMethod.PUT, path, null, INVALID, auth);
        assertEquals(422, invalidCreate.status(), invalidCreate.body());
        assertTrue(invalidCreate.body().contains("getOpenaiResponsesById"), invalidCreate.body());
        assertEquals(404, send(HttpMethod.GET, path, null, "", auth).status());

        Response created = send(HttpMethod.PUT, path, null, VALID, auth);
        assertEquals(200, created.status(), created.body());
        Response rejected = send(HttpMethod.PUT, path, null, INVALID, auth);
        assertEquals(422, rejected.status(), rejected.body());
        assertStoredPath(path, auth);
    }

    @ParameterizedTest
    @CsvSource({
            "Model, models, true", "Model, models, false",
            "Interceptor, interceptors, true", "Interceptor, interceptors, false",
            "Application, applications, true", "Application, applications, false"
    })
    void adminApplyRejectsBeforeOverwritingWithOrWithoutPrecheck(String kind, String group, boolean precheck) throws Exception {
        String id = group + "/platform/override-apply";
        Response created = send(HttpMethod.PUT, "/v1/" + id, null, VALID, "authorization", "admin");
        assertEquals(200, created.status(), created.body());
        String body = "{\"precheck\":" + precheck + ",\"manifests\":[{\"kind\":\"" + kind
                + "\",\"name\":\"" + id + "\",\"spec\":" + INVALID + "}]}";

        Response applied = send(HttpMethod.POST, "/v1/admin/apply", null, body, "authorization", "admin");
        assertEquals(precheck ? 422 : 200, applied.status(), applied.body());
        assertEquals(1, ProxyUtil.MAPPER.readTree(applied.body()).path("failed").asInt(), applied.body());
        assertEquals(0, ProxyUtil.MAPPER.readTree(applied.body()).path("applied").asInt(), applied.body());
        assertStoredPath("/v1/" + id, "authorization", "admin");
    }

    @ParameterizedTest
    @CsvSource({"Model, models", "Interceptor, interceptors", "Application, applications"})
    void adminValidateRejectsInvalidOverridePath(String kind, String group) {
        String body = "{\"manifests\":[{\"kind\":\"" + kind + "\",\"name\":\""
                + group + "/platform/override-validate\",\"spec\":" + INVALID + "}]}";
        Response response = send(HttpMethod.POST, "/v1/admin/validate", null, body, "authorization", "admin");
        assertEquals(422, response.status(), response.body());
        assertTrue(response.body().contains("getOpenaiResponsesById"), response.body());
    }

    @ParameterizedTest
    @CsvSource(value = {"NULL", "softValidation=true"}, nullValues = "NULL")
    void modelPutReturnsOverridePathAndCrossReferenceWarnings(String query) throws Exception {
        String path = "/v1/models/platform/override-combined";
        String body = new JsonObject(INVALID).put("interceptors", List.of("missing-interceptor")).encode();

        Response response = send(HttpMethod.PUT, path, query, body, "authorization", "admin");

        assertEquals(422, response.status(), response.body());
        assertTrue(response.body().contains("interfaces.openaiResponses.overridePaths.getOpenaiResponsesById"), response.body());
        assertTrue(response.body().contains("interceptors[0]"), response.body());
        assertEquals(404, send(HttpMethod.GET, path, null, "", "authorization", "admin").status());
    }

    @ParameterizedTest
    @CsvSource(value = {"NULL", "softValidation=true"}, nullValues = "NULL")
    void modelApplyReturnsAllValidationWarnings(String query) throws Exception {
        String id = "models/platform/override-combined";
        JsonObject spec = new JsonObject(INVALID)
                .put("interceptors", List.of("missing-interceptor"))
                .put("pricing", new JsonObject().put("unit", "char_without_whitespace").put("cacheRead", "1"));
        String body = new JsonObject().put("precheck", false)
                .put("manifests", List.of(new JsonObject().put("kind", "Model").put("name", id).put("spec", spec)))
                .encode();

        Response response = send(HttpMethod.POST, "/v1/admin/apply", query, body, "authorization", "admin");

        assertEquals(200, response.status(), response.body());
        var result = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(1, result.path("failed").asInt(), response.body());
        assertEquals(0, result.path("applied").asInt(), response.body());
        assertTrue(response.body().contains("interfaces.openaiResponses.overridePaths.getOpenaiResponsesById"), response.body());
        assertTrue(response.body().contains("interceptors[0]"), response.body());
        assertTrue(response.body().contains("pricing"), response.body());
        assertEquals(404, send(HttpMethod.GET, "/v1/" + id, null, "", "authorization", "admin").status());
    }

    /**
     * Soft validation forgives cross-reference warnings, but not override-path defects: the real apply
     * phase refuses those regardless of the parameter, so the precheck surface must refuse them as well.
     */
    @Test
    void modelValidateRejectsOverridePathOnly() throws Exception {
        String crossReferenceOnly = new JsonObject(VALID).put("interceptors", List.of("missing-interceptor")).encode();
        Response accepted = send(HttpMethod.POST, "/v1/admin/validate", "softValidation=true",
                validateBody(crossReferenceOnly), "authorization", "admin");
        assertEquals(200, accepted.status(), accepted.body());
        assertEquals(0, ProxyUtil.MAPPER.readTree(accepted.body()).path("failed").asInt(), accepted.body());

        Response rejected = send(HttpMethod.POST, "/v1/admin/validate", "softValidation=true",
                validateBody(INVALID), "authorization", "admin");
        assertEquals(422, rejected.status(), rejected.body());
        assertTrue(rejected.body().contains("getOpenaiResponsesById"), rejected.body());
    }

    private static String validateBody(String spec) {
        return new JsonObject()
                .put("manifests", List.of(new JsonObject()
                        .put("kind", "Model")
                        .put("name", "models/platform/override-soft-validate")
                        .put("spec", new JsonObject(spec))))
                .encode();
    }

    private void assertStoredPath(String path, String... auth) throws Exception {
        Response stored = send(HttpMethod.GET, path, null, "", auth);
        assertEquals(200, stored.status(), stored.body());
        assertEquals("/valid/{id}", ProxyUtil.MAPPER.readTree(stored.body())
                .path("interfaces").path("openaiResponses").path("overridePaths").path("getOpenaiResponsesById").asText());
    }
}
