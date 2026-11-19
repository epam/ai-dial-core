package com.epam.aidial.core.server;

import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.permission.PerRequestSharedData;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An API key acts through what a subject on {@code roles} its configured roles match, as a person does; an
 * application's per-request key only reads what its user's roles read.
 */
public class OrgUnitKeysApiTest extends ResourceBaseTest {

    private static final String DEFAULT_ROLE_SUBJECT = "{\"type\": \"claimsPredicate\", \"source\": \"roles\", \"function\": \"EQUAL\", \"targets\": [\"default\"]}";

    @Override
    protected JsonObject additionalSettingsOverrides() {
        return new JsonObject().put("orgUnits", new JsonObject().put("enabled", true));
    }

    @BeforeEach
    void unit() {
        assertEquals(200, send(HttpMethod.POST, "/v1/org-units/public", null, "{\"name\": \"Acme\"}", "authorization", "admin").status());
        assertEquals(200, upload(HttpMethod.PUT, "/v1/files/public/Acme/a.txt", null, "a", "authorization", "admin").status());
    }

    @Test
    void testApplicationsReadThroughTheirUsersRoles() {
        String key = perRequestKeyFor("alice");
        assertEquals(403, send(HttpMethod.GET, "/v1/files/public/Acme/a.txt", null, null, "api-key", key).status());

        assign("editor", "{\"type\": \"claimsPredicate\", \"source\": \"azp\", \"function\": \"EQUAL\", \"targets\": [\"alice\"]}");
        Response read = send(HttpMethod.GET, "/v1/files/public/Acme/a.txt", null, null, "api-key", key);
        assertEquals(200, read.status(), read.body());
        assertEquals("a", read.body());
        assertEquals(403, upload(HttpMethod.PUT, "/v1/files/public/Acme/b.txt", null, "b", "api-key", key).status());
        assertEquals(403, send(HttpMethod.GET, "/v1/files/public/Acme/a.txt", null, null, "api-key", perRequestKeyFor("bob")).status());
    }

    @Test
    void testApiKeysWriteThroughTheirConfiguredRoles() {
        assertEquals(403, send(HttpMethod.GET, "/v1/files/public/Acme/a.txt", null, null, "api-key", "proxyKey1").status());
        assertEquals(403, upload(HttpMethod.PUT, "/v1/files/public/Acme/b.txt", null, "b", "api-key", "proxyKey1").status());

        assign("editor", DEFAULT_ROLE_SUBJECT);
        assertEquals(200, send(HttpMethod.GET, "/v1/files/public/Acme/a.txt", null, null, "api-key", "proxyKey1").status());
        Response written = upload(HttpMethod.PUT, "/v1/files/public/Acme/b.txt", null, "b", "api-key", "proxyKey1");
        assertEquals(200, written.status(), written.body());
        assertEquals(403, upload(HttpMethod.PUT, "/v1/files/public/Acme/c.txt", null, "c", "api-key", "vstore_user_key").status(),
                "a key with another role");
    }

    @Test
    void testApiKeysApproveIntoTheirUnit() {
        assign("admin", DEFAULT_ROLE_SUBJECT);
        assign("viewer", "{\"type\": \"claimsPredicate\", \"source\": \"roles\", \"function\": \"EQUAL\", \"targets\": [\"user\"]}");
        String url = requestPublication("p.txt");

        Response list = send(HttpMethod.POST, "/v1/ops/publication/list", null, "{\"url\": \"publications/public/\"}", "api-key", "proxyKey1");
        assertEquals(200, list.status(), list.body());
        assertEquals(url, new JsonObject(list.body()).getJsonArray("publications").getJsonObject(0).getString("url"));
        Response publication = decide("get", url, "proxyKey1");
        assertEquals(200, publication.status(), publication.body());
        String reviewUrl = new JsonObject(publication.body()).getJsonArray("resources").getJsonObject(0).getString("reviewUrl");
        Response review = send(HttpMethod.GET, "/v1/" + reviewUrl, null, null, "api-key", "proxyKey1");
        assertEquals(200, review.status(), review.body());
        Response approved = decide("approve", url, "proxyKey1");
        assertEquals(200, approved.status(), approved.body());
        assertEquals("APPROVED", new JsonObject(approved.body()).getString("status"));

        Response rejected = decide("reject", requestPublication("q.txt"), "proxyKey1");
        assertEquals(200, rejected.status(), rejected.body());
        assertEquals("REJECTED", new JsonObject(rejected.body()).getString("status"));
    }

