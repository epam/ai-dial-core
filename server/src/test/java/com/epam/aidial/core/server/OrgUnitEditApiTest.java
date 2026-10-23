package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Roles at a unit editing its content: "azp:ed" is an editor and "azp:boss" an admin of Acme; the read rule of Acme
 * lets neither in, nor "azp:other", who holds nothing.
 */
public class OrgUnitEditApiTest extends ResourceBaseTest {

    private static final String EDITOR = "azp:ed";
    private static final String ADMIN = "azp:boss";
    private static final String OTHER = "azp:other";

    @Override
    protected JsonObject additionalSettingsOverrides() {
        return new JsonObject().put("orgUnits", new JsonObject().put("enabled", true));
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
        assign("editor", "ed");
        assign("admin", "boss");
        assertEquals(200, upload(HttpMethod.PUT, "/v1/files/public/Acme/Risk/r.txt", null, "r", "authorization", "admin").status());
    }

    @Test
    void testEditorsCreateChangeAndDeleteInTheirUnit() {
        for (String caller : List.of(EDITOR, ADMIN)) {
            assertEquals(200, upload(HttpMethod.PUT, "/v1/files/public/Acme/a.txt", null, "a", "authorization", caller).status(), caller);
            Response read = send(HttpMethod.GET, "/v1/files/public/Acme/a.txt", null, null, "authorization", caller);
            assertEquals(200, read.status(), caller);
            assertEquals("a", read.body());
            assertEquals(200, upload(HttpMethod.PUT, "/v1/files/public/Acme/a.txt", null, "b", "authorization", caller).status(), caller);
            Response prompt = send(HttpMethod.PUT, "/v1/prompts/public/Acme/p", null,
                    "{\"id\": \"p\", \"name\": \"p\", \"folderId\": \"f\", \"content\": \"c\"}", "authorization", caller);
            assertEquals(200, prompt.status(), prompt.body());
            assertEquals(200, send(HttpMethod.GET, "/v1/prompts/public/Acme/p", null, null, "authorization", caller).status(), caller);
            assertEquals(200, send(HttpMethod.DELETE, "/v1/files/public/Acme/a.txt", null, null, "authorization", caller).status(), caller);
            assertEquals(200, send(HttpMethod.DELETE, "/v1/prompts/public/Acme/p", null, null, "authorization", caller).status(), caller);
        }
        assertEquals(404, send(HttpMethod.GET, "/v1/files/public/Acme/a.txt", null, null, "authorization", "admin").status());
    }

    @Test
    void testNothingOutsideTheUnit() {
        assertEquals(200, upload(HttpMethod.PUT, "/v1/files/public/Acme/a.txt", null, "a", "authorization", "admin").status());
        assertEquals(200, upload(HttpMethod.PUT, "/v1/files/public/x.txt", null, "x", "authorization", "admin").status());

        for (String caller : List.of(EDITOR, ADMIN)) {
            assertEquals(403, send(HttpMethod.GET, "/v1/files/public/Acme/Risk/r.txt", null, null, "authorization", caller).status(), caller);
            assertEquals(403, upload(HttpMethod.PUT, "/v1/files/public/Acme/Risk/r.txt", null, "w", "authorization", caller).status(), caller);
            assertEquals(403, send(HttpMethod.DELETE, "/v1/files/public/Acme/Risk/r.txt", null, null, "authorization", caller).status(), caller);
            assertEquals(403, upload(HttpMethod.PUT, "/v1/files/public/Beta/b.txt", null, "b", "authorization", caller).status(), caller);
            assertEquals(403, upload(HttpMethod.PUT, "/v1/files/public/x.txt", null, "w", "authorization", caller).status(), caller);
            assertEquals(403, upload(HttpMethod.PUT, "/v1/files/public/acme/a.txt", null, "w", "authorization", caller).status(), caller);
        }
        for (String caller : List.of(OTHER, "azp:nobody")) {
            assertEquals(403, send(HttpMethod.GET, "/v1/files/public/Acme/a.txt", null, null, "authorization", caller).status(), caller);
            assertEquals(403, upload(HttpMethod.PUT, "/v1/files/public/Acme/a.txt", null, "w", "authorization", caller).status(), caller);
            assertEquals(403, send(HttpMethod.DELETE, "/v1/files/public/Acme/a.txt", null, null, "authorization", caller).status(), caller);
        }
        assertEquals("r", send(HttpMethod.GET, "/v1/files/public/Acme/Risk/r.txt", null, null, "authorization", "admin").body());
        assertEquals("a", send(HttpMethod.GET, "/v1/files/public/Acme/a.txt", null, null, "authorization", "admin").body());
    }

