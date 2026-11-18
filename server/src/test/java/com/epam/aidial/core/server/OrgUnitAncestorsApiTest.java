package com.epam.aidial.core.server;

import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.Rule;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A person with a role deep in the tree sees the units above it by name, with only the child units on the way there,
 * and none of their content.
 */
public class OrgUnitAncestorsApiTest extends ResourceBaseTest {

    private static final String BOB = "azp:bob";

    private String basel;

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
        create("public/Acme/Risk", "EU Risk");
        create("public/Acme/Risk/US%20Risk", "Basel");
        for (String file : List.of("Acme/a.txt", "Acme/HR/h.txt", "Acme/Risk/r.txt", "Acme/Risk/US%20Risk/u.txt",
                "Acme/Risk/US%20Risk/Basel/b.txt", "Handbook/w.txt")) {
            assertEquals(200, upload(HttpMethod.PUT, "/v1/files/public/" + file, null, "x", "authorization", "admin").status());
        }
        basel = assign("public/Acme/Risk/US%20Risk/Basel", "editor");
    }

    @Test
    void testRoleBelowShowsTheUnitsAboveAndOnlyThePathToIt() {
        assertEquals(List.of("files/public/Acme/", "files/public/Handbook/"), urls(list("public/", BOB)));
        assertEquals(List.of("files/public/Acme/Risk/"), urls(list("public/Acme/", BOB)));
        assertEquals(List.of("files/public/Acme/Risk/US%20Risk/"), urls(list("public/Acme/Risk/", BOB)));
        assertEquals(List.of("files/public/Acme/Risk/US%20Risk/Basel/"), urls(list("public/Acme/Risk/US%20Risk/", BOB)));
        assertEquals(403, list("public/Acme/HR/", BOB).status());
        assertEquals(403, list("public/Acme/Risk/EU%20Risk/", BOB).status());

        assertEquals(403, read("Acme/a.txt", BOB).status());
        assertEquals(403, read("Acme/Risk/r.txt", BOB).status());
        assertEquals(403, read("Acme/Risk/US%20Risk/u.txt", BOB).status());
        assertEquals(200, read("Acme/Risk/US%20Risk/Basel/b.txt", BOB).status());
        assertEquals(List.of("files/public/Acme/Risk/US%20Risk/Basel/.dial_folder", "files/public/Acme/Risk/US%20Risk/Basel/b.txt",
                "files/public/Handbook/w.txt"), urls(send(HttpMethod.GET, "/v1/metadata/files/public/", "recursive=true", null,
                "authorization", BOB)));

        assertEquals(List.of("public/Acme", "public/Acme/Risk", "public/Acme/Risk/US Risk", "public/Acme/Risk/US Risk/Basel"),
                unitPaths(BOB));
        assertEquals(403, send(HttpMethod.GET, "/v1/org-units/public/Acme/role-assignments", null, null, "authorization", BOB).status());
        assertEquals(List.of(), unitPaths("azp:nobody"));
        assertEquals(403, list("public/Acme/", "azp:nobody").status());
    }

    @Test
    void testRemovingTheRoleHidesTheUnitsAbove() {
        assertEquals(List.of("files/public/Acme/", "files/public/Handbook/"), urls(list("public/", BOB)));
        Response removed = send(HttpMethod.DELETE, "/v1/org-units/public/Acme/Risk/US%20Risk/Basel/role-assignments/" + basel, null, null,
                "authorization", "admin");
        assertEquals(200, removed.status(), removed.body());

        assertEquals(List.of("files/public/Handbook/"), urls(list("public/", BOB)));
        assertEquals(403, list("public/Acme/", BOB).status());
        assertEquals(List.of(), unitPaths(BOB));
    }

    @Test
    void testApplicationsReferToNoFolderAboveTheirUnit() {
        String app = "{\"displayName\": \"app\", \"applicationTypeSchemaId\": "
                + "\"https://mydial.somewhere.com/custom_application_schemas/specific_application_type\", "
                + "\"applicationProperties\": {\"property1\": \"p1\", \"property2\": \"p2\", \"property3\": [\"%s\"]}}";
        String url = "/v1/applications/public/Acme/Risk/US%20Risk/Basel/app";

        Response above = send(HttpMethod.PUT, url, null, app.formatted("files/public/Acme/"), "authorization", BOB);
        assertEquals(403, above.status(), above.body());
        assertEquals(404, send(HttpMethod.DELETE, url, null, null, "authorization", BOB).status());
        assertEquals(200, read("Acme/a.txt", "admin").status());
        assertEquals(200, read("Acme/HR/h.txt", "admin").status());

        Response own = send(HttpMethod.PUT, url, null, app.formatted("files/public/Acme/Risk/US%20Risk/Basel/"), "authorization", BOB);
        assertEquals(200, own.status(), own.body());
    }

    @Test
    void testApplicationsReferToNoFolderSpanningUnitsTheirAuthorCantRead() {
        assign("public/Acme/Risk", "viewer");
        String app = "{\"displayName\": \"app\", \"applicationTypeSchemaId\": "
                + "\"https://mydial.somewhere.com/custom_application_schemas/specific_application_type\", "
                + "\"applicationProperties\": {\"property1\": \"p1\", \"property2\": \"p2\", \"property3\": [\"%s\"]}}";
        String url = "/v1/applications/public/Acme/Risk/US%20Risk/Basel/app";

        Response spanning = send(HttpMethod.PUT, url, null, app.formatted("files/public/Acme/Risk/"), "authorization", BOB);
        assertEquals(403, spanning.status(), spanning.body());

        Response file = send(HttpMethod.PUT, url, null, app.formatted("files/public/Acme/Risk/r.txt"), "authorization", BOB);
        assertEquals(200, file.status(), file.body());
    }

    @Test
    void testRulesOfTheUnitsAboveStayHidden() {
        // nothing stores rules inside a unit any more: seed one as a node of an older version would have
        Rule rule = new Rule();
        rule.setSource("roles");
        rule.setFunction(Rule.Function.TRUE);
        dial.getProxy().getRuleService().storeRules("public/Acme/", List.of(rule));
        String body = "{\"url\": \"public/Acme/\"}";

        assertEquals(403, send(HttpMethod.POST, "/v1/ops/publication/rule/list", null, body, "authorization", BOB).status());

        assign("public/Acme", "viewer");
        Response rules = send(HttpMethod.POST, "/v1/ops/publication/rule/list", null, body, "authorization", BOB);
        assertEquals(200, rules.status(), rules.body());
    }

    @Test
    @DialConfigLocation("dial-config/org-unit-navigator-role.json")
    void testRoleSeeingTheChildUnitsListsTheRulesOfItsUnit() {
        assign("public/Acme", "navigator");
        Response rules = send(HttpMethod.POST, "/v1/ops/publication/rule/list", null, "{\"url\": \"public/Acme/\"}", "authorization", BOB);
        assertEquals(200, rules.status(), rules.body());
    }

    @Test
    void testBuiltInRoleAboveShowsOnlyTheChildUnitOnThePath() {
        assign("public/Acme", "viewer");

        assertEquals(List.of("files/public/Acme/.dial_folder", "files/public/Acme/Risk/", "files/public/Acme/a.txt"),
                urls(list("public/Acme/", BOB)));
        assertEquals(List.of("files/public/Acme/Risk/US%20Risk/"), urls(list("public/Acme/Risk/", BOB)));
    }

    @Test
    @DialConfigLocation("dial-config/org-unit-navigator-role.json")
    void testRoleReadingOrgUnitsShowsEveryChildUnit() {
        assign("public/Acme", "navigator");

        assertEquals(List.of("files/public/Acme/HR/", "files/public/Acme/Risk/"), urls(list("public/Acme/", BOB)));
    }

    @Test
    @DialConfigLocation("dial-config/org-unit-navigator-role.json")
    void testOnlyHoldersOfRolesReadingOrgUnitsGiveAndRemoveThem() {
        assign("public/Acme", "admin");
        Response byDialAdmin = giveNavigator("carol", "admin");
        assertEquals(200, byDialAdmin.status(), byDialAdmin.body());
        String carol = new JsonObject(byDialAdmin.body()).getString("id");

        assertEquals(403, giveNavigator("dave", BOB).status());
        assertEquals(403, removeAssignment(carol, BOB).status());

        assign("public/Acme", "navigator");
        assertEquals(200, giveNavigator("dave", BOB).status());
        assertEquals(200, removeAssignment(carol, BOB).status());
    }

    @Test
    void testApplicationsActingForThePersonDontSeeTheUnitsAbove() {
        ApiKeyData key = new ApiKeyData();
        key.setExtractedClaims(createWorkloadClaims("bob"));
        key.setSourceDeployment("testapp");
        key.setTraceId("trace-id");
        apiKeyStore.assignPerRequestApiKey(key);
        String perRequestKey = key.getPerRequestKey();

        assertEquals(403, send(HttpMethod.GET, "/v1/metadata/files/public/Acme/", null, null, "api-key", perRequestKey).status());
        assertEquals(200, send(HttpMethod.GET, "/v1/files/public/Acme/Risk/US%20Risk/Basel/b.txt", null, null, "api-key", perRequestKey).status());
    }

    private void create(String parent, String name) {
        Response response = send(HttpMethod.POST, "/v1/org-units/" + parent, null, new JsonObject().put("name", name).encode(),
                "authorization", "admin");
        assertEquals(200, response.status(), response.body());
    }

    private String assign(String unit, String roleName) {
        Response response = send(HttpMethod.POST, "/v1/org-units/" + unit + "/role-assignments", null,
                "{\"roleName\": \"%s\", \"subject\": {\"type\": \"claimsPredicate\", \"source\": \"azp\", \"function\": \"EQUAL\", \"targets\": [\"bob\"]}}"
                        .formatted(roleName), "authorization", "admin");
        assertEquals(200, response.status(), response.body());
        return new JsonObject(response.body()).getString("id");
    }

    private Response giveNavigator(String target, String caller) {
        return send(HttpMethod.POST, "/v1/org-units/public/Acme/role-assignments", null,
                "{\"roleName\": \"navigator\", \"subject\": {\"type\": \"claimsPredicate\", \"source\": \"azp\", \"function\": \"EQUAL\", \"targets\": [\"%s\"]}}"
                        .formatted(target), "authorization", caller);
    }

    private Response removeAssignment(String id, String caller) {
        return send(HttpMethod.DELETE, "/v1/org-units/public/Acme/role-assignments/" + id, null, null, "authorization", caller);
    }

    private Response list(String folder, String caller) {
        return send(HttpMethod.GET, "/v1/metadata/files/" + folder, null, null, "authorization", caller);
    }

    private Response read(String file, String caller) {
        return send(HttpMethod.GET, "/v1/files/public/" + file, null, null, "authorization", caller);
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

    private List<String> unitPaths(String caller) {
        Response response = send(HttpMethod.GET, "/v1/org-units/public", "recursive=true", null, "authorization", caller);
        assertEquals(200, response.status(), response.body());
        List<String> paths = new ArrayList<>();
        JsonArray items = new JsonObject(response.body()).getJsonArray("items");
        for (int i = 0; i < items.size(); i++) {
            paths.add(items.getJsonObject(i).getString("path"));
        }
        return paths.stream().sorted().toList();
    }
}
