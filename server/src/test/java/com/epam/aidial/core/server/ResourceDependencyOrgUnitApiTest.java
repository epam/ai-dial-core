package com.epam.aidial.core.server;

import com.epam.aidial.core.server.data.ApiKeyData;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A dependency on a public folder that spans org units resolves only for an initiator who reads every unit under it, as a
 * folder attachment does: the grant covers everything under the folder.
 */
public class ResourceDependencyOrgUnitApiTest extends ResourceBaseTest {

    private static final String APP = "applications/public/dep-units-app";
    private static final String UNIT_FILE = "/v1/files/public/Acme/Risk/r.txt";

    @Override
    protected JsonObject additionalSettingsOverrides() {
        return new JsonObject().put("orgUnits", new JsonObject().put("enabled", true))
                .put("applications", new JsonObject().put("allowUserAuthoredDependencies", true));
    }

    @BeforeEach
    void units() {
        Response created = send(HttpMethod.POST, "/v1/ops/publication/create", null, """
                {"targetFolder": "public/Acme/", "resources": [], "rules": [{"source": "title", "function": "EQUAL", "targets": ["Manager"]}]}
                """, "authorization", "admin");
        assertEquals(200, created.status(), created.body());
        assertEquals(200, send(HttpMethod.POST, "/v1/ops/publication/approve", null,
                new JsonObject().put("url", new JsonObject(created.body()).getString("url")).encode(), "authorization", "admin").status());
        assertEquals(200, send(HttpMethod.POST, "/v1/org-units/public", null, "{\"name\": \"Acme\"}", "authorization", "admin").status());
        assertEquals(200, send(HttpMethod.POST, "/v1/org-units/public/Acme", null, "{\"name\": \"Risk\"}", "authorization", "admin").status());
        assign("public/Acme", "editor", "ed");
        assertEquals(200, upload(HttpMethod.PUT, UNIT_FILE, null, "r", "authorization", "admin").status());

        verify(send(HttpMethod.PUT, "/v1/" + APP, null, """
                {
                  "endpoint": "http://localhost:4850/chat/completions",
                  "display_name": "Dependency Units App",
                  "resource_dependencies": [
                    {"kind": "dial.resourceLink", "link_id": "lnk", "target": {"url": "files/public/"}, "access": ["read"]}
                  ]
                }
                """, "authorization", "admin", "If-None-Match", "*"), 200);
        verify(send(HttpMethod.POST, "/v1/admin-consent/" + APP, null, reviewedResources(APP), "authorization", "admin"), 200);
    }

    @Test
    void testFolderSpanningAnUnreadableUnitIsNotGranted() {
        assertEquals(403, send(HttpMethod.GET, UNIT_FILE, null, null, "authorization", "azp:ed").status(), "no role in Risk");

        AtomicReference<Integer> unitFile = new AtomicReference<>();
        List<String> denied = captureAuditEvents(APP, () -> unitFile.set(readThroughTheApp("authorization", "azp:ed")));
        assertEquals(403, unitFile.get(), "the folder spans Risk, which the caller cannot read");
        assertOnly("event=resource_dependency_denial", denied);

        // with read in every unit the folder resolves
        assign("public/Acme/Risk", "viewer", "ed");
        assertOnly("event=resource_dependency_grant", captureAuditEvents(APP, () -> readThroughTheApp("authorization", "azp:ed")));
    }

    @Test
    void testChainedHopJudgesTheUnitsByTheInitiator() {
        ApiKeyData orchestratorKey = new ApiKeyData();
        orchestratorKey.setExtractedClaims(createWorkloadClaims("ed"));
        orchestratorKey.setSourceDeployment("testapp");
        orchestratorKey.setTraceId("trace-id");
        orchestratorKey.setExecutionPath(List.of("orchestrator"));
        apiKeyStore.assignPerRequestApiKey(orchestratorKey);
        String key = orchestratorKey.getPerRequestKey();

        AtomicReference<Integer> unitFile = new AtomicReference<>();
        List<String> denied = captureAuditEvents(APP, () -> unitFile.set(readThroughTheApp("api-key", key)));
        assertEquals(403, unitFile.get());
        assertOnly("event=resource_dependency_denial", denied);

        // the hop reads with the initiator's unit roles, not the calling key's
        assign("public/Acme/Risk", "viewer", "ed");
        assertOnly("event=resource_dependency_grant", captureAuditEvents(APP, () -> readThroughTheApp("api-key", key)));
    }