    @Test
    void testApiKeysManageRoleAssignments() {
        String path = "/v1/org-units/public/Acme/role-assignments";
        assertEquals(403, send(HttpMethod.GET, path, null, null, "api-key", "proxyKey1").status());

        assign("admin", DEFAULT_ROLE_SUBJECT);
        assertEquals(200, send(HttpMethod.GET, path, null, null, "api-key", "proxyKey1").status());
        Response added = send(HttpMethod.POST, path, null, "{\"roleName\": \"viewer\", \"subject\": "
                + "{\"type\": \"claimsPredicate\", \"source\": \"email\", \"function\": \"EQUAL\", \"targets\": [\"jane@acme.example\"]}}",
                "api-key", "proxyKey1");
        assertEquals(200, added.status(), added.body());
        Response removed = send(HttpMethod.DELETE, path + "/" + new JsonObject(added.body()).getString("id"), null, null, "api-key", "proxyKey1");
        assertEquals(200, removed.status(), removed.body());
    }

    @Test
    void testApiKeysListTheirUnitsAndTheUnitsAbove() {
        assertEquals(200, send(HttpMethod.POST, "/v1/org-units/public/Acme", null, "{\"name\": \"Risk\"}", "authorization", "admin").status());
        Response assigned = send(HttpMethod.POST, "/v1/org-units/public/Acme/Risk/role-assignments", null,
                "{\"roleName\": \"viewer\", \"subject\": %s}".formatted(DEFAULT_ROLE_SUBJECT), "authorization", "admin");
        assertEquals(200, assigned.status(), assigned.body());

        Response units = send(HttpMethod.GET, "/v1/user/role-assignments", null, null, "api-key", "proxyKey1");
        assertEquals(200, units.status(), units.body());
        JsonArray items = new JsonObject(units.body()).getJsonArray("items");
        assertEquals(2, items.size(), units.body());
        assertEquals("public/Acme", items.getJsonObject(0).getString("path"));
        assertEquals(List.of(), items.getJsonObject(0).getJsonArray("roles").getList());
        assertEquals("public/Acme/Risk", items.getJsonObject(1).getString("path"));
        assertEquals(List.of("viewer"), items.getJsonObject(1).getJsonArray("roles").getList());

        Response listing = send(HttpMethod.GET, "/v1/metadata/files/public/", null, null, "api-key", "proxyKey1");
        assertEquals(200, listing.status(), listing.body());
        assertTrue(listing.body().contains("\"files/public/Acme/\""), listing.body());
    }