    @Test
    void testFoldersInChildUnitsTakeOnlyTheirRoles() {
        assign("public/Acme/Risk", "editor", "risk");
        assertEquals(200, upload(HttpMethod.PUT, "/v1/files/public/Acme/Risk/F1/x.txt", null, "x", "authorization", "admin").status());

        for (String caller : List.of(EDITOR, ADMIN)) {
            assertEquals(403, send(HttpMethod.GET, "/v1/files/public/Acme/Risk/F1/x.txt", null, null, "authorization", caller).status(), caller);
            assertEquals(403, upload(HttpMethod.PUT, "/v1/files/public/Acme/Risk/F1/x.txt", null, "w", "authorization", caller).status(), caller);
            assertEquals(403, upload(HttpMethod.PUT, "/v1/files/public/Acme/Risk/F1/y.txt", null, "y", "authorization", caller).status(), caller);
            assertEquals(403, send(HttpMethod.DELETE, "/v1/files/public/Acme/Risk/F1/x.txt", null, null, "authorization", caller).status(), caller);
        }

        String riskEditor = "azp:risk";
        assertEquals("x", send(HttpMethod.GET, "/v1/files/public/Acme/Risk/F1/x.txt", null, null, "authorization", riskEditor).body());
        assertEquals(200, upload(HttpMethod.PUT, "/v1/files/public/Acme/Risk/F1/y.txt", null, "y", "authorization", riskEditor).status());
        assertEquals(200, send(HttpMethod.DELETE, "/v1/files/public/Acme/Risk/F1/x.txt", null, null, "authorization", riskEditor).status());
        assertEquals(403, upload(HttpMethod.PUT, "/v1/files/public/Acme/a.txt", null, "a", "authorization", riskEditor).status());
    }

    @Test
    void testListingsShowWhatTheRolesRead() {
        assertEquals(200, upload(HttpMethod.PUT, "/v1/files/public/Acme/a.txt", null, "a", "authorization", "admin").status());

        assertTrue(names("files/public/", EDITOR).contains("Acme"));
        List<String> acme = names("files/public/Acme/", EDITOR);
        assertTrue(acme.contains("a.txt"), acme.toString());
        assertEquals(403, send(HttpMethod.GET, "/v1/metadata/files/public/Acme/Risk/", null, null, "authorization", EDITOR).status());
        assertFalse(names("files/public/", OTHER).contains("Acme"));
        assertEquals(403, send(HttpMethod.GET, "/v1/metadata/files/public/Acme/", null, null, "authorization", OTHER).status());
    }

    @Test
    void testMovesAndCopiesStayInsideTheUnit() {
        assertEquals(200, upload(HttpMethod.PUT, "/v1/files/public/Acme/a.txt", null, "a", "authorization", "admin").status());

        assertEquals(200, operation("move", "files/public/Acme/a.txt", "files/public/Acme/b.txt", EDITOR).status());
        assertEquals(200, operation("copy", "files/public/Acme/b.txt", "files/public/Acme/c.txt", EDITOR).status());
        assertEquals(403, operation("move", "files/public/Acme/b.txt", "files/public/Acme/Risk/b.txt", EDITOR).status());
        assertEquals(403, operation("copy", "files/public/Acme/b.txt", "files/public/b.txt", EDITOR).status());
        assertEquals(403, operation("copy", "files/public/Acme/Risk/r.txt", "files/public/Acme/r.txt", EDITOR).status());
        assertEquals("a", send(HttpMethod.GET, "/v1/files/public/Acme/c.txt", null, null, "authorization", "admin").body());
    }