    @Test
    void testUnitEditorEndpointChangeDropsApproval() {
        String app = "applications/public/Acme/edited-app";
        String body = """
                {
                  "endpoint": "%s",
                  "display_name": "Edited App",
                  "resource_dependencies": [
                    {"kind": "dial.resourceLink", "link_id": "lnk", "target": {"url": "files/public/"}, "access": ["read"]}
                  ]
                }
                """;
        verify(send(HttpMethod.PUT, "/v1/" + app, null, body.formatted("http://localhost:4850/chat/completions"),
                "authorization", "admin", "If-None-Match", "*"), 200);
        verify(send(HttpMethod.POST, "/v1/admin-consent/" + app, null, reviewedResources(app), "authorization", "admin"), 200);

        // an administrator's endpoint change keeps the approval
        verify(send(HttpMethod.PUT, "/v1/" + app, null, body.formatted("http://localhost:4851/chat/completions"),
                "authorization", "admin"), 200);
        assertConsented(app, true);

        // a non-admin changing the endpoint drops the approval (routing-field protection)
        verify(send(HttpMethod.PUT, "/v1/" + app, null, body.formatted("http://editor.example/chat/completions"),
                "authorization", "azp:ed"), 200);
        assertConsented(app, false);
    }

    @Test
    void testUnitEditorNonRoutingEditKeepsApproval() {
        String app = "applications/public/Acme/non-routing-edit-app";
        String declaring = """
                {
                  "endpoint": "http://localhost:4850/chat/completions",
                  "display_name": "Original Name",
                  "resource_dependencies": [
                    {"kind": "dial.resourceLink", "link_id": "lnk", "target": {"url": "files/public/"}, "access": ["read"]}
                  ]
                }
                """;
        verify(send(HttpMethod.PUT, "/v1/" + app, null, declaring, "authorization", "admin", "If-None-Match", "*"), 200);
        verify(send(HttpMethod.POST, "/v1/admin-consent/" + app, null, reviewedResources(app), "authorization", "admin"), 200);

        // editor changes only display_name (no routing fields, no list) — approval survives
        verify(send(HttpMethod.PUT, "/v1/" + app, null, """
                {"endpoint": "http://localhost:4850/chat/completions", "display_name": "Renamed App"}
                """, "authorization", "azp:ed"), 200);
        assertConsented(app, true);
    }

    @Test
    void testUnitEditorOmittingTheListInheritsItAndKeepsApproval() {
        String app = "applications/public/Acme/delisted-app";
        String declaring = """
                {
                  "endpoint": "http://localhost:4850/chat/completions",
                  "display_name": "Delisted App",
                  "resource_dependencies": [
                    {"kind": "dial.resourceLink", "link_id": "lnk", "target": {"url": "files/public/"}, "access": ["read"]}
                  ]
                }
                """;
        verify(send(HttpMethod.PUT, "/v1/" + app, null, declaring, "authorization", "admin", "If-None-Match", "*"), 200);
        verify(send(HttpMethod.POST, "/v1/admin-consent/" + app, null, reviewedResources(app), "authorization", "admin"), 200);

        // editor omits resource_dependencies and keeps the endpoint: the stored list is inherited, approval survives
        verify(send(HttpMethod.PUT, "/v1/" + app, null, """
                {"endpoint": "http://localhost:4850/chat/completions", "display_name": "Delisted App"}
                """, "authorization", "azp:ed"), 200);
        assertConsented(app, true);
    }

    private void assertConsented(String app, boolean consented) {
        Response status = send(HttpMethod.GET, "/v1/admin-consent/" + app, null, "", "authorization", "admin");
        verify(status, 200);
        assertEquals(consented, new JsonObject(status.body()).getBoolean("consented"), () -> "Body: " + status.body());
    }

