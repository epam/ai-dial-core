package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The explicit-admin gate of {@code POST /v1/admin/export} when {@code access.admin.rules} is empty: the matcher treats an empty rule set
 * as allow-all, so the fail-closed gate answers 403 to everyone, the admin token included.
 */
public class ExportAdminRulesApiTest extends ResourceBaseTest {

    private static final String PATH = ExportApiSupport.PATH;
    private static final String MARKER = "SUBMITTED-VALUE-MARKER";
    private static final String PLATFORM_ROOT = "{\"roots\":[{\"type\":\"application\",\"url\":\"applications/platform/exported\"}]}";

    @Override
    protected JsonObject additionalSettingsOverrides() {
        return new JsonObject()
                .put("access", new JsonObject()
                        .put("admin", new JsonObject().put("rules", new JsonArray())));
    }

    @Test
    void testGateComesBeforeTheValidationOfTheBodyAndTheLookupOfTheRoots() {
        // with the rules configured these are 400 and 404; with none the caller learns nothing about them
        Response invalidBody = send(HttpMethod.POST, PATH, null, "not json " + MARKER, "authorization", "admin");
        Response missingRoot = send(HttpMethod.POST, PATH, null, PLATFORM_ROOT, "authorization", "admin");

        assertEquals(403, invalidBody.status());
        assertEquals(403, missingRoot.status());
        assertEquals(403, send(HttpMethod.POST, PATH, null, "", "authorization", "admin").status());
        assertFalse(invalidBody.body().contains(MARKER));
    }
}