    @Test
    void testApplicationsNeitherApproveNorManageNorSeeUnitsAbove() {
        assertEquals(200, send(HttpMethod.POST, "/v1/org-units/public/Acme", null, "{\"name\": \"Risk\"}", "authorization", "admin").status());
        String alice = "{\"type\": \"claimsPredicate\", \"source\": \"azp\", \"function\": \"EQUAL\", \"targets\": [\"alice\"]}";
        assign("admin", alice);
        assign("viewer", "{\"type\": \"claimsPredicate\", \"source\": \"roles\", \"function\": \"EQUAL\", \"targets\": [\"user\"]}");
        Response belowAssigned = send(HttpMethod.POST, "/v1/org-units/public/Acme/Risk/role-assignments", null,
                "{\"roleName\": \"viewer\", \"subject\": %s}".formatted(alice), "authorization", "admin");
        assertEquals(200, belowAssigned.status(), belowAssigned.body());
        String url = requestPublication("p.txt");
        String key = perRequestKeyFor("alice");

        assertEquals(403, send(HttpMethod.POST, "/v1/ops/publication/list", null, "{\"url\": \"publications/public/\"}", "api-key", key).status());
        assertEquals(403, decide("get", url, key).status());
        String reviewUrl = new JsonObject(send(HttpMethod.POST, "/v1/ops/publication/get", null, new JsonObject().put("url", url).encode(),
                "authorization", "admin").body()).getJsonArray("resources").getJsonObject(0).getString("reviewUrl");
        assertEquals(403, send(HttpMethod.GET, "/v1/" + reviewUrl, null, null, "api-key", key).status());
        assertEquals(403, decide("approve", url, key).status());
        assertEquals(403, decide("reject", url, key).status());
        assertEquals(403, send(HttpMethod.GET, "/v1/org-units/public/Acme/role-assignments", null, null, "api-key", key).status());

        String bob = perRequestKeyFor("bob");
        Response bobAssigned = send(HttpMethod.POST, "/v1/org-units/public/Acme/Risk/role-assignments", null,
                "{\"roleName\": \"viewer\", \"subject\": {\"type\": \"claimsPredicate\", \"source\": \"azp\", \"function\": \"EQUAL\", "
                        + "\"targets\": [\"bob\"]}}", "authorization", "admin");
        assertEquals(200, bobAssigned.status(), bobAssigned.body());
        assertEquals(403, send(HttpMethod.GET, "/v1/metadata/files/public/Acme/", null, null, "api-key", bob).status(),
                "a role in Risk shows a person Acme, not an application");
    }

    @Test
    void testApiKeySubjectIsRefused() {
        Response response = send(HttpMethod.POST, "/v1/org-units/public/Acme/role-assignments", null,
                "{\"roleName\": \"viewer\", \"subject\": {\"type\": \"apiKey\", \"id\": \"proxyKey1\"}}", "authorization", "admin");
        assertEquals(400, response.status(), response.body());
        Response stored = send(HttpMethod.GET, "/v1/org-units/public/Acme/role-assignments", null, null, "authorization", "admin");
        assertEquals(new JsonArray(), new JsonObject(stored.body()).getJsonArray("items"));
    }

    @Test
    void testTrueMatchesEveryTokenWithTheClaim() {
        assign("viewer", "{\"type\": \"claimsPredicate\", \"source\": \"azp\", \"function\": \"TRUE\"}");
        assertEquals(200, send(HttpMethod.GET, "/v1/files/public/Acme/a.txt", null, null, "authorization", "azp:anyone").status());
        assertEquals(200, send(HttpMethod.GET, "/v1/files/public/Acme/a.txt", null, null, "api-key", perRequestKeyFor("anyone")).status());
        assertEquals(403, upload(HttpMethod.PUT, "/v1/files/public/Acme/b.txt", null, "b", "authorization", "azp:anyone").status());
        assertEquals(403, send(HttpMethod.GET, "/v1/files/public/Acme/a.txt", null, null, "api-key", "proxyKey1").status(),
                "an API key has no claims");

        assign("viewer", "{\"type\": \"claimsPredicate\", \"source\": \"roles\", \"function\": \"TRUE\"}");
        assertEquals(200, send(HttpMethod.GET, "/v1/files/public/Acme/a.txt", null, null, "api-key", "proxyKey1").status());
    }

    @Test
    void testTrueTakesNoTargets() {
        Response response = send(HttpMethod.POST, "/v1/org-units/public/Acme/role-assignments", null,
                "{\"roleName\": \"viewer\", \"subject\": {\"type\": \"claimsPredicate\", \"source\": \"azp\", \"function\": \"TRUE\", "
                        + "\"targets\": [\"x\"]}}", "authorization", "admin");
        assertEquals(400, response.status(), response.body());
    }

