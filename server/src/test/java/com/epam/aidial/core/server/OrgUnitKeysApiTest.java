package com.epam.aidial.core.server;

import com.epam.aidial.core.server.data.ApiKeyData;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Callers other than a person read through unit roles and do nothing else through them: an application's per-request
 * key reads what its user's roles read, and an API key what a subject on {@code roles} its configured roles match.
 */
public class OrgUnitKeysApiTest extends ResourceBaseTest {

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
    void testApiKeysReadThroughTheirConfiguredRoles() {
        assertEquals(403, send(HttpMethod.GET, "/v1/files/public/Acme/a.txt", null, null, "api-key", "proxyKey1").status());

        assign("editor", "{\"type\": \"claimsPredicate\", \"source\": \"roles\", \"function\": \"EQUAL\", \"targets\": [\"default\"]}");
        assertEquals(200, send(HttpMethod.GET, "/v1/files/public/Acme/a.txt", null, null, "api-key", "proxyKey1").status());
        assertEquals(403, upload(HttpMethod.PUT, "/v1/files/public/Acme/b.txt", null, "b", "api-key", "proxyKey1").status());
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
        assign("member", "{\"type\": \"claimsPredicate\", \"source\": \"azp\", \"function\": \"EQUAL\", \"targets\": [\"bob\"]}");

        String alice = perRequestKeyFor("alice");
        assertEquals(200, grant(alice, "files/public/Acme/a.txt").status());
        assertEquals(200, grant(alice, "files/public/Acme/Docs/").status());
        assertEquals(403, grant(alice, "files/public/Acme/").status(), "Acme/ holds Child, where alice has no role");
        assertEquals(403, grant(alice, "files/public/").status());
        assertEquals(403, grant(perRequestKeyFor("bob"), "files/public/Acme/").status(), "member reads no content");
    }

    private Response grant(String key, String url) {
        return send(HttpMethod.POST, "/v1/ops/resource/per-request-permissions/grant", null,
                new JsonObject().put("receiver", "app2").put("resourcePermissions", new JsonArray()
                        .add(new JsonObject().put("url", url).put("permissions", new JsonArray().add("READ")))).encode(),
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

    private void assign(String roleName, String subject) {
        Response response = send(HttpMethod.POST, "/v1/org-units/public/Acme/role-assignments", null,
                "{\"roleName\": \"%s\", \"subject\": %s}".formatted(roleName, subject), "authorization", "admin");
        assertEquals(200, response.status(), response.body());
    }
}
