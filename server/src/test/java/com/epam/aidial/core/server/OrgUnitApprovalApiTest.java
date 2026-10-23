package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit approvers deciding publication requests: "user" requests, "azp:approver1" approves Acme, "azp:other" approves nothing.
 */
public class OrgUnitApprovalApiTest extends ResourceBaseTest {

    private static final String APPROVER = "azp:approver1";
    private static final String OTHER = "azp:other";

    private String userBucket;

    @Override
    protected JsonObject additionalSettingsOverrides() {
        return new JsonObject().put("orgUnits", new JsonObject().put("enabled", true));
    }

    @BeforeEach
    void units() {
        addReadRule("public/Acme/");
        addReadRule("public/Acme/Risk/");
        assertEquals(200, send(HttpMethod.POST, "/v1/org-units/public", null, "{\"name\": \"Acme\"}", "authorization", "admin").status());
        assertEquals(200, send(HttpMethod.POST, "/v1/org-units/public/Acme", null, "{\"name\": \"Risk\"}", "authorization", "admin").status());
        assign("public/Acme", "{\"type\": \"claimsPredicate\", \"source\": \"azp\", \"function\": \"EQUAL\", \"targets\": [\"APPROVER1\"]}");
        userBucket = new JsonObject(send(HttpMethod.GET, "/v1/bucket", null, null, "authorization", "user").body()).getString("bucket");
    }

    @Test
    void testApproverReviewsAndApprovesIntoTheirUnit() {
        String url = request("public/Acme/", "Acme/a.txt", null);

        JsonArray pending = list(APPROVER);
        assertEquals(List.of(url), urls(pending));
        Response publication = decide("get", url, APPROVER);
        assertEquals(200, publication.status(), publication.body());
        String reviewUrl = new JsonObject(publication.body()).getJsonArray("resources").getJsonObject(0).getString("reviewUrl");
        Response review = send(HttpMethod.GET, "/v1/" + reviewUrl, null, null, "authorization", APPROVER);
        assertEquals(200, review.status(), review.body());
        assertEquals("a", review.body());
        assertEquals(403, send(HttpMethod.PUT, "/v1/" + reviewUrl, null, "changed", "authorization", APPROVER).status());

        Response approved = decide("approve", url, APPROVER);
        assertEquals(200, approved.status(), approved.body());
        assertEquals("APPROVED", new JsonObject(approved.body()).getString("status"));
        assertEquals(200, send(HttpMethod.GET, "/v1/files/public/Acme/a.txt", null, null, "authorization", "user").status());
        assertEquals(403, send(HttpMethod.GET, "/v1/" + reviewUrl, null, null, "authorization", APPROVER).status());
    }

    @Test
    void testApproverRejects() {
        String url = request("public/Acme/", "Acme/a.txt", null);
        Response rejected = send(HttpMethod.POST, "/v1/ops/publication/reject", null,
                new JsonObject().put("url", url).put("comment", "no").encode(), "authorization", APPROVER);
        assertEquals(200, rejected.status(), rejected.body());
        assertEquals("REJECTED", new JsonObject(rejected.body()).getString("status"));
    }

    @Test
    void testOthersStayRefused() {
        String url = request("public/Acme/", "Acme/a.txt", null);

        for (String caller : List.of(OTHER, "user")) {
            assertEquals(403, send(HttpMethod.POST, "/v1/ops/publication/list", null, "{\"url\": \"publications/public/\"}",
                    "authorization", caller).status(), caller);
        }
        assertEquals(403, decide("get", url, OTHER).status());
        assertEquals(403, decide("get", "publications/" + userBucket + "/missing", OTHER).status());
        assertEquals(403, decide("approve", url, OTHER).status());
        assertEquals(403, decide("reject", url, OTHER).status());
        assertEquals(403, decide("approve", url, "user").status());
        assertEquals(200, decide("get", url, "user").status());
        assertEquals(200, decide("approve", url, "admin").status());
    }

    @Test
    void testApproverLearnsNothingAboutRequestsOutsideTheirUnits() {
        String finalized = request("public/Handbook/", "Handbook/c.txt", null);
        assertEquals(200, decide("approve", finalized, "admin").status());
        String missing = "publications/" + userBucket + "/missing";

        for (String url : List.of(finalized, missing)) {
            for (String operation : List.of("approve", "reject", "get")) {
                Response response = decide(operation, url, APPROVER);
                assertEquals(403, response.status(), operation + " " + url + ": " + response.body());
                assertEquals("Forbidden resource: " + url, response.body());
            }
        }
    }

    @Test
    void testApproverIsRefusedOutsideTheirUnits() {
        String childUnit = request("public/Acme/Risk/", "Acme/Risk/a.txt", null);
        String deeperTarget = request("public/Acme/", "Acme/Risk/b.txt", null);
        String sharedSpace = request("public/Handbook/", "Handbook/c.txt", null);

        assertEquals(List.of(deeperTarget), urls(list(APPROVER)));
        for (String url : List.of(childUnit, deeperTarget, sharedSpace)) {
            assertEquals(403, decide("get", url, APPROVER).status(), url);
            assertEquals(403, decide("approve", url, APPROVER).status(), url);
        }

        assign("public/Acme/Risk", "{\"type\": \"claimsPredicate\", \"source\": \"azp\", \"function\": \"EQUAL\", \"targets\": [\"approver1\"]}");
        assertEquals(200, decide("approve", deeperTarget, APPROVER).status());
        assertEquals(200, decide("approve", childUnit, APPROVER).status());
        assertEquals(403, decide("approve", sharedSpace, APPROVER).status());
    }