    @Test
    void testApplicationsHandOnlyFoldersTheirUsersReadInFull() {
        assertEquals(200, send(HttpMethod.POST, "/v1/org-units/public/Acme", null, "{\"name\": \"Child\"}", "authorization", "admin").status());
        assertEquals(200, upload(HttpMethod.PUT, "/v1/files/public/Acme/Child/c.txt", null, "c", "authorization", "admin").status());
        assertEquals(200, upload(HttpMethod.PUT, "/v1/files/public/Acme/Docs/d.txt", null, "d", "authorization", "admin").status());
        assign("viewer", "{\"type\": \"claimsPredicate\", \"source\": \"azp\", \"function\": \"EQUAL\", \"targets\": [\"alice\"]}");

        String alice = perRequestKeyFor("alice");
        assertEquals(200, grant(alice, "files/public/Acme/a.txt").status());
        assertEquals(200, grant(alice, "files/public/Acme/Docs/").status());
        assertEquals(403, grant(alice, "files/public/Acme/").status(), "Acme/ holds Child, where alice has no role");
        assertEquals(403, grant(alice, "files/public/").status());
    }

    @Test
    void testWriteOnlyFoldersPassOnOutsideUnitsAsBefore() {
        ApiKeyData key = new ApiKeyData();
        key.setExtractedClaims(createWorkloadClaims("alice"));
        key.setSourceDeployment("testapp");
        key.setTraceId("trace-id");
        Set<ResourceAccessType> write = Set.of(ResourceAccessType.WRITE);
        key.setPerRequestSharedResources(Map.of("files/%s/out/".formatted(bucket), new PerRequestSharedData(write),
                "files/public/Handbook/", new PerRequestSharedData(write), "files/public/Acme/Docs/", new PerRequestSharedData(write)));
        apiKeyStore.assignPerRequestApiKey(key);

        assertEquals(200, grant(key.getPerRequestKey(), "files/%s/out/".formatted(bucket), "WRITE").status());
        assertEquals(200, grant(key.getPerRequestKey(), "files/public/Handbook/", "WRITE").status());
        assertEquals(403, grant(key.getPerRequestKey(), "files/public/Acme/Docs/", "WRITE").status(), "Acme reads only through roles");
    }

    private Response grant(String key, String url) {
        return grant(key, url, "READ");
    }

    private Response grant(String key, String url, String permission) {
        return send(HttpMethod.POST, "/v1/ops/resource/per-request-permissions/grant", null,
                new JsonObject().put("receiver", "app2").put("resourcePermissions", new JsonArray()
                        .add(new JsonObject().put("url", url).put("permissions", new JsonArray().add(permission)))).encode(),
                "api-key", key);
    }

    private String perRequestKeyFor(String azp) {
        ApiKeyData key = new ApiKeyData();
        key.setExtractedClaims(createWorkloadClaims(azp));
        key.setSourceDeployment("testapp");
        key.setTraceId("trace-id");
        apiKeyStore.assignPerRequestApiKey(key);
        return key.getPerRequestKey();
    }

    private String requestPublication(String name) {
        String bucket = new JsonObject(send(HttpMethod.GET, "/v1/bucket", null, null, "authorization", "user").body()).getString("bucket");
        String source = "files/" + bucket + "/" + name;
        assertEquals(200, upload(HttpMethod.PUT, "/v1/" + source, null, "p", "authorization", "user").status());
        JsonObject body = new JsonObject().put("name", name).put("targetFolder", "public/Acme/")
                .put("resources", new JsonArray().add(new JsonObject().put("action", "ADD").put("sourceUrl", source)
                        .put("targetUrl", "files/public/Acme/" + name)));
        Response created = send(HttpMethod.POST, "/v1/ops/publication/create", null, body.encode(), "authorization", "user");
        assertEquals(200, created.status(), created.body());
        return new JsonObject(created.body()).getString("url");
    }

    private Response decide(String operation, String url, String key) {
        return send(HttpMethod.POST, "/v1/ops/publication/" + operation, null, new JsonObject().put("url", url).encode(), "api-key", key);
    }

    private void assign(String roleName, String subject) {
        Response response = send(HttpMethod.POST, "/v1/org-units/public/Acme/role-assignments", null,
                "{\"roleName\": \"%s\", \"subject\": %s}".formatted(roleName, subject), "authorization", "admin");
        assertEquals(200, response.status(), response.body());
    }
}
