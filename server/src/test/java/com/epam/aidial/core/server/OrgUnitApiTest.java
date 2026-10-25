package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.util.UrlUtil;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class OrgUnitApiTest extends ResourceBaseTest {

    private static final String ORG = "public";

    @Override
    protected JsonObject additionalSettingsOverrides() {
        return new JsonObject().put("orgUnits", new JsonObject().put("enabled", true));
    }

    @Test
    void testCreateAndReadUnits() {
        addReadRule("public/Risk/", "Manager");
        Response risk = create(ORG, "Risk");
        assertEquals(200, risk.status(), risk.body());
        JsonObject created = new JsonObject(risk.body());
        assertEquals("Risk", created.getString("name"));
        assertEquals("Risk", created.getString("displayName"));
        assertEquals(ORG + "/Risk", created.getString("path"));
        assertEquals("public/Risk/", created.getString("folder"));
        assertTrue(created.getJsonArray("items").isEmpty());

        addReadRule("public/Risk/US Risk/", "Manager");
        Response usRisk = send(HttpMethod.POST, "/v1/org-units/" + ORG + "/Risk", null,
                "{\"name\": \"US Risk\", \"displayName\": \"US Risk Department\"}", "authorization", "admin");
        assertEquals(200, usRisk.status(), usRisk.body());
        assertEquals("public/Risk/US%20Risk/", new JsonObject(usRisk.body()).getString("folder"));

        addReadRule("public/Finance/", "Manager");
        assertEquals(200, create(ORG, "Finance").status());

        JsonObject org = read(ORG, false);
        assertEquals(ORG, org.getString("name"));
        assertEquals(ORG, org.getString("displayName"));
        assertEquals("public/", org.getString("folder"));
        assertEquals(List.of("Finance", "Risk"), childNames(org));

        JsonObject tree = read(ORG, true);
        JsonArray items = tree.getJsonArray("items");
        assertEquals(3, items.size());
        assertEquals(ORG + "/Finance", items.getJsonObject(0).getString("path"));
        assertEquals(ORG + "/Risk", items.getJsonObject(1).getString("path"));
        assertEquals(ORG + "/Risk/US Risk", items.getJsonObject(2).getString("path"));
        assertEquals("US Risk Department", items.getJsonObject(2).getString("displayName"));

        JsonObject unit = read(ORG + "/Risk/US Risk", false);
        assertEquals(ORG + "/Risk/US Risk", unit.getString("path"));
        assertTrue(unit.getJsonArray("items").isEmpty());
    }

    @Test
    void testCreateRefusals() {
        addReadRule("public/Risk/", "Manager");
        assertEquals(200, create(ORG, "Risk").status());
        addReadRule("public/risk/", "Manager");
        Response duplicate = create(ORG, "risk");
        assertEquals(409, duplicate.status());
        assertTrue(duplicate.body().contains("already has a unit named Risk"), duplicate.body());

        Response file = upload(HttpMethod.PUT, "/v1/files/public/Policies/limits.txt", null, "text", "authorization", "admin");
        assertEquals(200, file.status(), file.body());
        addReadRule("public/policies/", "Manager");
        Response folderTaken = create(ORG, "policies");
        assertEquals(409, folderTaken.status());
        assertTrue(folderTaken.body().contains("already has a folder named Policies"), folderTaken.body());

        Response item = upload(HttpMethod.PUT, "/v1/files/public/Notes", null, "text", "authorization", "admin");
        assertEquals(200, item.status(), item.body());
        addReadRule("public/notes/", "Manager");
        Response itemTaken = create(ORG, "notes");
        assertEquals(409, itemTaken.status());
        assertTrue(itemTaken.body().contains("already has an item named Notes"), itemTaken.body());

        Response prompt = send(HttpMethod.PUT, "/v1/prompts/public/Legal/memo", null, PROMPT_BODY, "authorization", "admin");
        assertEquals(200, prompt.status(), prompt.body());
        addReadRule("public/legal/", "Manager");
        Response promptTaken = create(ORG, "legal");
        assertEquals(409, promptTaken.status());
        assertTrue(promptTaken.body().contains("already has a folder named Legal"), promptTaken.body());

        assertEquals(400, create(ORG, "Bad ").status());
        assertEquals(400, create(ORG, ".hidden").status());
        assertEquals(400, create(ORG, "a/b").status());
        assertEquals(400, create(ORG, "a{b").status());
        assertEquals(400, create(ORG, "x".repeat(1000)).status());
        assertEquals(404, create(ORG + "/Missing", "Child").status());
        assertEquals(404, create("acme", "Child").status());

        String parent = ORG;
        String folder = "public/";
        for (String name : List.of("L1", "L2", "L3", "L4")) {
            folder = folder + name + "/";
            addReadRule(folder, "Manager");
            assertEquals(200, create(parent, name).status(), folder);
            parent = parent + "/" + name;
        }
        Response tooDeep = create(parent, "L5");
        assertEquals(409, tooDeep.status());
        assertTrue(tooDeep.body().contains("at most 4 levels"), tooDeep.body());
    }

    @Test
    void testNonAdmins() {
        addReadRule("public/Risk/", "Manager");
        addReadRule("public/Finance/", "Director");
        assertEquals(200, create(ORG, "Risk").status());
        assertEquals(200, create(ORG, "Finance").status());

        Response refused = send(HttpMethod.POST, "/v1/org-units/" + ORG, null, "{\"name\": \"Ops\"}", "authorization", "user");
        assertEquals(403, refused.status());

        Response org = send(HttpMethod.GET, "/v1/org-units/" + ORG, null, null, "authorization", "user");
        assertEquals(200, org.status(), org.body());
        assertEquals(List.of("Risk"), childNames(new JsonObject(org.body())));
        assertEquals(404, send(HttpMethod.GET, "/v1/org-units/" + ORG + "/Finance", null, null, "authorization", "user").status());
        assertEquals(200, send(HttpMethod.GET, "/v1/org-units/" + ORG + "/Risk", null, null, "authorization", "user").status());

        addReadRule("public/Risk/Audit/", "Director");
        addReadRule("public/Finance/Tax/", "Manager");
        assertEquals(200, create(ORG + "/Risk", "Audit").status());
        assertEquals(200, create(ORG + "/Finance", "Tax").status());
        Response all = send(HttpMethod.GET, "/v1/org-units/" + ORG, "recursive=true", null, "authorization", "user");
        assertEquals(200, all.status(), all.body());
        assertEquals(List.of("Risk"), childNames(new JsonObject(all.body())));
        assertEquals(404, send(HttpMethod.GET, "/v1/org-units/" + ORG + "/Risk/Audit", null, null, "authorization", "user").status());
        assertEquals(404, send(HttpMethod.GET, "/v1/org-units/" + ORG + "/Finance/Tax", null, null, "authorization", "user").status());
        assertEquals(List.of("Finance", "Tax", "Risk", "Audit"), childNames(read(ORG, true)));
    }

    @Test
    void testDisplayNameIsChecked() {
        addReadRule("public/Risk/", "Manager");
        for (String displayName : List.of("x".repeat(129), "line\nbreak")) {
            Response response = send(HttpMethod.POST, "/v1/org-units/" + ORG, null,
                    new JsonObject().put("name", "Risk").put("displayName", displayName).encode(), "authorization", "admin");
            assertEquals(400, response.status(), response.body());
        }
        Response ok = send(HttpMethod.POST, "/v1/org-units/" + ORG, null,
                new JsonObject().put("name", "Risk").put("displayName", "x".repeat(128)).encode(), "authorization", "admin");
        assertEquals(200, ok.status(), ok.body());
    }

    @Test
    void testNewUnitGetsFolderMarker() {
        addReadRule("public/Acme/", "Manager");
        assertEquals(200, create(ORG, "Acme").status());
        assertEquals(List.of(".dial_folder"), itemNames("files/public/Acme/"));
        assertTrue(itemNames("files/public/").contains("Acme"));

        addReadRule("public/Acme/Risk/", "Manager");
        assertEquals(200, create(ORG + "/Acme", "Risk").status());
        assertEquals(List.of(".dial_folder"), itemNames("files/public/Acme/Risk/"));
    }

    @Test
    void testRatePathStaysWithDeployments() {
        addReadRule("public/Acme/", "Manager");
        assertEquals(200, create(ORG, "Acme").status());

        Response rate = send(HttpMethod.POST, "/v1/org-units/public/Acme/rate", null, "{\"rate\": true}", "authorization", "admin");
        assertEquals(404, rate.status(), rate.body());
        assertTrue(rate.body().startsWith("Unknown deployment"), rate.body());
        assertEquals(List.of(), childNames(read(ORG + "/Acme", false)));
    }

    @Test
    void testReservedAndDottedNamesAreRefused() {
        for (String name : List.of("roles", "Role-Assignments", "rate", "Risk.")) {
            addReadRule("public/" + name + "/", "Manager");
            Response response = create(ORG, name);
            assertEquals(400, response.status(), name + " " + response.body());
        }
    }

    @Test
    void testAuthorIsTheCreatorsDisplayName() {
        addReadRule("public/Risk/", "Manager");
        Response created = create(ORG, "Risk");
        assertEquals(200, created.status());
        assertEquals("admin user", new JsonObject(created.body()).getString("author"));
        assertTrue(new JsonObject(created.body()).getValue("createdAt") instanceof Number, created.body());

        Response asUser = send(HttpMethod.GET, "/v1/org-units/" + ORG + "/Risk", null, null, "authorization", "user");
        assertEquals(200, asUser.status(), asUser.body());
        assertEquals("admin user", new JsonObject(asUser.body()).getString("author"));

        Response marker = send(HttpMethod.GET, "/v1/metadata/files/public/Risk/.dial_folder", null, null, "authorization", "admin");
        assertEquals(200, marker.status(), marker.body());
        assertEquals("admin user", new JsonObject(marker.body()).getString("author"));
    }

    @Test
    void testNamesFollowCoreFolderRules() {
        for (String name : List.of("R&D", "Ünits 日本", "Q1:2026")) {
            addReadRule("public/" + UrlUtil.encodePathSegment(name) + "/", "Manager");
            Response response = create(ORG, name);
            assertEquals(200, response.status(), name + " " + response.body());
            assertEquals("public/" + UrlUtil.encodePathSegment(name) + "/", new JsonObject(response.body()).getString("folder"));
        }
        assertEquals(List.of("Q1:2026", "R&D", "Ünits 日本"), childNames(read(ORG, false)));

        // The url form keeps ":" as it is, and ":abc" is not a valid URI, so the name must not be checked through it
        for (String name : List.of(":abc", "R&D: Plan")) {
            Response colon = create(ORG, name);
            assertEquals(200, colon.status(), colon.body());
        }
    }

    @Test
    void testUnitsNeedNoReadRule() {
        assertEquals(200, create(ORG, "Acme").status());
        assertEquals(200, create(ORG + "/Acme", "Risk").status());
        assertEquals(200, upload(HttpMethod.PUT, "/v1/files/public/Acme/Risk/a.txt", null, "a", "authorization", "admin").status());
        assertEquals(200, send(HttpMethod.GET, "/v1/files/public/Acme/Risk/a.txt", null, null).status(), "read rules still decide");
        addReadRule("public/Acme/", "Manager");
        assertEquals(403, send(HttpMethod.GET, "/v1/files/public/Acme/Risk/a.txt", null, null).status());
    }

    @Test
    void testUnknownFieldsAreRefusedByName() {
        addReadRule("public/Acme/", "Manager");
        for (String field : List.of("folder", "path", "id", "author")) {
            Response refused = send(HttpMethod.POST, "/v1/org-units/" + ORG, null,
                    new JsonObject().put("name", "Acme").put(field, "x").encode(), "authorization", "admin");
            assertEquals(400, refused.status(), field);
            assertTrue(refused.body().startsWith("Unknown field '" + field + "'"), refused.body());
        }
        assertTrue(read(ORG, false).getJsonArray("items").isEmpty());
    }

    @Test
    void testEncodedSlashIsNotAnotherUnit() {
        addReadRule("public/Acme/", "Manager");
        addReadRule("public/Acme/Risk/", "Manager");
        assertEquals(200, create(ORG, "Acme").status());
        assertEquals(200, create(ORG + "/Acme", "Risk").status());

        assertEquals(400, send(HttpMethod.POST, "/v1/org-units/public/Acme%2FRisk", null, "{\"name\": \"Basel\"}", "authorization", "admin").status());
        assertEquals(400, send(HttpMethod.DELETE, "/v1/org-units/public/Acme%2FRisk", null, null, "authorization", "admin").status());
        assertEquals(400, send(HttpMethod.GET, "/v1/org-units/public/Acme%2FRisk", null, null, "authorization", "admin").status());
        assertEquals(404, send(HttpMethod.POST, "/v1/org-units/public/a/b/c/d", null, "{\"name\": \"x\"}", "authorization", "admin").status());
        assertEquals(400, send(HttpMethod.POST, "/v1/org-units/public", null, "", "authorization", "admin").status());
        assertEquals(400, send(HttpMethod.POST, "/v1/org-units/public", null, "{\"name\":", "authorization", "admin").status());
        assertEquals(List.of("Risk"), childNames(read(ORG + "/Acme", false)));
    }

    @Test
    void testUnitTreeIsNeverVisibleAsFile() {
        addReadRule("public/Acme/", "Manager");
        assertEquals(200, create(ORG, "Acme").status());
        ResourceDescriptor tree = ResourceDescriptorFactory.fromDecoded(ResourceTypes.ORG_UNITS, ResourceDescriptor.PUBLIC_BUCKET,
                ResourceDescriptor.PUBLIC_LOCATION, "units");
        assertTrue(dial.getProxy().getResourceService().getResource(tree).contains("public/Acme/"));

        for (String caller : List.of("admin", "user")) {
            assertEquals(404, send(HttpMethod.GET, "/v1/org_units/public/units", null, null, "authorization", caller).status());
            assertEquals(404, send(HttpMethod.GET, "/v1/metadata/org_units/public/units", null, null, "authorization", caller).status());
            assertEquals(404, send(HttpMethod.DELETE, "/v1/org_units/public/units", null, null, "authorization", caller).status());
            assertEquals(400, move("org_units/public/units", "files/public/Acme/stolen.json").status());
        }
        assertEquals(List.of(".dial_folder"), itemNames("files/public/Acme/"));
        assertTrue(!itemNames("files/public/").contains("org_units"));
        assertTrue(dial.getProxy().getResourceService().getResource(tree).contains("public/Acme/"));
    }

    @Test
    void testDisplayNameCanBeChanged() {
        addReadRule("public/Acme/", "Manager");
        assertEquals(200, create(ORG, "Acme").status());
        assertEquals(200, create(ORG + "/Acme", "Risk").status());

        Response updated = rename(ORG + "/Acme", "{\"displayName\": \"Acme Corp\"}", "admin");
        assertEquals(200, updated.status(), updated.body());
        JsonObject unit = new JsonObject(updated.body());
        assertEquals("Acme", unit.getString("name"));
        assertEquals("Acme Corp", unit.getString("displayName"));
        assertEquals(ORG + "/Acme", unit.getString("path"));
        assertEquals(List.of("Risk"), childNames(unit));
        assertEquals("Acme Corp", read(ORG + "/Acme", false).getString("displayName"));
        assertEquals("Acme Corp", read(ORG, false).getJsonArray("items").getJsonObject(0).getString("displayName"));

        assertEquals("Acme", new JsonObject(rename(ORG + "/Acme", "{\"displayName\": \"\"}", "admin").body()).getString("displayName"));
    }

    @Test
    void testDisplayNameChangeRefusals() {
        addReadRule("public/Acme/", "Manager");
        assertEquals(200, create(ORG, "Acme").status());

        assertEquals(403, rename(ORG + "/Acme", "{\"displayName\": \"Mine\"}", "user").status());
        Response renamed = rename(ORG + "/Acme", "{\"name\": \"Beta\"}", "admin");
        assertEquals(400, renamed.status());
        assertTrue(renamed.body().startsWith("Unknown field 'name'"), renamed.body());
        assertEquals(400, rename(ORG + "/Acme", "{}", "admin").status());
        assertEquals(400, rename(ORG + "/Acme", "not json", "admin").status());
        assertEquals(400, rename(ORG + "/Acme", new JsonObject().put("displayName", "x".repeat(129)).encode(), "admin").status());
        assertEquals(400, rename(ORG, "{\"displayName\": \"Shared\"}", "admin").status());
        assertEquals(404, rename(ORG + "/Missing", "{\"displayName\": \"Missing\"}", "admin").status());
        assertEquals("Acme", read(ORG + "/Acme", false).getString("displayName"));
    }

    @Test
    void testMovesStayInsideTheirUnit() {
        addReadRule("public/Acme/", "Manager");
        addReadRule("public/Acme/Risk/", "Manager");
        assertEquals(200, create(ORG, "Acme").status());
        assertEquals(200, create(ORG + "/Acme", "Risk").status());
        assertEquals(200, upload(HttpMethod.PUT, "/v1/files/public/Acme/Risk/a.txt", null, "a", "authorization", "admin").status());
        assertEquals(200, upload(HttpMethod.PUT, "/v1/files/public/Handbook/h.txt", null, "h", "authorization", "admin").status());

        Response renamed = move("files/public/Acme/Risk/a.txt", "files/public/Acme/Risk%20Renamed/a.txt");
        assertEquals(409, renamed.status(), renamed.body());
        assertTrue(renamed.body().contains("Moves between org units aren't allowed"), renamed.body());
        assertEquals(409, move("files/public/Handbook/h.txt", "files/public/Acme/h.txt").status());
        assertEquals(409, move("files/public/Acme/Risk/a.txt", "files/public/Loose/a.txt").status());

        assertEquals(200, move("files/public/Acme/Risk/a.txt", "files/public/Acme/Risk/Policies/a.txt").status());
        assertEquals(200, move("files/public/Handbook/h.txt", "files/public/Archive/h.txt").status());

        Response bucket = send(HttpMethod.GET, "/v1/bucket", null, null, "authorization", "admin");
        String own = new JsonObject(bucket.body()).getString("bucket");
        assertEquals(200, upload(HttpMethod.PUT, "/v1/files/" + own + "/mine.txt", null, "m", "authorization", "admin").status());
        assertEquals(200, move("files/" + own + "/mine.txt", "files/public/Acme/Risk/mine.txt").status());
    }

    @Test
    void testDeleteRefusesUnitThatHoldsContent() {
        addReadRule("public/Acme/", "Manager");
        assertEquals(200, create(ORG, "Acme").status());
        assertEquals(200, create(ORG + "/Acme", "Risk").status());
        assertEquals(200, upload(HttpMethod.PUT, "/v1/files/public/Acme/Risk/Templates/memo.txt", null, "m", "authorization", "admin").status());

        Response refused = remove(ORG + "/Acme/Risk", false);
        assertEquals(409, refused.status(), refused.body());
        assertTrue(refused.body().contains("files/public/Acme/Risk/Templates/memo.txt"), refused.body());
        Response refusedRecursive = remove(ORG + "/Acme", true);
        assertEquals(409, refusedRecursive.status(), refusedRecursive.body());
        assertTrue(refusedRecursive.body().contains("the units below it"), refusedRecursive.body());
        assertEquals(List.of("Risk"), childNames(read(ORG + "/Acme", false)));
    }

    @Test
    void testEmptySkillFoldersDontKeepUnitsFromBeingRemoved() {
        addReadRule("public/Acme/", "Manager");
        assertEquals(200, create(ORG, "Acme").status());
        assertEquals(200, send(HttpMethod.PUT, "/v2/skills/public/Acme/Team/", null, "", "authorization", "admin").status());

        Response removed = remove(ORG + "/Acme", false);
        assertEquals(200, removed.status(), removed.body());
    }

    @Test
    void testDeleteLeavesNothingOfAnEmptyUnit() {
        addReadRule("public/Acme/", "Manager");
        addReadRule("public/Acme/Risk/", "Manager");
        assertEquals(200, create(ORG, "Acme").status());
        assertEquals(200, create(ORG + "/Acme", "Risk").status());
        assertEquals(List.of(".dial_folder"), itemNames("files/public/Acme/Risk/"));

        Response removed = remove(ORG + "/Acme", true);
        assertEquals(200, removed.status(), removed.body());
        assertEquals(List.of("public/Acme/Risk", "public/Acme"), new JsonObject(removed.body()).getJsonArray("removed").getList());
        assertTrue(read(ORG, false).getJsonArray("items").isEmpty());
        assertTrue(!itemNames("files/public/").contains("Acme"));
        Response rules = send(HttpMethod.POST, "/v1/ops/publication/rule/list", null, "{\"url\": \"public/Acme/Risk/\"}",
                "authorization", "admin");
        assertEquals(200, rules.status(), rules.body());
        assertTrue(new JsonObject(rules.body()).getJsonObject("rules").containsKey("public/Acme/Risk/"), "read rules aren't the unit's");
    }

    @Test
    void testDeleteNeedsRecursiveForUnitsBelowAndKeepsRulesAbove() {
        addReadRule("public/Acme/", "Manager");
        assertEquals(200, create(ORG, "Acme").status());
        assertEquals(200, create(ORG + "/Acme", "Risk").status());

        Response refused = remove(ORG + "/Acme", false);
        assertEquals(409, refused.status(), refused.body());
        assertTrue(refused.body().contains("has units below it: Acme › Risk"), refused.body());

        assertEquals(200, remove(ORG + "/Acme/Risk", false).status());
        Response rules = send(HttpMethod.POST, "/v1/ops/publication/rule/list", null, "{\"url\": \"public/Acme/\"}",
                "authorization", "admin");
        assertEquals(200, rules.status(), rules.body());
        assertTrue(new JsonObject(rules.body()).getJsonObject("rules").containsKey("public/Acme/"), rules.body());
        assertEquals(List.of(), childNames(read(ORG + "/Acme", false)));
    }

    @Test
    void testDeleteUnitRefusals() {
        addReadRule("public/Acme/", "Manager");
        assertEquals(200, create(ORG, "Acme").status());

        assertEquals(400, remove(ORG, false).status());
        assertEquals(404, remove(ORG + "/Missing", false).status());
        assertEquals(403, send(HttpMethod.DELETE, "/v1/org-units/" + ORG + "/Acme", null, null, "authorization", "user").status());
        assertEquals(List.of("Acme"), childNames(read(ORG, false)));
        assertEquals(200, remove(ORG + "/Acme", false).status());
        assertEquals(404, remove(ORG + "/Acme", false).status());
    }

    private Response move(String source, String destination) {
        return send(HttpMethod.POST, "/v1/ops/resource/move", null,
                new JsonObject().put("sourceUrl", source).put("destinationUrl", destination).encode(), "authorization", "admin");
    }

    private void addReadRule(String folder, String title) {
        addRules(folder, "[{\"source\": \"title\", \"function\": \"EQUAL\", \"targets\": [\"%s\"]}]".formatted(title));
    }

    private void addRules(String folder, String rules) {
        Response created = send(HttpMethod.POST, "/v1/ops/publication/create", null, """
                {"targetFolder": "%s", "resources": [], "rules": %s}
                """.formatted(folder.replace(" ", "%20"), rules), "authorization", "admin");
        assertEquals(200, created.status(), created.body());
        String url = new JsonObject(created.body()).getString("url");
        Response approved = send(HttpMethod.POST, "/v1/ops/publication/approve", null,
                new JsonObject().put("url", url).encode(), "authorization", "admin");
        assertEquals(200, approved.status(), approved.body());
    }

    private Response rename(String path, String body, String caller) {
        return send(HttpMethod.PATCH, "/v1/org-units/" + path.replace(" ", "%20"), null, body, "authorization", caller);
    }

    private Response remove(String path, boolean recursive) {
        return send(HttpMethod.DELETE, "/v1/org-units/" + path.replace(" ", "%20"), recursive ? "recursive=true" : null, null,
                "authorization", "admin");
    }


    private Response create(String parent, String name) {
        return send(HttpMethod.POST, "/v1/org-units/" + parent.replace(" ", "%20"), null,
                new JsonObject().put("name", name).encode(), "authorization", "admin");
    }

    private JsonObject read(String path, boolean recursive) {
        Response response = send(HttpMethod.GET, "/v1/org-units/" + path.replace(" ", "%20"), recursive ? "recursive=true" : null,
                null, "authorization", "admin");
        assertEquals(200, response.status(), response.body());
        return new JsonObject(response.body());
    }

    private List<String> itemNames(String folderUrl) {
        Response response = send(HttpMethod.GET, "/v1/metadata/" + folderUrl, null, null, "authorization", "admin");
        assertEquals(200, response.status(), response.body());
        List<String> names = new ArrayList<>();
        JsonArray items = new JsonObject(response.body()).getJsonArray("items");
        for (int i = 0; i < items.size(); i++) {
            names.add(items.getJsonObject(i).getString("name"));
        }
        return names;
    }

    private static List<String> childNames(JsonObject unit) {
        List<String> names = new ArrayList<>();
        JsonArray items = unit.getJsonArray("items");
        for (int i = 0; i < items.size(); i++) {
            names.add(items.getJsonObject(i).getString("name"));
        }
        return names;
    }
}