    @Test
    void testRuleChangesStayWithAdmins() {
        String emptyRules = request("public/Acme/", "Acme/a.txt", "[]");
        String newRules = request("public/Acme/", "Acme/b.txt", "[{\"source\": \"roles\", \"function\": \"TRUE\"}]");

        assertEquals(200, decide("get", emptyRules, APPROVER).status());
        assertEquals(403, decide("approve", emptyRules, APPROVER).status());
        assertEquals(403, decide("approve", newRules, APPROVER).status());
        assertEquals(403, send(HttpMethod.GET, "/v1/files/public/Acme/a.txt", null, null).status());
    }

    @Test
    void testNoSelfApproval() {
        assign("public/Acme", "{\"type\": \"claimsPredicate\", \"source\": \"roles\", \"function\": \"EQUAL\", \"targets\": [\"user\"]}");
        String own = request("public/Acme/", "Acme/a.txt", null);

        assertEquals(List.of(own), urls(list("user")));
        assertEquals(403, decide("approve", own, "user").status());
        assertEquals(200, decide("approve", own, APPROVER).status());
    }

    @Test
    void testUnpublishIsApprovable() {
        assertEquals(200, decide("approve", request("public/Acme/", "Acme/a.txt", null), APPROVER).status());
        Response created = send(HttpMethod.POST, "/v1/ops/publication/create", null, """
                {"name": "remove", "targetFolder": "public/Acme/", "resources": [{"action": "DELETE", "targetUrl": "files/public/Acme/a.txt"}]}
                """, "authorization", "user");
        assertEquals(200, created.status(), created.body());

        assertEquals(200, decide("approve", new JsonObject(created.body()).getString("url"), APPROVER).status());
        assertEquals(404, send(HttpMethod.GET, "/v1/files/public/Acme/a.txt", null, null, "authorization", "user").status());
    }

    @Test
    void testRemovedAssignmentGrantsNothing() {
        String url = request("public/Acme/", "Acme/a.txt", null);
        assign("public/Acme/Risk", "{\"type\": \"claimsPredicate\", \"source\": \"azp\", \"function\": \"EQUAL\", \"targets\": [\"other\"]}");
        String otherUnit = request("public/Acme/Risk/", "Acme/Risk/a.txt", null);
        assertEquals(200, decide("approve", otherUnit, OTHER).status());

        Response removed = send(HttpMethod.DELETE, "/v1/org-units/public/Acme/role-assignments/" + assignments("public/Acme").get(0),
                null, null, "authorization", "admin");
        assertEquals(200, removed.status(), removed.body());
        assertEquals(403, decide("approve", url, APPROVER).status());
    }

    private String request(String targetFolder, String target, String rules) {
        String name = target.substring(target.lastIndexOf('/') + 1);
        String source = "files/" + userBucket + "/" + target.replace("/", "-");
        assertEquals(200, upload(HttpMethod.PUT, "/v1/" + source, null, name.substring(0, 1), "authorization", "user").status());
        JsonObject body = new JsonObject().put("name", target).put("targetFolder", targetFolder)
                .put("resources", new JsonArray().add(new JsonObject().put("action", "ADD").put("sourceUrl", source)
                        .put("targetUrl", "files/public/" + target)));
        if (rules != null) {
            body.put("rules", new JsonArray(rules));
        }
        Response created = send(HttpMethod.POST, "/v1/ops/publication/create", null, body.encode(), "authorization", "user");
        assertEquals(200, created.status(), created.body());
        return new JsonObject(created.body()).getString("url");
    }

    private Response decide(String operation, String url, String caller) {
        return send(HttpMethod.POST, "/v1/ops/publication/" + operation, null, new JsonObject().put("url", url).encode(),
                "authorization", caller);
    }

    private JsonArray list(String caller) {
        Response response = send(HttpMethod.POST, "/v1/ops/publication/list", null, "{\"url\": \"publications/public/\"}",
                "authorization", caller);
        assertEquals(200, response.status(), response.body());
        return new JsonObject(response.body()).getJsonArray("publications");
    }

    private static List<String> urls(JsonArray publications) {
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < publications.size(); i++) {
            urls.add(publications.getJsonObject(i).getString("url"));
        }
        return urls;
    }

    private void assign(String unit, String subject) {
        Response response = send(HttpMethod.POST, "/v1/org-units/" + unit + "/role-assignments", null,
                "{\"roleName\": \"admin\", \"subject\": %s}".formatted(subject), "authorization", "admin");
        assertEquals(200, response.status(), response.body());
    }

    private List<String> assignments(String unit) {
        JsonArray items = new JsonObject(send(HttpMethod.GET, "/v1/org-units/" + unit + "/role-assignments", null, null,
                "authorization", "admin").body()).getJsonArray("items");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            ids.add(items.getJsonObject(i).getString("id"));
        }
        return ids;
    }

    private void addReadRule(String folder) {
        Response created = send(HttpMethod.POST, "/v1/ops/publication/create", null, """
                {"targetFolder": "%s", "resources": [], "rules": [{"source": "title", "function": "EQUAL", "targets": ["Manager"]}]}
                """.formatted(folder), "authorization", "admin");
        assertEquals(200, created.status(), created.body());
        Response approved = decide("approve", new JsonObject(created.body()).getString("url"), "admin");
        assertTrue(approved.status() == 200, approved.body());
    }
}