    @Test
    void testRecursiveListingsLeaveChildUnitsOut() {
        assertEquals(200, upload(HttpMethod.PUT, "/v1/files/public/Acme/a.txt", null, "a", "authorization", "admin").status());
        assertEquals(200, upload(HttpMethod.PUT, "/v1/files/public/Acme/F/b.txt", null, "b", "authorization", "admin").status());

        Response response = send(HttpMethod.GET, "/v1/metadata/files/public/Acme/?recursive=true", null, null, "authorization", EDITOR);
        assertEquals(200, response.status(), response.body());
        List<String> urls = new ArrayList<>();
        JsonArray items = new JsonObject(response.body()).getJsonArray("items");
        for (int i = 0; i < items.size(); i++) {
            urls.add(items.getJsonObject(i).getString("url"));
        }
        assertTrue(urls.contains("files/public/Acme/a.txt"), urls.toString());
        assertTrue(urls.contains("files/public/Acme/F/b.txt"), urls.toString());
        assertTrue(urls.stream().noneMatch(url -> url.startsWith("files/public/Acme/Risk/")), urls.toString());
    }

    @Test
    void testCopiesDuplicateOnlyFilesTheCallerReads() {
        String schemaId = putBadgeSchema();
        assertEquals(200, upload(HttpMethod.PUT, "/v1/files/public/Acme/badge.png", null, "badge", "authorization", "admin").status());

        assertEquals(200, putBadgedApp("x", schemaId, "files/public/Acme/Risk/r.txt").status());
        Response refused = operation("copy", "applications/public/Acme/x", "applications/public/Acme/y", EDITOR);
        assertEquals(403, refused.status(), refused.body());
        assertEquals(404, send(HttpMethod.GET, "/v1/applications/public/Acme/y", null, null, "authorization", "admin").status());
        assertEquals(404, send(HttpMethod.GET, "/v1/files/public/Acme/.y/r.txt", null, null, "authorization", "admin").status());

        assertEquals(200, putBadgedApp("z", schemaId, "files/public/Acme/badge.png").status());
        Response copied = operation("copy", "applications/public/Acme/z", "applications/public/Acme/w", EDITOR);
        assertEquals(200, copied.status(), copied.body());
        assertEquals("badge", send(HttpMethod.GET, "/v1/files/public/Acme/.w/badge.png", null, null, "authorization", EDITOR).body());
    }

    @Test
    void testCopiesDontDuplicateFoldersReadOnlyThroughRoles() {
        String schemaId = putBadgeSchema();
        assertEquals(200, putBadgedApp("x", schemaId, "files/public/Acme/").status());

        Response refused = operation("copy", "applications/public/Acme/x", "applications/public/Acme/y", EDITOR);
        assertEquals(403, refused.status(), refused.body());
        assertEquals(404, send(HttpMethod.GET, "/v1/applications/public/Acme/y", null, null, "authorization", "admin").status());
        assertEquals(404, send(HttpMethod.GET, "/v1/files/public/Acme/.y/Acme/Risk/r.txt", null, null, "authorization", "admin").status());
        assertEquals(404, send(HttpMethod.GET, "/v1/metadata/files/public/Acme/.y/", null, null, "authorization", "admin").status());

        // the rules let everyone read the root, but not what's below it
        assertEquals(200, putBadgedApp("z", schemaId, "files/public/").status());
        Response root = operation("copy", "applications/public/Acme/z", "applications/public/Acme/w", EDITOR);
        assertEquals(403, root.status(), root.body());
        assertEquals(404, send(HttpMethod.GET, "/v1/metadata/files/public/Acme/.w/", null, null, "authorization", "admin").status());
    }

