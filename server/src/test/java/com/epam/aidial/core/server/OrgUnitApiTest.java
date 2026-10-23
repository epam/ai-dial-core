package com.epam.aidial.core.server;

import com.epam.aidial.core.server.data.Rule;
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
        Response risk = create(ORG, "Risk");
        assertEquals(200, risk.status(), risk.body());
        JsonObject created = new JsonObject(risk.body());
        assertEquals("Risk", created.getString("name"));
        assertEquals("Risk", created.getString("displayName"));
        assertEquals(ORG + "/Risk", created.getString("path"));
        assertEquals("public/Risk/", created.getString("folder"));
        assertTrue(created.getJsonArray("items").isEmpty());

        Response usRisk = send(HttpMethod.POST, "/v1/org-units/" + ORG + "/Risk", null,
                "{\"name\": \"US Risk\", \"displayName\": \"US Risk Department\"}", "authorization", "admin");
        assertEquals(200, usRisk.status(), usRisk.body());
        assertEquals("public/Risk/US%20Risk/", new JsonObject(usRisk.body()).getString("folder"));

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
        assertEquals(200, create(ORG, "Risk").status());
        Response duplicate = create(ORG, "risk");
        assertEquals(409, duplicate.status());
        assertTrue(duplicate.body().contains("already has a unit named Risk"), duplicate.body());

        Response file = upload(HttpMethod.PUT, "/v1/files/public/Policies/limits.txt", null, "text", "authorization", "admin");
        assertEquals(200, file.status(), file.body());
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
            assertEquals(200, create(parent, name).status(), folder);
            parent = parent + "/" + name;
        }
        Response tooDeep = create(parent, "L5");
        assertEquals(409, tooDeep.status());
        assertTrue(tooDeep.body().contains("at most 4 levels"), tooDeep.body());
    }

    @Test
    void testNonAdmins() {
        // a read rule that lets the user in shows them nothing: only roles do
        addReadRule("public/Finance/", "Manager");
        assertEquals(200, create(ORG, "Risk").status());
        assertEquals(200, create(ORG, "Finance").status());
        assertEquals(200, assign(ORG + "/Risk", roleForUsers("viewer")).status());

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
        // a role at Risk shows the units directly below it by name
        assertEquals(List.of("Risk", "Audit"), childNames(new JsonObject(all.body())));
        assertEquals(200, send(HttpMethod.GET, "/v1/org-units/" + ORG + "/Risk/Audit", null, null, "authorization", "user").status());
        assertEquals(404, send(HttpMethod.GET, "/v1/org-units/" + ORG + "/Finance/Tax", null, null, "authorization", "user").status());
        assertEquals(List.of("Finance", "Tax", "Risk", "Audit"), childNames(read(ORG, true)));
    }

    @Test
    void testRulesGrantNothingInsideUnits() {
        addRules("public/Open/", "[{\"source\": \"roles\", \"function\": \"TRUE\"}]");
        assertEquals(200, create(ORG, "Open").status());
        assertEquals(200, upload(HttpMethod.PUT, "/v1/files/public/Open/a.txt", null, "a", "authorization", "admin").status());
        assertEquals(200, upload(HttpMethod.PUT, "/v1/files/public/Outside/b.txt", null, "b", "authorization", "admin").status());

        assertEquals(403, send(HttpMethod.GET, "/v1/files/public/Open/a.txt", null, null, "authorization", "user").status());
        assertEquals(200, send(HttpMethod.GET, "/v1/files/public/Outside/b.txt", null, null, "authorization", "user").status());

        assertEquals(200, assign(ORG + "/Open", roleForUsers("viewer")).status());
        assertEquals(200, send(HttpMethod.GET, "/v1/files/public/Open/a.txt", null, null, "authorization", "user").status());
        assertEquals(403, upload(HttpMethod.PUT, "/v1/files/public/Open/c.txt", null, "c", "authorization", "user").status());
    }

    @Test
    void testDisplayNameIsChecked() {
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
        assertEquals(200, create(ORG, "Acme").status());
        assertEquals(List.of(".dial_folder"), itemNames("files/public/Acme/"));
        assertTrue(itemNames("files/public/").contains("Acme"));

        assertEquals(200, create(ORG + "/Acme", "Risk").status());
        assertEquals(List.of(".dial_folder"), itemNames("files/public/Acme/Risk/"));
    }

    @Test
    void testRatePathStaysWithDeployments() {
        assertEquals(200, create(ORG, "Acme").status());

        Response rate = send(HttpMethod.POST, "/v1/org-units/public/Acme/rate", null, "{\"rate\": true}", "authorization", "admin");
        assertEquals(404, rate.status(), rate.body());
        assertTrue(rate.body().startsWith("Unknown deployment"), rate.body());
        assertEquals(List.of(), childNames(read(ORG + "/Acme", false)));
    }

    @Test
    void testReservedAndDottedNamesAreRefused() {
        for (String name : List.of("roles", "Role-Assignments", "rate", "Risk.")) {
            Response response = create(ORG, name);
            assertEquals(400, response.status(), name + " " + response.body());
        }
    }

    @Test
    void testAuthorIsTheCreatorsDisplayName() {
        Response created = create(ORG, "Risk");
        assertEquals(200, created.status());
        assertEquals("admin user", new JsonObject(created.body()).getString("author"));
        assertTrue(new JsonObject(created.body()).getValue("createdAt") instanceof Number, created.body());

        assertEquals(200, assign(ORG + "/Risk", roleForUsers("member")).status());
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
        assertEquals(200, create(ORG, "Acme").status());
        assertEquals(200, create(ORG + "/Acme", "Risk").status());
        assertEquals(List.of(".dial_folder"), itemNames("files/public/Acme/Risk/"));
        // nothing stores rules inside a unit any more: seed one as a node of an older version would have
        Rule rule = new Rule();
        rule.setSource("roles");
        rule.setFunction(Rule.Function.TRUE);
        dial.getProxy().getRuleService().storeRules("public/Acme/Risk/", List.of(rule));

        Response removed = remove(ORG + "/Acme", true);
        assertEquals(200, removed.status(), removed.body());
        assertEquals(List.of("public/Acme/Risk", "public/Acme"), new JsonObject(removed.body()).getJsonArray("removed").getList());
        assertTrue(read(ORG, false).getJsonArray("items").isEmpty());
        assertTrue(!itemNames("files/public/").contains("Acme"));
        Response rules = send(HttpMethod.POST, "/v1/ops/publication/rule/list", null, "{\"url\": \"public/Acme/Risk/\"}",
                "authorization", "admin");
        assertEquals(200, rules.status(), rules.body());
        assertTrue(new JsonObject(rules.body()).getJsonObject("rules").isEmpty(), rules.body());
    }

    @Test
    void testDeleteNeedsRecursiveForUnitsBelow() {
        assertEquals(200, create(ORG, "Acme").status());
        assertEquals(200, create(ORG + "/Acme", "Risk").status());

        Response refused = remove(ORG + "/Acme", false);
        assertEquals(409, refused.status(), refused.body());
        assertTrue(refused.body().contains("has units below it: Acme › Risk"), refused.body());

        assertEquals(200, remove(ORG + "/Acme/Risk", false).status());
        assertEquals(List.of(), childNames(read(ORG + "/Acme", false)));
    }

    @Test
    void testUnitsNeedNoReadRule() {
        assertEquals(200, create(ORG, "Acme").status());
        assertEquals(200, create(ORG + "/Acme", "Risk").status());
        assertEquals(200, upload(HttpMethod.PUT, "/v1/files/public/Acme/Risk/a.txt", null, "a", "authorization", "admin").status());
        assertEquals(403, send(HttpMethod.GET, "/v1/files/public/Acme/Risk/a.txt", null, null).status());
    }

    @Test
    void testDeleteUnitRefusals() {
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

    @Test
    void testRolesAreTheBuiltInOnes() {
        addReadRule("public/Acme/", "Manager");
        assertEquals(200, create(ORG, "Acme").status());

        JsonArray roles = roles(ORG + "/Acme");
        assertEquals(4, roles.size(), roles.encode());
        JsonObject admin = roles.getJsonObject(0);
        assertEquals("admin", admin.getString("name"));
        assertTrue(admin.getBoolean("system"));
        assertTrue(grants(admin, "publications/**"), admin.encode());
        JsonObject editor = roles.getJsonObject(1);
        assertEquals("editor", editor.getString("name"));
        assertTrue(grants(editor, "files/**"), editor.encode());
        assertTrue(!grants(editor, "publications/**") && !grants(editor, "role-assignments/**"), editor.encode());
        JsonObject viewer = roles.getJsonObject(2);
        assertEquals("viewer", viewer.getString("name"));
        assertEquals(List.of("read"), viewer.getJsonArray("permissions").getJsonObject(0).getJsonArray("actions").getList());
        JsonObject member = roles.getJsonObject(3);
        assertEquals("member", member.getString("name"));
        assertEquals(1, member.getJsonArray("permissions").size(), member.encode());
        assertEquals("org-units/**", member.getJsonArray("permissions").getJsonObject(0).getString("pathPattern"), member.encode());

        assertEquals(403, send(HttpMethod.GET, "/v1/org-units/" + ORG + "/Acme/roles", null, null, "authorization", "user").status());
        assertEquals(404, send(HttpMethod.GET, "/v1/org-units/" + ORG + "/Beta/roles", null, null, "authorization", "admin").status());
        assertEquals(400, send(HttpMethod.GET, "/v1/org-units/" + ORG + "/roles", null, null, "authorization", "admin").status());
    }

    @Test
    @DialConfigLocation("dial-config/org-unit-roles.json")
    void testRolesComeFromTheConfig() {
        addReadRule("public/Acme/", "Manager");
        assertEquals(200, create(ORG, "Acme").status());

        JsonArray roles = roles(ORG + "/Acme");
        assertEquals(List.of("admin", "editor", "viewer", "member", "auditor", "manager"), roles.stream().map(role -> ((JsonObject) role).getString("name")).toList());
        JsonObject editor = roles.getJsonObject(1);
        assertEquals("Changes files only", editor.getString("description"));
        assertEquals(1, editor.getJsonArray("permissions").size(), editor.encode());
        Response auditor = assign(ORG + "/Acme",
                "{\"roleName\": \"auditor\", \"subject\": {\"type\": \"claimsPredicate\", \"source\": \"email\", \"function\": \"EQUAL\", \"targets\": [\"jane@acme.example\"]}}");
        assertEquals(200, auditor.status(), auditor.body());
    }

    @Test
    void testRoleAssignments() {
        addReadRule("public/Acme/", "Manager");
        assertEquals(200, create(ORG, "Acme").status());
        assertEquals(200, create(ORG + "/Acme", "Risk").status());

        String editors = "{\"roleName\": \"editor\", \"subject\": {\"type\": \"claimsPredicate\", \"source\": \"groups\", \"function\": \"EQUAL\", \"targets\": [\"risk-leads\"]}}";
        Response added = assign(ORG + "/Acme/Risk", editors);
        assertEquals(200, added.status(), added.body());
        JsonObject editor = new JsonObject(added.body());
        assertEquals("editor", editor.getString("roleName"));
        assertEquals(new JsonObject().put("type", "claimsPredicate").put("source", "groups").put("function", "EQUAL")
                        .put("targets", new JsonArray().add("risk-leads")),
                editor.getJsonObject("subject"));
        Response again = assign(ORG + "/Acme/Risk", editors);
        assertEquals(200, again.status(), again.body());
        assertEquals(editor.getString("id"), new JsonObject(again.body()).getString("id"));
        Response dotted = assign(ORG + "/Acme/Risk", editors.replace("\"groups\"", "\"realm_access.roles\""));
        assertEquals(200, dotted.status(), dotted.body());
        assertEquals(200, send(HttpMethod.DELETE, "/v1/org-units/" + ORG + "/Acme/Risk/role-assignments/" + new JsonObject(dotted.body()).getString("id"),
                null, null, "authorization", "admin").status());

        Response admin = assign(ORG + "/Acme/Risk",
                "{\"roleName\": \"admin\", \"subject\": {\"type\": \"claimsPredicate\", \"source\": \"email\", \"function\": \"EQUAL\", \"targets\": [\"jane@acme.example\"]}}");
        assertEquals(200, admin.status(), admin.body());

        JsonArray items = assignments(ORG + "/Acme/Risk");
        assertEquals(List.of("editor", "admin"), List.of(items.getJsonObject(0).getString("roleName"), items.getJsonObject(1).getString("roleName")));
        assertTrue(assignments(ORG + "/Acme").isEmpty());

        String editorUrl = "/v1/org-units/" + ORG + "/Acme/Risk/role-assignments/" + editor.getString("id");
        Response removed = send(HttpMethod.DELETE, editorUrl, null, null, "authorization", "admin");
        assertEquals(200, removed.status(), removed.body());
        assertEquals(editor.getString("id"), new JsonObject(removed.body()).getString("removed"));
        assertEquals("admin", assignments(ORG + "/Acme/Risk").getJsonObject(0).getString("roleName"));
        assertEquals(1, assignments(ORG + "/Acme/Risk").size());
        assertEquals(404, send(HttpMethod.DELETE, editorUrl, null, null, "authorization", "admin").status());

        assertEquals(200, remove(ORG + "/Acme/Risk", false).status());
        assertEquals(404, send(HttpMethod.GET, "/v1/org-units/" + ORG + "/Acme/Risk/role-assignments", null, null, "authorization", "admin").status());
        assertEquals(200, create(ORG + "/Acme", "Risk").status());
        assertTrue(assignments(ORG + "/Acme/Risk").isEmpty());
    }

    @Test
    void testUnitDocumentsAreNeverVisibleAsFiles() {
        assertEquals(200, create(ORG, "Acme").status());
        assertEquals(200, assign(ORG + "/Acme",
                "{\"roleName\": \"admin\", \"subject\": {\"type\": \"claimsPredicate\", \"source\": \"email\", \"function\": \"EQUAL\", "
                        + "\"targets\": [\"jane@acme.example\"]}}").status());
        String id = read(ORG + "/Acme", false).getString("id");
        ResourceDescriptor policy = ResourceDescriptorFactory.fromDecoded(ResourceTypes.ORG_UNITS, ResourceDescriptor.PUBLIC_BUCKET,
                ResourceDescriptor.PUBLIC_LOCATION, id);
        assertTrue(dial.getProxy().getResourceService().getResource(policy).contains("jane@acme.example"));

        for (String document : List.of("org_units/public/" + id, "org_units/public/units")) {
            for (String caller : List.of("admin", "user")) {
                assertEquals(404, send(HttpMethod.GET, "/v1/" + document, null, null, "authorization", caller).status(), document);
                assertEquals(404, send(HttpMethod.GET, "/v1/metadata/" + document, null, null, "authorization", caller).status(), document);
                assertEquals(404, send(HttpMethod.DELETE, "/v1/" + document, null, null, "authorization", caller).status(), document);
                assertEquals(400, move(document, "files/public/Acme/stolen.json").status(), document);
            }
        }
        assertEquals(List.of(".dial_folder"), itemNames("files/public/Acme/"));
        assertTrue(!itemNames("files/public/").contains("org_units"));
        assertTrue(dial.getProxy().getResourceService().getResource(policy).contains("jane@acme.example"));
    }

    @Test
    void testRoleAssignmentRefusals() {
        assertEquals(200, create(ORG, "Acme").status());
        String valid = "{\"roleName\": \"admin\", \"subject\": {\"type\": \"claimsPredicate\", \"source\": \"email\", \"function\": \"EQUAL\", "
                + "\"targets\": [\"jane@acme.example\"]}}";

        assertEquals(400, assign(ORG + "/Acme", valid.replace("\"admin\"", "\"owner\"")).status());
        Response approver = assign(ORG + "/Acme", valid.replace("\"admin\"", "\"approver\""));
        assertEquals(400, approver.status());
        assertEquals("roleName is one of admin, editor, viewer, member", approver.body());
        assertEquals(400, assign(ORG + "/Acme", "{\"roleName\": \"admin\", \"subject\": {\"type\": \"user\", \"id\": \"jane\"}}").status());
        assertEquals(400, assign(ORG + "/Acme", "{\"roleName\": \"admin\", \"subject\": {\"type\": \"any\"}}").status());
        assertEquals(400, assign(ORG + "/Acme", "{\"roleName\": \"admin\"}").status());
        assertEquals(400, assign(ORG + "/Acme", valid.replace("jane@acme.example", " ")).status());
        assertEquals(400, assign(ORG + "/Acme", valid.replace("[\"jane@acme.example\"]", "[]")).status());
        assertEquals(400, assign(ORG + "/Acme", valid.replace(", \"targets\": [\"jane@acme.example\"]", "")).status());
        for (String function : List.of("CONTAIN", "REGEX", "TRUE", "FALSE", "LIKE")) {
            assertEquals(400, assign(ORG + "/Acme", valid.replace("\"EQUAL\"", "\"" + function + "\"")).status(), function);
        }
        assertEquals(400, assign(ORG + "/Acme", valid.replace("\"function\": \"EQUAL\", ", "")).status());
        assertEquals(400, assign(ORG + "/Acme", valid.replace("\"source\": \"email\", ", "")).status());
        for (String source : List.of("$.email", "$[?(@.email =~ /^((.+)+)+X$/)]", "email[0]", "a b", ".email", "email.", "x".repeat(65))) {
            assertEquals(400, assign(ORG + "/Acme", valid.replace("\"email\"", "\"" + source + "\"")).status(), source);
        }
        assertEquals(400, assign(ORG + "/Acme", valid.replace("\"source\"", "\"issuer\": \"https://idp\", \"source\"")).status());
        assertEquals(400, assign(ORG + "/Acme", "not json").status());
        assertEquals(400, assign(ORG, valid).status());
        assertEquals(404, assign(ORG + "/Missing", valid).status());
        assertEquals(400, send(HttpMethod.GET, "/v1/org-units/public/Acme%2FRisk/role-assignments", null, null, "authorization", "admin").status());

        assertEquals(403, send(HttpMethod.POST, "/v1/org-units/" + ORG + "/Acme/role-assignments", null, valid, "authorization", "user").status());
        assertEquals(403, send(HttpMethod.GET, "/v1/org-units/" + ORG + "/Acme/role-assignments", null, null, "authorization", "user").status());
        assertEquals(403, send(HttpMethod.DELETE, "/v1/org-units/" + ORG + "/Acme/role-assignments/x", null, null, "authorization", "user").status());
        assertTrue(assignments(ORG + "/Acme").isEmpty());
    }

    @Test
    void testUnitAdminsManageTheirOwnUnitOnly() {
        assertEquals(200, create(ORG, "Acme").status());
        assertEquals(200, create(ORG + "/Acme", "Risk").status());
        assertEquals(200, assign(ORG + "/Acme", roleFor("admin", "boss")).status());
        assertEquals(200, assign(ORG + "/Acme", roleFor("editor", "ed")).status());

        String acme = "/v1/org-units/" + ORG + "/Acme";
        assertEquals(200, send(HttpMethod.GET, acme + "/roles", null, null, "authorization", "azp:boss").status());
        assertEquals(200, send(HttpMethod.GET, acme + "/role-assignments", null, null, "authorization", "azp:boss").status());
        Response added = send(HttpMethod.POST, acme + "/role-assignments", null, roleFor("admin", "new"), "authorization", "azp:boss");
        assertEquals(200, added.status(), added.body());
        String id = new JsonObject(added.body()).getString("id");
        assertEquals(400, send(HttpMethod.POST, acme + "/role-assignments", null, roleFor("owner", "new"), "authorization", "azp:boss").status());
        for (String body : List.of("{}", roleFor("editor", "new").replace("\"editor\"", "null"), "{\"roleName\": \"editor\"}")) {
            Response invalid = send(HttpMethod.POST, acme + "/role-assignments", null, body, "authorization", "azp:boss");
            assertEquals(400, invalid.status(), body + " " + invalid.body());
        }
        assertEquals(200, send(HttpMethod.DELETE, acme + "/role-assignments/" + id, null, null, "authorization", "azp:boss").status());
        assertEquals(404, send(HttpMethod.DELETE, acme + "/role-assignments/" + id, null, null, "authorization", "azp:boss").status());

        String risk = acme + "/Risk";
        assertEquals(403, send(HttpMethod.GET, risk + "/role-assignments", null, null, "authorization", "azp:boss").status());
        assertEquals(403, send(HttpMethod.POST, risk + "/role-assignments", null, roleFor("editor", "new"), "authorization", "azp:boss").status());
        assertEquals(403, send(HttpMethod.GET, risk + "/roles", null, null, "authorization", "azp:boss").status());
        assertEquals(403, send(HttpMethod.GET, acme + "/Missing/role-assignments", null, null, "authorization", "azp:boss").status());
        assertEquals(403, send(HttpMethod.GET, "/v1/org-units/elsewhere/Acme/role-assignments", null, null, "authorization", "azp:boss").status());

        for (String caller : List.of("azp:ed", "azp:other", "user")) {
            assertEquals(403, send(HttpMethod.GET, acme + "/role-assignments", null, null, "authorization", caller).status(), caller);
            assertEquals(403, send(HttpMethod.GET, acme + "/roles", null, null, "authorization", caller).status(), caller);
            assertEquals(403, send(HttpMethod.POST, acme + "/role-assignments", null, "not json", "authorization", caller).status(), caller);
            assertEquals(403, send(HttpMethod.DELETE, acme + "/role-assignments/" + assignments(ORG + "/Acme").getJsonObject(0).getString("id"),
                    null, null, "authorization", caller).status(), caller);
        }
        assertEquals(2, assignments(ORG + "/Acme").size());

        String own = assignments(ORG + "/Acme").getJsonObject(0).getString("id");
        assertEquals(200, send(HttpMethod.DELETE, acme + "/role-assignments/" + own, null, null, "authorization", "azp:boss").status());
        assertEquals(403, send(HttpMethod.GET, acme + "/role-assignments", null, null, "authorization", "azp:boss").status());
    }

    @Test
    @DialConfigLocation("dial-config/org-unit-roles.json")
    void testUnitManagersGiveAndTakeOnlyWhatTheyHold() {
        assertEquals(200, create(ORG, "Acme").status());
        assertEquals(200, assign(ORG + "/Acme", roleFor("manager", "lead")).status());
        assertEquals(200, assign(ORG + "/Acme", roleFor("admin", "boss")).status());
        String bossId = assignments(ORG + "/Acme").getJsonObject(1).getString("id");

        String url = "/v1/org-units/" + ORG + "/Acme/role-assignments";
        assertEquals(200, send(HttpMethod.POST, url, null, roleFor("editor", "ed"), "authorization", "azp:lead").status());
        assertEquals(200, send(HttpMethod.POST, url, null, roleFor("manager", "lead2"), "authorization", "azp:lead").status());
        for (String role : List.of("admin", "auditor")) {
            Response refused = send(HttpMethod.POST, url, null, roleFor(role, "x"), "authorization", "azp:lead");
            assertEquals(403, refused.status(), role);
            assertEquals("You can give only roles whose permissions you hold in this unit", refused.body());
        }
        Response kept = send(HttpMethod.DELETE, url + "/" + bossId, null, null, "authorization", "azp:lead");
        assertEquals(403, kept.status(), kept.body());
        assertEquals(4, assignments(ORG + "/Acme").size());
        assertEquals(200, send(HttpMethod.POST, url, null, roleFor("auditor", "x"), "authorization", "azp:boss").status());
    }

    private Response assign(String path, String body) {
        return send(HttpMethod.POST, "/v1/org-units/" + path.replace(" ", "%20") + "/role-assignments", null, body, "authorization", "admin");
    }

    private static String roleFor(String roleName, String azp) {
        return "{\"roleName\": \"%s\", \"subject\": {\"type\": \"claimsPredicate\", \"source\": \"azp\", \"function\": \"EQUAL\", \"targets\": [\"%s\"]}}"
                .formatted(roleName, azp);
    }

    private JsonArray assignments(String path) {
        Response response = send(HttpMethod.GET, "/v1/org-units/" + path.replace(" ", "%20") + "/role-assignments", null, null,
                "authorization", "admin");
        assertEquals(200, response.status(), response.body());
        return new JsonObject(response.body()).getJsonArray("items");
    }

    @Test
    void testCreateClearsTheFoldersReadRules() {
        addReadRule("public/Acme/", "Manager");
        addReadRule("public/Acme/Old/", "Manager");
        addReadRule("public/Handbook/", "Manager");
        assertEquals(200, create(ORG, "Acme").status());

        Response rules = send(HttpMethod.POST, "/v1/ops/publication/rule/list", null, "{\"url\": \"public/Acme/Old/\"}", "authorization", "admin");
        assertEquals("{\"rules\":{}}", rules.body());
        Response kept = send(HttpMethod.POST, "/v1/ops/publication/rule/list", null, "{\"url\": \"public/Handbook/\"}", "authorization", "admin");
        assertTrue(kept.body().contains("public/Handbook/"), kept.body());
    }

    /**
     * The role for every caller with the DIAL role "user", which the test token "user" carries.
     */
    private static String roleForUsers(String roleName) {
        return "{\"roleName\": \"%s\", \"subject\": {\"type\": \"claimsPredicate\", \"source\": \"roles\", \"function\": \"EQUAL\", \"targets\": [\"user\"]}}"
                .formatted(roleName);
    }

    private JsonArray roles(String path) {
        Response response = send(HttpMethod.GET, "/v1/org-units/" + path + "/roles", null, null, "authorization", "admin");
        assertEquals(200, response.status(), response.body());
        return new JsonObject(response.body()).getJsonArray("items");
    }

    private static boolean grants(JsonObject role, String pathPattern) {
        return role.getJsonArray("permissions").stream().map(JsonObject.class::cast)
                .anyMatch(permission -> permission.getString("pathPattern").equals(pathPattern)
                        && permission.getJsonArray("actions").getList().equals(List.of("read", "write")));
    }

}
