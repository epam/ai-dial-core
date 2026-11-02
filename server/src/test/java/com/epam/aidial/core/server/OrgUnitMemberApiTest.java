package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A caller with a role deep in the tree finds their unit through {@code member} roles above it, which show child units
 * and nothing of the content.
 */
public class OrgUnitMemberApiTest extends ResourceBaseTest {

    private static final String BOB = "azp:bob";

    @Override
    protected JsonObject additionalSettingsOverrides() {
        return new JsonObject().put("orgUnits", new JsonObject().put("enabled", true));
    }

    @BeforeEach
    void tree() {
        create("public", "Acme");
        create("public/Acme", "Risk");
        create("public/Acme", "HR");
        create("public/Acme/Risk", "US Risk");
        create("public/Acme/Risk/US%20Risk", "Basel");
        for (String file : List.of("Acme/a.txt", "Acme/HR/h.txt", "Acme/Risk/r.txt", "Acme/Risk/US%20Risk/u.txt",
                "Acme/Risk/US%20Risk/Basel/b.txt", "Handbook/w.txt")) {
            assertEquals(200, upload(HttpMethod.PUT, "/v1/files/public/" + file, null, "x", "authorization", "admin").status());
        }
        assign("public/Acme/Risk/US%20Risk/Basel", "editor");
    }

    @Test
    void testRoleBelowWithoutMembersIsReachedOnlyDirectly() {
        assertEquals(403, list("public/Acme/Risk/").status());
        assertEquals(List.of("files/public/Acme/Risk/US%20Risk/Basel/.dial_folder", "files/public/Acme/Risk/US%20Risk/Basel/b.txt"),
                urls(list("public/Acme/Risk/US%20Risk/Basel/")));
        assertEquals(List.of(), unitPaths());
    }

    @Test
    void testMembersSeeChildUnitsAndNoContent() {
        assign("public/Acme", "member");
        assign("public/Acme/Risk", "member");
        assign("public/Acme/Risk/US%20Risk", "member");

        assertEquals(List.of("files/public/Acme/", "files/public/Handbook/"), urls(list("public/")));
        assertEquals(List.of("files/public/Acme/HR/", "files/public/Acme/Risk/"), urls(list("public/Acme/")));
        assertEquals(List.of("files/public/Acme/Risk/US%20Risk/"), urls(list("public/Acme/Risk/")));
        assertEquals(List.of("files/public/Acme/Risk/US%20Risk/Basel/"), urls(list("public/Acme/Risk/US%20Risk/")));
        assertEquals(403, list("public/Acme/HR/").status());

        assertEquals(403, send(HttpMethod.GET, "/v1/files/public/Acme/a.txt", null, null, "authorization", BOB).status());
        assertEquals(403, send(HttpMethod.GET, "/v1/files/public/Acme/Risk/US%20Risk/u.txt", null, null, "authorization", BOB).status());
        assertEquals(200, send(HttpMethod.GET, "/v1/files/public/Acme/Risk/US%20Risk/Basel/b.txt", null, null, "authorization", BOB).status());
        assertEquals(List.of("files/public/Acme/Risk/US%20Risk/Basel/.dial_folder", "files/public/Acme/Risk/US%20Risk/Basel/b.txt",
                "files/public/Handbook/w.txt"), urls(send(HttpMethod.GET, "/v1/metadata/files/public/", "recursive=true", null,
                "authorization", BOB)));

        assertEquals(List.of("public/Acme", "public/Acme/HR", "public/Acme/Risk", "public/Acme/Risk/US Risk",
                "public/Acme/Risk/US Risk/Basel"), unitPaths());
        assertEquals(403, send(HttpMethod.GET, "/v1/org-units/public/Acme/role-assignments", null, null, "authorization", BOB).status());
    }

    private void create(String parent, String name) {
        Response response = send(HttpMethod.POST, "/v1/org-units/" + parent, null, new JsonObject().put("name", name).encode(),
                "authorization", "admin");
        assertEquals(200, response.status(), response.body());
    }

    private void assign(String unit, String roleName) {
        Response response = send(HttpMethod.POST, "/v1/org-units/" + unit + "/role-assignments", null,
                "{\"roleName\": \"%s\", \"subject\": {\"type\": \"claimsPredicate\", \"source\": \"azp\", \"function\": \"EQUAL\", \"targets\": [\"bob\"]}}"
                        .formatted(roleName), "authorization", "admin");
        assertEquals(200, response.status(), response.body());
    }

    private Response list(String folder) {
        return send(HttpMethod.GET, "/v1/metadata/files/" + folder, null, null, "authorization", BOB);
    }

    private static List<String> urls(Response response) {
        assertEquals(200, response.status(), response.body());
        List<String> urls = new ArrayList<>();
        JsonArray items = new JsonObject(response.body()).getJsonArray("items");
        for (int i = 0; i < items.size(); i++) {
            urls.add(items.getJsonObject(i).getString("url"));
        }
        return urls.stream().sorted().toList();
    }

    private List<String> unitPaths() {
        Response response = send(HttpMethod.GET, "/v1/org-units/public", "recursive=true", null, "authorization", BOB);
        assertEquals(200, response.status(), response.body());
        List<String> paths = new ArrayList<>();
        JsonArray items = new JsonObject(response.body()).getJsonArray("items");
        for (int i = 0; i < items.size(); i++) {
            paths.add(items.getJsonObject(i).getString("path"));
        }
        return paths.stream().sorted().toList();
    }
}