    /** Calls the app; returns what its upstream got reading the unit file with the key it received. */
    private Integer readThroughTheApp(String header, String credential) {
        AtomicReference<Integer> unitFile = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4850)) {
            server.map(HttpMethod.POST, "/chat/completions", request -> {
                unitFile.set(send(HttpMethod.GET, UNIT_FILE, null, null, "api-key", request.getHeader("Api-Key")).status());
                return TestWebServer.createResponse(200, "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"choices\":[]}",
                        "Content-Type", "application/json");
            });
            Response completion = send(HttpMethod.POST, "/openai/deployments/%s/chat/completions".formatted(APP), null,
                    "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}", header, credential, "Content-Type", "application/json");
            assertEquals(200, completion.status(), completion.body());
        }
        return unitFile.get();
    }

    private static void assertOnly(String event, List<String> events) {
        assertEquals(1, events.size(), events::toString);
        assertTrue(events.get(0).startsWith(event) && events.get(0).contains("targets=files/public/"), events::toString);
    }

    private void assign(String unit, String roleName, String azp) {
        Response response = send(HttpMethod.POST, "/v1/org-units/" + unit + "/role-assignments", null,
                "{\"roleName\": \"%s\", \"subject\": {\"type\": \"claimsPredicate\", \"source\": \"azp\", \"function\": \"EQUAL\", \"targets\": [\"%s\"]}}"
                        .formatted(roleName, azp), "authorization", "admin");
        assertEquals(200, response.status(), response.body());
    }

    /** allowUserAuthoredDependencies is off: a unit editor may not introduce or change the list, but may omit it. */
    public static class FlagOff extends ResourceBaseTest {

        private static final String APP = "applications/public/Acme/flag-off-app";
        private static final String DECLARING_BODY = """
                {
                  "endpoint": "http://localhost:4850/chat/completions",
                  "display_name": "Flag Off App",
                  "resource_dependencies": [
                    {"kind": "dial.resourceLink", "link_id": "lnk", "target": {"url": "files/public/"}, "access": ["read"]}
                  ]
                }
                """;

        @Override
        protected JsonObject additionalSettingsOverrides() {
            return new JsonObject()
                    .put("orgUnits", new JsonObject().put("enabled", true));
        }

        @BeforeEach
        void setup() {
            Response created = send(HttpMethod.POST, "/v1/ops/publication/create", null, """
                    {"targetFolder": "public/Acme/", "resources": [], "rules": [{"source": "title", "function": "EQUAL", "targets": ["Manager"]}]}
                    """, "authorization", "admin");
            assertEquals(200, created.status(), created.body());
            assertEquals(200, send(HttpMethod.POST, "/v1/ops/publication/approve", null,
                    new JsonObject().put("url", new JsonObject(created.body()).getString("url")).encode(), "authorization", "admin").status());
            assertEquals(200, send(HttpMethod.POST, "/v1/org-units/public", null, "{\"name\": \"Acme\"}", "authorization", "admin").status());
            Response assignment = send(HttpMethod.POST, "/v1/org-units/public/Acme/role-assignments", null,
                    "{\"roleName\": \"editor\", \"subject\": {\"type\": \"claimsPredicate\", \"source\": \"azp\", \"function\": \"EQUAL\", \"targets\": [\"ed\"]}}",
                    "authorization", "admin");
            assertEquals(200, assignment.status(), assignment.body());

            verify(send(HttpMethod.PUT, "/v1/" + APP, null, DECLARING_BODY, "authorization", "admin", "If-None-Match", "*"), 200);
            String reviewed = "{\"resources\": [{\"url\": \"files/public/\", \"access\": [\"READ\"]}]}";
            verify(send(HttpMethod.POST, "/v1/admin-consent/" + APP, null, reviewed, "authorization", "admin"), 200);
        }

        @Test
        void testEditorWithDifferentListIsRejected() {
            String differentList = DECLARING_BODY.replace("files/public/", "prompts/public/");
            verify(send(HttpMethod.PUT, "/v1/" + APP, null, differentList, "authorization", "azp:ed"), 403);
        }

        @Test
        void testEditorOmittingTheListInheritsItAndKeepsApproval() {
            verify(send(HttpMethod.PUT, "/v1/" + APP, null, """
                    {"endpoint": "http://localhost:4850/chat/completions", "display_name": "Flag Off App"}
                    """, "authorization", "azp:ed"), 200);
            Response status = send(HttpMethod.GET, "/v1/admin-consent/" + APP, null, "", "authorization", "admin");
            verify(status, 200);
            assertEquals(true, new JsonObject(status.body()).getBoolean("consented"), status.body());
        }
    }
}
