package com.epam.aidial.core.server;

import com.epam.aidial.core.server.controller.AdminExportController;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.storage.service.LockService;
import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Integration tests for the checks that the selective Application export on {@code POST /v1/admin/export} makes before it writes
 * an archive; the export of whole buckets is another endpoint. The tests run without a redaction policy, so a valid request that
 * gets through every check is answered 503; no assertion here depends on the content of a package.
 */
public class ExportSelectionApiTest extends ResourceBaseTest {

    private static final String PATH = "/v1/admin/export";
    private static final String MARKER = "SUBMITTED-VALUE-MARKER";
    private static final String PLATFORM_ROOT = "{\"roots\":[{\"type\":\"application\",\"url\":\"applications/platform/exported\"}]}";
    private static final String PUBLIC_ROOT = "{\"roots\":[{\"type\":\"application\",\"url\":\"applications/public/exported__1.0.0\"}]}";

    /**
     * With no redaction policy a request that passes the checks ends in 503; the archive itself is tested with a redactor in
     * {@code ExportSelectionStreamApiTest} and with the production one in {@code ExportBodyRedactorApiTest}.
     */
    @BeforeEach
    void withoutRedactor() {
        dial.getProxy().setExportBodyRedactor(null);
    }

    private Response exportAs(String token, String body) {
        return send(HttpMethod.POST, PATH, null, body, "authorization", token);
    }

    private void storeApplication(String blobPath) {
        dial.getStorage().store(blobPath, "application/json", null, Map.of(),
                "{\"endpoint\":\"http://application.invalid/chat\"}".getBytes(StandardCharsets.UTF_8));
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
    void testChecksComeBeforeTheMissingRedactionPolicy() {
        // invalid body: 400, not 503
        verify(exportAs("admin", "{\"roots\":[]}"), 400);
        // root that does not exist: 404, not 503 (another name than the stored one, because a missing resource is remembered)
        verify(exportAs("admin", "{\"roots\":[{\"type\":\"application\",\"url\":\"applications/platform/never-stored\"}]}"), 404);
        // valid root: only now 503
        storeApplication("platform/applications/exported");
        verify(exportAs("admin", PLATFORM_ROOT), 503);
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

    @Test
    void testSlotHeldIsConflictAndTheSlotIsReleasedAfterTheServiceUnavailableAnswer() {
        storeApplication("platform/applications/exported");
        LockService.Lock slot = dial.getProxy().getLockService().tryLock(AdminExportController.SELECTION_SLOT);
        assertNotNull(slot);
        try {
            verify(exportAs("admin", PLATFORM_ROOT), 409);
        } finally {
            slot.close();
        }

        // the 503 path releases the slot: a second request is answered 503 as well and not 409
        verify(exportAs("admin", PLATFORM_ROOT), 503);
        verify(exportAs("admin", PLATFORM_ROOT), 503);
    }
}
