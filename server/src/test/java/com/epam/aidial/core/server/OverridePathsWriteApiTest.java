package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ProxyUtil;
import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverridePathsWriteApiTest extends ResourceBaseTest {

    private static final String VALID = """
            {"endpoint":"http://localhost:4848/legacy", "interfaces":{"openaiResponses":{
              "base_url":"http://localhost:4848",
              "overridePaths":{"getOpenaiResponsesById":"/valid/{id}"}
            }}}
            """;
    private static final String INVALID = VALID.replace("/valid/{id}", "/v1/{unknown}");

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
    void adminValidateRejectsMalformedTemplate(String kind, String group) {
        String body = "{\"manifests\":[{\"kind\":\"" + kind + "\",\"name\":\""
                + group + "/platform/override-validate\",\"spec\":" + INVALID + "}]}";
        Response response = send(HttpMethod.POST, "/v1/admin/validate", null, body, "authorization", "admin");
        assertEquals(422, response.status(), response.body());
        assertTrue(response.body().contains("getOpenaiResponsesById"), response.body());
    }

    private void assertStoredPath(String path, String... auth) throws Exception {
        Response stored = send(HttpMethod.GET, path, null, "", auth);
        assertEquals(200, stored.status(), stored.body());
        assertEquals("/valid/{id}", ProxyUtil.MAPPER.readTree(stored.body())
                .path("interfaces").path("openaiResponses").path("overridePaths").path("getOpenaiResponsesById").asText());
    }
}