    @Test
    void testFolderAttachmentsNeedReadInEveryUnitTheySpan() {
        assertEquals(200, upload(HttpMethod.PUT, "/v1/files/public/Acme/a.txt", null, "a", "authorization", "admin").status());
        assertEquals(200, upload(HttpMethod.PUT, "/v1/files/public/Acme/Docs/d.txt", null, "d", "authorization", "admin").status());
        String answer = "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"gpt-3-turbo\","
                + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}]}";
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/chat/completions", request ->
                    TestWebServer.createResponse(200, answer, "Content-Type", "application/json"));

            Response file = chatWithAttachment("files/public/Acme/a.txt");
            assertEquals(200, file.status(), file.body());
            Response plain = chatWithAttachment("files/public/Acme/Docs/");
            assertEquals(200, plain.status(), plain.body());

            Response spanning = chatWithAttachment("files/public/Acme/");
            assertEquals(403, spanning.status(), "Acme/ holds Risk, where the editor has no role");
            assertTrue(spanning.body().contains("Access denied to the file"), spanning.body());
            assertEquals(403, chatWithAttachment("files/public/").status(), "the shared space holds every unit");

            assign("public/Acme/Risk", "viewer", "ed");
            Response covered = chatWithAttachment("files/public/Acme/");
            assertEquals(200, covered.status(), covered.body());
        }
    }

    @Test
    void testApplicationsKeepTheirPlatformFields() {
        assertEquals(200, send(HttpMethod.PUT, "/v1/applications/public/Acme/app", null, """
                {"endpoint": "http://example.com/v1/completions", "display_name": "App", "forward_auth_token": true}
                """, "authorization", "admin").status());

        Response edited = send(HttpMethod.PUT, "/v1/applications/public/Acme/app", null, """
                {"endpoint": "http://example.com/v2/completions", "display_name": "App 2", "forward_auth_token": false}
                """, "authorization", EDITOR);
        assertEquals(200, edited.status(), edited.body());
        JsonObject stored = new JsonObject(send(HttpMethod.GET, "/v1/applications/public/Acme/app", null, null, "authorization", "admin").body());
        assertEquals("http://example.com/v2/completions", stored.getString("endpoint"));
        assertEquals(true, stored.getBoolean("forward_auth_token"), stored.encode());

        Response created = send(HttpMethod.PUT, "/v1/applications/public/Acme/new-app", null, """
                {"endpoint": "http://example.com/v1/completions", "display_name": "New", "forward_auth_token": true}
                """, "authorization", EDITOR);
        assertEquals(200, created.status(), created.body());
        JsonObject fresh = new JsonObject(send(HttpMethod.GET, "/v1/applications/public/Acme/new-app", null, null, "authorization", "admin").body());
        assertFalse(fresh.getBoolean("forward_auth_token", false), fresh.encode());
    }

    @Test
    void testCopiesAndMovesDontForwardTokens() {
        assertEquals(200, send(HttpMethod.PUT, "/v1/applications/public/Acme/app", null, """
                {"endpoint": "http://example.com/v1/completions", "display_name": "App", "forward_auth_token": true}
                """, "authorization", "admin").status());

        assertEquals(200, operation("copy", "applications/public/Acme/app", "applications/public/Acme/admin-copy", "admin").status());
        assertTrue(new JsonObject(send(HttpMethod.GET, "/v1/applications/public/Acme/admin-copy", null, null, "authorization", "admin").body())
                .getBoolean("forward_auth_token", false));

        Response copied = operation("copy", "applications/public/Acme/app", "applications/public/Acme/copy", EDITOR);
        assertEquals(200, copied.status(), copied.body());
        Response moved = operation("move", "applications/public/Acme/admin-copy", "applications/public/Acme/moved", EDITOR);
        assertEquals(200, moved.status(), moved.body());
        for (String name : List.of("copy", "moved")) {
            JsonObject stored = new JsonObject(send(HttpMethod.GET, "/v1/applications/public/Acme/" + name, null, null, "authorization", "admin").body());
            assertFalse(stored.getBoolean("forward_auth_token", false), stored.encode());
        }
    }

    @Test
    void testCodeAppOperationsStayWithAdmins() {
        assertEquals(200, send(HttpMethod.PUT, "/v1/applications/public/Acme/app", null, """
                {"endpoint": "http://example.com/v1/completions", "display_name": "App"}
                """, "authorization", "admin").status());
        String body = new JsonObject().put("url", "applications/public/Acme/app").encode();
        for (String operation : List.of("deploy", "undeploy", "redeploy", "logs")) {
            Response refused = send(HttpMethod.POST, "/v1/ops/application/" + operation, null, body, "authorization", ADMIN);
            assertEquals(403, refused.status(), operation + " " + refused.body());
        }
    }

    @Test
    @DialConfigLocation("dial-config/org-unit-roles.json")
    void testConfiguredRolesGrantWhatTheySay() {
        assign("auditor", "aud");
        assertEquals(200, upload(HttpMethod.PUT, "/v1/files/public/Acme/a.txt", null, "a", "authorization", "admin").status());

        assertEquals(200, send(HttpMethod.GET, "/v1/files/public/Acme/a.txt", null, null, "authorization", "azp:aud").status());
        assertEquals(403, upload(HttpMethod.PUT, "/v1/files/public/Acme/a.txt", null, "w", "authorization", "azp:aud").status());
        assertEquals(403, send(HttpMethod.DELETE, "/v1/files/public/Acme/a.txt", null, null, "authorization", "azp:aud").status());
        Response prompt = send(HttpMethod.PUT, "/v1/prompts/public/Acme/p", null,
                "{\"id\": \"p\", \"name\": \"p\", \"folderId\": \"f\", \"content\": \"c\"}", "authorization", EDITOR);
        assertEquals(403, prompt.status(), "the configured editor changes files only");
        assertEquals(200, upload(HttpMethod.PUT, "/v1/files/public/Acme/b.txt", null, "b", "authorization", EDITOR).status());
    }

    private void assign(String roleName, String azp) {
        assign("public/Acme", roleName, azp);
    }

    private void assign(String unit, String roleName, String azp) {
        Response response = send(HttpMethod.POST, "/v1/org-units/" + unit + "/role-assignments", null,
                "{\"roleName\": \"%s\", \"subject\": {\"type\": \"claimsPredicate\", \"source\": \"azp\", \"function\": \"EQUAL\", \"targets\": [\"%s\"]}}"
                        .formatted(roleName, azp), "authorization", "admin");
        assertEquals(200, response.status(), response.body());
    }

    private Response operation(String name, String source, String destination, String caller) {
        return send(HttpMethod.POST, "/v1/ops/resource/" + name, null,
                new JsonObject().put("sourceUrl", source).put("destinationUrl", destination).encode(), "authorization", caller);
    }

    private String putBadgeSchema() {
        String schemaId = "https://dial.epam.com/catalog-schemas/org-unit-edit";
        Response schema = send(HttpMethod.PUT, "/v1/catalog_schemas/platform/org-unit-edit", null, """
                {"$schema": "https://dial.epam.com/catalog_schemas/schema#", "$id": "%s",
                 "dial:catalogEntityType": "agent", "dial:catalogDisplayName": "Agent", "type": "object",
                 "properties": {"badge": {"type": "string", "format": "dial-file-encoded", "dial:file": true}}}
                """.formatted(schemaId), "authorization", "admin", "If-None-Match", "*");
        assertEquals(200, schema.status(), schema.body());
        return schemaId;
    }

    private Response putBadgedApp(String name, String schemaId, String badge) {
        return send(HttpMethod.PUT, "/v1/applications/public/Acme/" + name, null, """
                {"endpoint": "http://example.com/v1/completions", "display_name": "App",
                 "catalog_schema_id": "%s", "catalog_properties": {"badge": "%s"}}
                """.formatted(schemaId, badge), "authorization", EDITOR);
    }

    private Response chatWithAttachment(String url) {
        String body = new JsonObject().put("messages", new JsonArray().add(new JsonObject()
                .put("role", "user").put("content", "hi")
                .put("custom_content", new JsonObject().put("attachments", new JsonArray()
                        .add(new JsonObject().put("type", "text/plain").put("url", url)))))).encode();
        return send(HttpMethod.POST, "/openai/deployments/gpt-3-turbo/chat/completions", null, body,
                "authorization", EDITOR, "content-type", "application/json");
    }

    private List<String> names(String folderUrl, String caller) {
        Response response = send(HttpMethod.GET, "/v1/metadata/" + folderUrl, null, null, "authorization", caller);
        assertEquals(200, response.status(), response.body());
        List<String> names = new ArrayList<>();
        JsonArray items = new JsonObject(response.body()).getJsonArray("items");
        for (int i = 0; i < items.size(); i++) {
            names.add(items.getJsonObject(i).getString("name"));
        }
        return names;
    }
}
