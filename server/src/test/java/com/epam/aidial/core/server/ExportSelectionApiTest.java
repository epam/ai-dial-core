package com.epam.aidial.core.server;

import com.epam.aidial.core.server.data.ApiKeyData;
import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The checks that the selective export on {@code POST /v1/admin/export} makes before it writes an archive, which are the statuses of the
 * endpoint. The content is tested in the other {@code Export*ApiTest} classes.
 */
public class ExportSelectionApiTest extends ResourceBaseTest {

    private static final String PATH = ExportApiSupport.PATH;
    private static final String MARKER = "SUBMITTED-VALUE-MARKER";
    private static final String PLATFORM_ROOT = "{\"roots\":[{\"type\":\"application\",\"url\":\"applications/platform/exported\"}]}";
    private static final String PUBLIC_ROOT = "{\"roots\":[{\"type\":\"application\",\"url\":\"applications/public/exported__1.0.0\"}]}";

    private Response exportAs(String token, String body) {
        return send(HttpMethod.POST, PATH, null, body, "authorization", token);
    }

    @Test
    void testCallerWithoutExplicitAdminAccessIsForbidden() {
        verify(exportAs("user", PLATFORM_ROOT), 403);
        verify(send(HttpMethod.POST, PATH, null, PLATFORM_ROOT, "Api-key", "proxyKey1"), 403);

        Response response = exportAs("user", "{\"roots\":[{\"type\":\"application\",\"url\":\"applications/platform/" + MARKER + "\"}]}");

        assertEquals(403, response.status());
        assertFalse(response.body().contains(MARKER), "the answer must say nothing about the roots");
    }

    @Test
    void testInvalidBodiesAreBadRequestsThatEchoNothing() {
        // the other shapes of an invalid body are covered by ExportRequestParserTest
        String[] bodies = {
                "not json " + MARKER,
                "{\"roots\":[{\"type\":\"application\",\"url\":\"applications/platform/a\"}],\"" + MARKER + "\":1}",
        };
        for (String body : bodies) {
            Response response = exportAs("admin", body);

            assertEquals(400, response.status(), body);
            assertFalse(response.body().contains(MARKER), "the answer echoes a submitted value: " + response.body());
        }
    }

    @Test
    void testRootThatDoesNotExistIsNotFound() {
        verify(exportAs("admin", PLATFORM_ROOT), 404);
        verify(exportAs("admin", PUBLIC_ROOT), 404);
    }

    @Test
    void testNoSeparatePathExists() {
        verify(send(HttpMethod.POST, PATH + "/applications", null, PLATFORM_ROOT, "authorization", "admin"), 404);
        verify(send(HttpMethod.GET, PATH + "/applications", null, "", "authorization", "admin"), 404);
    }

    private String perRequestKeyOfAdmin() {
        ApiKeyData adminAppKey = createAdminAppKey();
        apiKeyStore.assignPerRequestApiKey(adminAppKey);
        return adminAppKey.getPerRequestKey();
    }

    @Test
    void testPerRequestKeyCarryingTheAdminIdentityIsForbidden() {
        Response response = send(HttpMethod.POST, PATH, null, PLATFORM_ROOT, "Api-key", perRequestKeyOfAdmin());

        verify(response, 403);
    }
}
