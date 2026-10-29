package com.epam.aidial.core.server;

import com.epam.aidial.core.server.data.ApiKeyData;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The caller's units tell the admin panel how to start and what to show: whether the caller is a DIAL admin, and in
 * which units they hold which roles and permissions.
 */
public class OrgUnitUserOrgUnitsApiTest extends ResourceBaseTest {

    @Override
    protected JsonObject additionalSettingsOverrides() {
        return new JsonObject().put("orgUnits", new JsonObject().put("enabled", true));
    }

    @BeforeEach
    void createUnits() {
        assertEquals(200, send(HttpMethod.POST, "/v1/org-units/public", null, "{\"name\": \"Acme\"}", "authorization", "admin").status());
        assertEquals(200, send(HttpMethod.POST, "/v1/org-units/public/Acme", null, "{\"name\": \"Risk\"}", "authorization", "admin").status());
        assertEquals(200, send(HttpMethod.POST, "/v1/org-units/public", null, "{\"name\": \"Beta\"}", "authorization", "admin").status());
        assertEquals(200, send(HttpMethod.POST, "/v1/org-units/public/Beta", null, "{\"name\": \"Deep\"}", "authorization", "admin").status());
        assign("public/Acme", "member", "alice");
        assign("public/Acme/Risk", "editor", "alice");
        assign("public/Acme/Risk", "admin", "alice");
        assign("public/Beta/Deep", "viewer", "carol");
    }

    @Test
    void testUnitsWithRolesAndPermissions() {
        JsonObject alice = units("azp:alice");
        assertEquals("NONE", alice.getString("adminAccess"));
        JsonArray items = alice.getJsonArray("items");
        assertEquals(List.of("public/Acme", "public/Acme/Risk"), paths(items));

        JsonObject acme = items.getJsonObject(0);
        assertEquals("Acme", acme.getString("name"));
        assertEquals("public/Acme/", acme.getString("folder"));
        assertEquals(List.of("member"), acme.getJsonArray("roles").getList());
        assertEquals(new JsonObject().put("org-units", new JsonArray().add("read")), acme.getJsonObject("permissions"));

        JsonObject risk = items.getJsonObject(1);
        assertEquals(List.of("admin", "editor"), risk.getJsonArray("roles").getList());
        JsonObject permissions = risk.getJsonObject("permissions");
        assertEquals(List.of("read", "write"), permissions.getJsonArray("applications").getList());
        assertEquals(List.of("read", "write"), permissions.getJsonArray("publications").getList());
        assertEquals(List.of("read", "write"), permissions.getJsonArray("role-assignments").getList());
        assertEquals(List.of("read"), permissions.getJsonArray("org-units").getList());
    }

    @Test
    void testUnitReachedOnlyByPathComesWithoutItsParent() {
        JsonArray items = units("azp:carol").getJsonArray("items");
        assertEquals(List.of("public/Beta/Deep"), paths(items));
        assertEquals(List.of("viewer"), items.getJsonObject(0).getJsonArray("roles").getList());
    }

    @Test
    void testCallerWithoutRolesHasNoUnits() {
        JsonObject nobody = units("azp:nobody");
        assertEquals("NONE", nobody.getString("adminAccess"));
        assertEquals(new JsonArray(), nobody.getJsonArray("items"));
    }

    @Test
    void testAdminsGetEveryUnit() {
        JsonObject admin = units("admin");
        assertEquals("FULL", admin.getString("adminAccess"));
        JsonArray items = admin.getJsonArray("items");
        assertEquals(List.of("public/Acme", "public/Acme/Risk", "public/Beta", "public/Beta/Deep"), paths(items));
        assertEquals(List.of("read", "write"), items.getJsonObject(2).getJsonObject("permissions").getJsonArray("org-units").getList());
    }

    @Test
    void testApplicationsHaveNoUnits() {
        ApiKeyData key = new ApiKeyData();
        key.setExtractedClaims(createWorkloadClaims("alice"));
        key.setSourceDeployment("testapp");
        key.setTraceId("trace-id");
        apiKeyStore.assignPerRequestApiKey(key);

        Response response = send(HttpMethod.GET, "/v1/user/org-units", null, null, "api-key", key.getPerRequestKey());
        assertEquals(200, response.status(), response.body());
        JsonObject body = new JsonObject(response.body());
        assertEquals("NONE", body.getString("adminAccess"));
        assertEquals(new JsonArray(), body.getJsonArray("items"));
    }

    @Test
    void testUserInfoIsUnchanged() {
        Response response = send(HttpMethod.GET, "/v1/user/info", null, null, "authorization", "azp:alice");
        assertEquals(200, response.status(), response.body());
        assertFalse(new JsonObject(response.body()).containsKey("adminAccess"));
    }

    private JsonObject units(String authorization) {
        Response response = send(HttpMethod.GET, "/v1/user/org-units", null, null, "authorization", authorization);
        assertEquals(200, response.status(), response.body());
        return new JsonObject(response.body());
    }

    private static List<String> paths(JsonArray items) {
        return items.stream().map(item -> ((JsonObject) item).getString("path")).toList();
    }

    private void assign(String unit, String roleName, String azp) {
        Response response = send(HttpMethod.POST, "/v1/org-units/" + unit + "/role-assignments", null,
                "{\"roleName\": \"%s\", \"subject\": {\"type\": \"claimsPredicate\", \"source\": \"azp\", \"function\": \"EQUAL\", \"targets\": [\"%s\"]}}"
                        .formatted(roleName, azp), "authorization", "admin");
        assertEquals(200, response.status(), response.body());
    }
}
