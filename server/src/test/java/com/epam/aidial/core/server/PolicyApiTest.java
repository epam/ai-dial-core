package com.epam.aidial.core.server;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.policy.Policy;
import com.epam.aidial.core.server.service.policy.PolicyRecordCodec;
import com.epam.aidial.core.server.service.policy.PolicyService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Admin API of policies and policy assignments: {@code /v1/policies/platform/...},
 * {@code /v1/policy_assignments/platform/...} and the two manifest kinds of {@code /v1/admin/apply}.
 */
public class PolicyApiTest extends ResourceBaseTest {

    private static final String POLICIES = "/v1/policies/platform/";
    private static final String ASSIGNMENTS = "/v1/policy_assignments/platform/";

    private static final String GRANT_POLICY = """
            { "priority": 10, "kind": "budget", "quantity": "cost",
              "object": ["opus", "sonnet"], "per": ["subject"], "cost": { "month": 500 } }
            """;

    private static final String ALICE = """
            [ { "source": "sub", "function": "EQUAL", "targets": ["alice"] } ]""";

    private static String policy(int priority, String object, String extra) {
        return """
                { "priority": %d, "kind": "budget", "quantity": "cost",
                  "object": %s, "per": ["subject"], "cost": { "month": 100 }%s }
                """.formatted(priority, object, extra.isEmpty() ? "" : ", " + extra);
    }

    private static String assignment(String policy, String subject, String costObject) {
        return """
                { "policy": "%s"%s%s }
                """.formatted(policy, subject == null ? "" : ", \"subject\": " + subject,
                costObject == null ? "" : ", \"costObject\": \"" + costObject + "\"");
    }

    private Response put(String url, String body, String... headers) {
        String[] all = new String[headers.length + 2];
        all[0] = "authorization";
        all[1] = "admin";
        System.arraycopy(headers, 0, all, 2, headers.length);
        return send(HttpMethod.PUT, url, null, body, all);
    }

    private Response get(String url) {
        return send(HttpMethod.GET, url, null, "", "authorization", "admin");
    }

    private Response delete(String url) {
        return send(HttpMethod.DELETE, url, null, "", "authorization", "admin");
    }

    @SneakyThrows
    private static JsonNode json(Response response) {
        return ProxyUtil.MAPPER.readTree(response.body());
    }

    private static void verifyRejected(Response response, int status, String message) {
        assertEquals(status, response.status(), () -> "Actual response body: " + response.body());
        assertTrue(response.body().contains(message), () -> "Expected '" + message + "' in: " + response.body());
    }

    @Test
    void testRecordsAreCreatedReadUpdatedAndDeleted() {
        Response created = put(POLICIES + "crud-grant", GRANT_POLICY);
        verify(created, 200);
        assertEquals("crud-grant", json(created).get("name").asText());
        assertNotNull(created.headers().get("etag"));

        JsonNode stored = json(get(POLICIES + "crud-grant"));
        assertEquals("crud-grant", stored.get("id").asText());
        assertEquals(10, stored.get("priority").asInt());
        assertEquals("budget", stored.get("kind").asText());
        assertEquals("cost", stored.get("quantity").asText());
        assertEquals("opus", stored.get("object").get(0).asText());
        assertEquals("{\"month\":500}", stored.get("cost").toString());

        verify(put(ASSIGNMENTS + "crud-a/grant-1", assignment("crud-grant", ALICE, "crud-a")), 200);
        JsonNode assignment = json(get(ASSIGNMENTS + "crud-a/grant-1"));
        assertEquals("crud-a/grant-1", assignment.get("id").asText());
        assertEquals("crud-grant", assignment.get("policy").asText());
        assertEquals("alice", assignment.get("subject").get(0).get("targets").get(0).asText());

        verify(put(ASSIGNMENTS + "crud-a/grant-1", """
                { "policy": "crud-grant", "costObject": "crud-a", "ticket": "T-1", "description": "changed",
                  "subject": [ { "source": "sub", "function": "EQUAL", "targets": ["bob"] } ] }"""), 200);
        JsonNode updated = json(get(ASSIGNMENTS + "crud-a/grant-1"));
        assertEquals("bob", updated.get("subject").get(0).get("targets").get(0).asText());
        assertEquals("T-1", updated.get("ticket").asText());

        verify(delete(ASSIGNMENTS + "crud-a/grant-1"), 204);
        verify(get(ASSIGNMENTS + "crud-a/grant-1"), 404);
        verify(delete(ASSIGNMENTS + "crud-a/grant-1"), 404);
        verify(delete(POLICIES + "crud-grant"), 204);
        verify(get(POLICIES + "crud-grant"), 404);
    }

    @Test
    void testWriteAndDeleteReachTheConfigOfTheWriterPod() {
        verify(put(POLICIES + "cfg-grant", GRANT_POLICY), 200);
        verify(put(ASSIGNMENTS + "cfg-a/grant-1", assignment("cfg-grant", ALICE, "cfg-a")), 200);

        Config config = dial.getProxy().getConfigStore().get();
        assertTrue(config.getPolicies().containsKey("cfg-grant"));
        assertTrue(config.getPolicyAssignments().containsKey("cfg-a/grant-1"));
        assertEquals("cfg-a/grant-1", config.getCostObjectIndex().getInstances("cfg-a").get(0).id());

        verify(delete(ASSIGNMENTS + "cfg-a/grant-1"), 204);

        config = dial.getProxy().getConfigStore().get();
        assertFalse(config.getPolicyAssignments().containsKey("cfg-a/grant-1"));
        assertTrue(config.getCostObjectIndex().getInstances("cfg-a").isEmpty());
        verify(delete(POLICIES + "cfg-grant"), 204);
        assertFalse(dial.getProxy().getConfigStore().get().getPolicies().containsKey("cfg-grant"));
    }

    @Test
    void testApplyReachesTheConfigOfTheWriterPod() {
        String body = """
                { "manifests": [
                  { "kind": "PolicyAssignment", "name": "policy_assignments/platform/cfgapply-a/grant-1",
                    "spec": { "policy": "cfgapply-grant", "costObject": "cfgapply-a", "subject": %s } },
                  { "kind": "Policy", "name": "policies/platform/cfgapply-grant", "spec": %s }
                ] }
                """.formatted(ALICE, GRANT_POLICY);

        verify(send(HttpMethod.POST, "/v1/admin/apply", null, body, "authorization", "admin"), 200);

        Config config = dial.getProxy().getConfigStore().get();
        assertEquals("cfgapply-a/grant-1", config.getCostObjectIndex().getInstances("cfgapply-a").get(0).id());
    }

    @Test
    void testAssignmentWrittenWhileThisPodLagsBehindIsStoredAndReachesTheConfig() {
        storePolicyBypassingTheConfig("lag-grant");
        assertFalse(dial.getProxy().getConfigStore().get().getPolicies().containsKey("lag-grant"));

        verify(put(ASSIGNMENTS + "lag-a/grant-1", assignment("lag-grant", ALICE, "lag-a")), 200);
        verify(get(ASSIGNMENTS + "lag-a/grant-1"), 200);

        Config config = awaitConfig(next -> next.getPolicyAssignments().containsKey("lag-a/grant-1"));
        assertEquals("lag-a/grant-1", config.getCostObjectIndex().getInstances("lag-a").get(0).id());
    }

    @Test
    void testApplyWhileThisPodLagsBehindReachesTheConfig() {
        storePolicyBypassingTheConfig("lagapply-grant");
        String body = """
                { "manifests": [
                  { "kind": "PolicyAssignment", "name": "policy_assignments/platform/lagapply-a/grant-1",
                    "spec": { "policy": "lagapply-grant", "costObject": "lagapply-a", "subject": %s } }
                ] }
                """.formatted(ALICE);

        verify(send(HttpMethod.POST, "/v1/admin/apply", null, body, "authorization", "admin"), 200);

        Config config = awaitConfig(next -> next.getPolicyAssignments().containsKey("lagapply-a/grant-1"));
        assertEquals("lagapply-a/grant-1", config.getCostObjectIndex().getInstances("lagapply-a").get(0).id());
    }

    /**
     * Stores the policy as another pod would: in blob storage only. This pod ignores the replica events of its own
     * writes, so its merged config misses the policy until a full rebuild.
     */
    @SneakyThrows
    private void storePolicyBypassingTheConfig(String path) {
        Policy policy = PolicyRecordCodec.parsePolicy(ProxyUtil.MAPPER.readTree(GRANT_POLICY));
        new PolicyService(dial.getProxy().getResourceService()).putPolicy(path, policy, EtagHeader.ANY, null);
    }

    @SneakyThrows
    private Config awaitConfig(Predicate<Config> condition) {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            Config config = dial.getProxy().getConfigStore().get();
            if (condition.test(config)) {
                return config;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("The merged config did not reach the expected state");
    }

    @Test
    void testProjectFolderIsListedByPrefix() {
        verify(put(POLICIES + "list-grant", GRANT_POLICY), 200);
        verify(put(ASSIGNMENTS + "list-a/one", assignment("list-grant", ALICE, "list-a")), 200);
        verify(put(ASSIGNMENTS + "list-a/two", assignment("list-grant", ALICE, "list-a")), 200);
        verify(put(ASSIGNMENTS + "list-b/other", assignment("list-grant", ALICE, "list-b")), 200);

        JsonNode listing = json(get("/v1/metadata/policy_assignments/platform/list-a/"));
        assertEquals(2, listing.get("items").size());
        assertEquals("policy_assignments/platform/list-a/one", listing.get("items").get(0).get("url").asText());
        assertEquals("policy_assignments/platform/list-a/two", listing.get("items").get(1).get("url").asText());

        JsonNode policies = json(get("/v1/metadata/policies/platform/"));
        assertTrue(policies.get("items").toString().contains("policies/platform/list-grant"));
        verify(get("/v1/metadata/policy_assignments/platform/no-such-folder/"), 404);
    }

    @Test
    void testNonAdminIsRefused() {
        verify(send(HttpMethod.PUT, POLICIES + "denied", null, GRANT_POLICY), 403);
        verify(send(HttpMethod.GET, POLICIES + "denied"), 403);
        verify(send(HttpMethod.DELETE, POLICIES + "denied"), 403);
        verify(send(HttpMethod.PUT, ASSIGNMENTS + "denied-a/one", null, assignment("denied", ALICE, "denied-a")), 403);
        verify(send(HttpMethod.GET, "/v1/metadata/policies/platform/"), 403);
        verify(send(HttpMethod.GET, "/v1/metadata/policy_assignments/platform/denied-a/"), 403);
    }

    @Test
    void testOnlyThePlatformBucketExists() {
        verify(send(HttpMethod.GET, "/v1/policies/public/some", null, "", "authorization", "admin"), 404);
        verify(send(HttpMethod.GET, "/v1/policy_assignments/public/a/b", null, "", "authorization", "admin"), 404);
    }

    @Test
    void testPostIsNotAllowed() {
        Response response = send(HttpMethod.POST, POLICIES + "post", null, GRANT_POLICY, "authorization", "admin");
        verify(response, 405);
        assertEquals("GET, PUT, DELETE", response.headers().get("Allow"));
    }

    @Test
    void testConditionalWrites() {
        Response created = put(POLICIES + "etag-grant", GRANT_POLICY, "If-None-Match", "*");
        verify(created, 200);
        verify(put(POLICIES + "etag-grant", GRANT_POLICY, "If-None-Match", "*"), 412);
        verify(put(POLICIES + "etag-grant", GRANT_POLICY, "If-Match", "stale"), 412);
        verify(put(POLICIES + "etag-grant", GRANT_POLICY, "If-Match", created.headers().get("etag")), 200);
        String etag = get(POLICIES + "etag-grant").headers().get("etag");
        verify(send(HttpMethod.GET, POLICIES + "etag-grant", null, "", "authorization", "admin", "If-None-Match", etag), 304);
        verify(send(HttpMethod.DELETE, POLICIES + "etag-grant", null, "", "authorization", "admin", "If-Match", "stale"), 412);
    }

    @Test
    void testPathRules() {
        verify(put(POLICIES + "a/b/c", GRANT_POLICY), 400);
        verifyRejected(put(POLICIES + "a/b/c", GRANT_POLICY), 400, "At most one folder level");
        verifyRejected(put(POLICIES + "a%25b", GRANT_POLICY), 400, "must not contain %");
        verifyRejected(put(POLICIES + "a/", GRANT_POLICY), 400, "must not be empty or a folder");
        verifyRejected(put(POLICIES + "/b", GRANT_POLICY), 400, "Invalid path element");
        verifyRejected(put(POLICIES + "a%20b", GRANT_POLICY), 400, "Invalid path element");
        verifyRejected(put(ASSIGNMENTS + "no-folder", assignment("p", ALICE, "x")), 400, "<costObject>/<id>");
        verifyRejected(put(ASSIGNMENTS + "a/b/c", assignment("p", ALICE, "a")), 400, "At most one folder level");
        verifyRejected(put(POLICIES + "x".repeat(500) + "/" + "y".repeat(500), GRANT_POLICY), 400, "exceeds max allowed size");
        verify(get(POLICIES + "a/b/c"), 400);
    }

    // V1

    @Test
    void testPolicyMustExistAndBelongToTheCostObject() {
        verifyRejected(put(ASSIGNMENTS + "v1-a/one", assignment("v1-missing", ALICE, "v1-a")), 422,
                "Policy 'v1-missing' does not exist");

        verify(put(POLICIES + "v1-b/project-policy", policy(1, "\"*\"", "")), 200);
        verifyRejected(put(ASSIGNMENTS + "v1-a/two", assignment("v1-b/project-policy", ALICE, "v1-a")), 422,
                "project policy of cost object 'v1-b'");
        verify(put(ASSIGNMENTS + "v1-b/two", assignment("v1-b/project-policy", ALICE, "v1-b")), 200);
    }

    // V2, V3

    @Test
    void testSubjectIsPinnedExactlyOnce() {
        verify(put(POLICIES + "v2-open", GRANT_POLICY), 200);
        verify(put(POLICIES + "v2-pinned", policy(20, "[\"opus\"]", "\"subject\": " + ALICE)), 200);

        verifyRejected(put(ASSIGNMENTS + "v2-a/none", assignment("v2-open", null, "v2-a")), 422,
                "subject is pinned by neither");
        verifyRejected(put(ASSIGNMENTS + "v2-a/both", assignment("v2-pinned", ALICE, "v2-a")), 422,
                "subject is pinned by both");
        verify(put(ASSIGNMENTS + "v2-a/policy-pins", assignment("v2-pinned", null, "v2-a")), 200);
    }

    @Test
    void testCostObjectIsPinnedExactlyOnce() {
        verify(put(POLICIES + "v3-open", GRANT_POLICY), 200);
        verify(put(POLICIES + "v3-pinned", policy(20, "[\"opus\"]", "\"costObject\": \"v3-a\"")), 200);

        verifyRejected(put(ASSIGNMENTS + "v3-a/none", assignment("v3-open", ALICE, null)), 422,
                "costObject is pinned by neither");
        verifyRejected(put(ASSIGNMENTS + "v3-a/both", assignment("v3-pinned", ALICE, "v3-a")), 422,
                "costObject is pinned by both");
        verify(put(ASSIGNMENTS + "v3-a/policy-pins", assignment("v3-pinned", ALICE, null)), 200);
    }

    // V4

    @Test
    void testSubjectRulesAreChecked() {
        verify(put(POLICIES + "v4-open", GRANT_POLICY), 200);

        verifyRejected(put(ASSIGNMENTS + "v4-a/empty", assignment("v4-open", "[]", "v4-a")), 422, "at least one rule");
        verifyRejected(put(POLICIES + "v4-empty", policy(1, "\"*\"", "\"subject\": []")), 422, "at least one rule");
        verifyRejected(put(ASSIGNMENTS + "v4-a/function", assignment("v4-open",
                "[{\"source\": \"sub\", \"function\": \"STARTS_WITH\", \"targets\": [\"a\"]}]", "v4-a")), 400,
                "subject[0].function");
        verifyRejected(put(ASSIGNMENTS + "v4-a/regex", assignment("v4-open",
                "[{\"source\": \"sub\", \"function\": \"REGEX\", \"targets\": [\"(open\"]}]", "v4-a")), 422,
                "invalid pattern");
        verifyRejected(put(ASSIGNMENTS + "v4-a/targets", assignment("v4-open",
                "[{\"source\": \"sub\", \"function\": \"EQUAL\"}]", "v4-a")), 422, "needs non-empty targets");
        verify(put(ASSIGNMENTS + "v4-a/everyone", assignment("v4-open",
                "[{\"source\": \"sub\", \"function\": \"TRUE\"}]", "v4-a")), 200);
    }

    // V5

    @Test
    void testCostObjectMustMatchTheFolder() {
        verify(put(POLICIES + "v5-open", GRANT_POLICY), 200);

        verifyRejected(put(ASSIGNMENTS + "v5-a/other", assignment("v5-open", ALICE, "v5-b")), 422,
                "differs from the folder 'v5-a'");
        verifyRejected(put(ASSIGNMENTS + "v5-a/bad", assignment("v5-open", ALICE, "bad%name")), 422,
                "must not contain %");

        verify(put(POLICIES + "v5-pinned", policy(30, "[\"opus\"]", "\"costObject\": \"v5-a\"")), 200);
        verifyRejected(put(ASSIGNMENTS + "v5-b/pinned", assignment("v5-pinned", ALICE, null)), 422,
                "differs from the folder 'v5-b'");
        verifyRejected(put(POLICIES + "v5-b/wrong", policy(30, "[\"opus\"]", "\"costObject\": \"v5-a\"")), 422,
                "differs from the folder 'v5-b' of the policy");
    }

    @Test
    void testSelfApplyingPolicyLivesInTheFolderOfItsCostObject() {
        String selfApplying = policy(100, "\"*\"", """
                "subject": [ { "source": "roles", "function": "EQUAL", "targets": ["costLimit-1000"] } ],
                "costObject": "ORG" """);

        verifyRejected(put(POLICIES + "personal-standard", selfApplying), 422, "must live in the folder of its cost object");
        verify(put(POLICIES + "ORG/personal-standard", selfApplying), 200);
        assertEquals("ORG", json(get(POLICIES + "ORG/personal-standard")).get("costObject").asText());
    }

    @Test
    void testInstanceIdIsUniqueInTheFolder() {
        verify(put(POLICIES + "v5id-open", GRANT_POLICY), 200);
        verify(put(ASSIGNMENTS + "v5id-a/taken", assignment("v5id-open", ALICE, "v5id-a")), 200);

        String selfApplying = policy(50, "[\"opus\"]", "\"subject\": " + ALICE + ", \"costObject\": \"v5id-a\"");
        verifyRejected(put(POLICIES + "v5id-a/taken", selfApplying), 409, "already used by an assignment");

        verify(put(POLICIES + "v5id-a/self", selfApplying), 200);
        verifyRejected(put(ASSIGNMENTS + "v5id-a/self", assignment("v5id-open", ALICE, "v5id-a")), 409,
                "already used by a self-applying policy");
        // a policy of the same name that is not self-applying does not claim an instance id
        verify(put(POLICIES + "v5id-a/free", policy(60, "[\"opus\"]", "")), 200);
        verify(put(ASSIGNMENTS + "v5id-a/free", assignment("v5id-open", ALICE, "v5id-a")), 200);
    }

    // V6

    @Test
    void testBudgetQuantityMustBeCost() {
        verifyRejected(put(POLICIES + "v6-tokens", """
                { "priority": 1, "kind": "budget", "quantity": "tokens", "object": "*", "per": ["subject"],
                  "cost": { "month": 5 } }"""), 422, "a budget must have quantity 'cost', got 'tokens'");
        verifyRejected(put(POLICIES + "v6-requests", """
                { "priority": 1, "kind": "budget", "quantity": "requests", "object": "*", "per": ["subject"],
                  "cost": { "month": 5 } }"""), 422, "got 'requests'");
    }

    // V8

    @Test
    void testExpiryMustBeInTheFuture() {
        verify(put(POLICIES + "v8-open", GRANT_POLICY), 200);
        String past = Instant.now().minus(1, ChronoUnit.DAYS).toString();
        String future = Instant.now().plus(30, ChronoUnit.DAYS).toString();

        verifyRejected(put(ASSIGNMENTS + "v8-a/past", """
                { "policy": "v8-open", "costObject": "v8-a", "subject": %s, "expiresAt": "%s" }""".formatted(ALICE, past)),
                422, "expiresAt must be in the future");
        verifyRejected(put(ASSIGNMENTS + "v8-a/garbage", """
                { "policy": "v8-open", "costObject": "v8-a", "subject": %s, "expiresAt": "next week" }""".formatted(ALICE)),
                422, "expiresAt must be an ISO-8601");
        verify(put(ASSIGNMENTS + "v8-a/future", """
                { "policy": "v8-open", "costObject": "v8-a", "subject": %s, "expiresAt": "%s" }""".formatted(ALICE, future)), 200);
    }

    @Test
    void testAnExpiredAssignmentCanBeEditedWithoutTouchingItsExpiry() {
        verify(put(POLICIES + "v8e-open", GRANT_POLICY), 200);
        String soon = Instant.now().plus(1, ChronoUnit.SECONDS).toString();
        verify(put(ASSIGNMENTS + "v8e-a/one", """
                { "policy": "v8e-open", "costObject": "v8e-a", "subject": %s, "expiresAt": "%s" }""".formatted(ALICE, soon)), 200);
        waitUntilPast(soon);

        verify(put(ASSIGNMENTS + "v8e-a/one", """
                { "policy": "v8e-open", "costObject": "v8e-a", "subject": %s, "expiresAt": "%s", "ticket": "T-2" }"""
                .formatted(ALICE, soon)), 200);
        verifyRejected(put(ASSIGNMENTS + "v8e-a/one", """
                { "policy": "v8e-open", "costObject": "v8e-a", "subject": %s, "expiresAt": "%s" }"""
                .formatted(ALICE, Instant.now().minus(1, ChronoUnit.HOURS))), 422, "expiresAt must be in the future");
    }

    @SneakyThrows
    private static void waitUntilPast(String instant) {
        while (Instant.parse(instant).isAfter(Instant.now())) {
            Thread.sleep(50);
        }
    }

    // V9

    @Test
    void testUnknownFieldsAndPolicyFieldsOnAnAssignmentAreRejected() {
        verify(put(POLICIES + "v9-open", GRANT_POLICY), 200);

        verifyRejected(put(POLICIES + "v9-unknown", """
                { "priority": 1, "colour": "red" }"""), 400, "\"colour\": unrecognized field");
        verifyRejected(put(ASSIGNMENTS + "v9-a/unknown", """
                { "policy": "v9-open", "colour": "red" }"""), 400, "unrecognized field");
        verifyRejected(put(ASSIGNMENTS + "v9-a/priority", """
                { "policy": "v9-open", "costObject": "v9-a", "subject": %s, "priority": 5 }""".formatted(ALICE)), 400,
                "'priority' belongs to the policy");
        verifyRejected(put(ASSIGNMENTS + "v9-a/cost", """
                { "policy": "v9-open", "costObject": "v9-a", "subject": %s, "cost": { "month": 1 } }""".formatted(ALICE)), 400,
                "'cost' belongs to the policy");
        verifyRejected(put(ASSIGNMENTS + "v9-a/object", """
                { "policy": "v9-open", "costObject": "v9-a", "subject": %s, "object": "*" }""".formatted(ALICE)), 400,
                "'object' belongs to the policy");
        verifyRejected(put(POLICIES + "v9-week", """
                { "priority": 1, "kind": "budget", "quantity": "cost", "object": "*", "per": ["subject"],
                  "cost": { "week": 5 } }"""), 400, "cost.week");
        verifyRejected(put(POLICIES + "v9-id", policy(1, "\"*\"", "\"id\": \"other\"")), 422, "differs from the path");
        verifyRejected(put(POLICIES + "v9-array", "[]"), 400, "a JSON object is expected");
        verifyRejected(put(POLICIES + "v9-json", "{"), 400, "Invalid JSON");
    }

    // V10

    @Test
    void testPriorityIsRequiredAndAnInteger() {
        String rest = "\"kind\": \"budget\", \"quantity\": \"cost\", \"object\": \"*\", \"per\": [\"subject\"], \"cost\": {\"month\": 5}";

        verifyRejected(put(POLICIES + "v10-missing", "{" + rest + "}"), 422, "priority is required");
        verifyRejected(put(POLICIES + "v10-fraction", "{\"priority\": 10.5, " + rest + "}"), 400, "\"priority\"");
        verifyRejected(put(POLICIES + "v10-text", "{\"priority\": \"10\", " + rest + "}"), 400, "\"priority\"");
        verify(put(POLICIES + "v10-ok", "{\"priority\": -3, " + rest + "}"), 200);
    }

    // V11

    @Test
    void testBudgetsOfOneCostObjectMustNotTieInPriority() {
        verify(put(POLICIES + "v11-first", policy(5, "[\"opus\"]", "")), 200);
        verify(put(POLICIES + "v11-second", policy(5, "[\"opus\", \"sonnet\"]", "")), 200);
        verify(put(ASSIGNMENTS + "v11-a/one", assignment("v11-first", ALICE, "v11-a")), 200);

        // same priority, shared model, overlapping subject
        verifyRejected(put(ASSIGNMENTS + "v11-a/two", assignment("v11-second", ALICE, "v11-a")), 409,
                "has the same priority 5");
        // a second grant of the same policy is not a tie
        verify(put(ASSIGNMENTS + "v11-a/same-policy", assignment("v11-first", """
                [ { "source": "sub", "function": "EQUAL", "targets": ["alice"] } ]""", "v11-a")), 200);
        // another cost object is another budget
        verify(put(ASSIGNMENTS + "v11-b/two", assignment("v11-second", ALICE, "v11-b")), 200);
        // another subject cannot be paid by the same call
        verify(put(ASSIGNMENTS + "v11-a/bob", assignment("v11-second", """
                [ { "source": "sub", "function": "EQUAL", "targets": ["bob"] } ]""", "v11-a")), 200);
    }

    @Test
    void testDisjointObjectsAndDifferentPrioritiesDoNotTie() {
        verify(put(POLICIES + "v11o-gpt", policy(7, "[\"gpt\"]", "")), 200);
        verify(put(POLICIES + "v11o-claude", policy(7, "[\"opus\"]", "")), 200);
        verify(put(POLICIES + "v11o-all", policy(8, "\"*\"", "")), 200);
        verify(put(ASSIGNMENTS + "v11o-a/gpt", assignment("v11o-gpt", ALICE, "v11o-a")), 200);

        verify(put(ASSIGNMENTS + "v11o-a/claude", assignment("v11o-claude", ALICE, "v11o-a")), 200);
        verify(put(ASSIGNMENTS + "v11o-a/all", assignment("v11o-all", ALICE, "v11o-a")), 200);
    }

    @Test
    void testSelfApplyingPoliciesTieWithAssignments() {
        verify(put(POLICIES + "v11s-grant", policy(100, "\"*\"", "")), 200);
        String tier = """
                "subject": [ { "source": "roles", "function": "EQUAL", "targets": ["gold"] } ], "costObject": "v11s-a" """;
        verify(put(POLICIES + "v11s-a/gold", policy(100, "\"*\"", tier)), 200);
        verify(put(ASSIGNMENTS + "v11s-a/alice", assignment("v11s-grant", """
                [ { "source": "sub", "function": "EQUAL", "targets": ["alice"] } ]""", "v11s-a")), 409);

        String silver = """
                "subject": [ { "source": "roles", "function": "EQUAL", "targets": ["silver"] } ], "costObject": "v11s-a" """;
        // one caller can hold both roles: a tie even though the targets differ
        verifyRejected(put(POLICIES + "v11s-a/silver", policy(100, "\"*\"", silver)), 409, "has the same priority 100");
        // disjoint values of a claim other than roles: no tie even at the same priority
        verify(put(POLICIES + "v11s-b/gold", policy(100, "\"*\"", """
                "subject": [ { "source": "plan", "function": "EQUAL", "targets": ["gold"] } ], "costObject": "v11s-b" """)), 200);
        verify(put(POLICIES + "v11s-b/silver", policy(100, "\"*\"", """
                "subject": [ { "source": "plan", "function": "EQUAL", "targets": ["silver"] } ], "costObject": "v11s-b" """)), 200);
        // a self-applying policy that overlaps an existing one at the same priority
        verifyRejected(put(POLICIES + "v11s-a/gold-too", policy(100, "[\"opus\"]", tier)), 409, "has the same priority 100");
    }

    @Test
    void testAnExpiredAssignmentDoesNotTie() {
        verify(put(POLICIES + "v11x-first", policy(5, "\"*\"", "")), 200);
        verify(put(POLICIES + "v11x-second", policy(5, "\"*\"", "")), 200);
        String soon = Instant.now().plus(1, ChronoUnit.SECONDS).toString();
        verify(put(ASSIGNMENTS + "v11x-a/one", """
                { "policy": "v11x-first", "costObject": "v11x-a", "subject": %s, "expiresAt": "%s" }""".formatted(ALICE, soon)), 200);
        waitUntilPast(soon);

        verify(put(ASSIGNMENTS + "v11x-a/two", assignment("v11x-second", ALICE, "v11x-a")), 200);
    }

    // policy rules

    @Test
    void testPolicyAmountsAreChecked() {
        String head = "{\"priority\": 1, \"kind\": \"budget\", \"quantity\": \"cost\", \"object\": \"*\", \"per\": [\"subject\"], ";

        verifyRejected(put(POLICIES + "amount-zero", head + "\"cost\": {\"month\": 0}}"), 422, "cost.month must be greater than 0");
        verifyRejected(put(POLICIES + "amount-negative", head + "\"cost\": {\"day\": -5, \"month\": 10}}"), 422,
                "cost.day must be greater than 0");
        verifyRejected(put(POLICIES + "amount-none", head + "\"cost\": {}}"), 422, "at least one window");
        verifyRejected(put(POLICIES + "amount-missing", head.substring(0, head.length() - 2) + "}"), 422, "at least one window");
        verify(put(POLICIES + "amount-ok", head + "\"cost\": {\"day\": 1.5}}"), 200);
    }

    @Test
    void testPolicyAmountsKeepEveryDigit() {
        String head = "{\"priority\": 1, \"kind\": \"budget\", \"quantity\": \"cost\", \"object\": \"*\", \"per\": [\"subject\"], ";

        // more digits than a double holds, and trailing zeros
        verify(put(POLICIES + "amount-exact", head + "\"cost\": {\"day\": 12345678901234567.89, \"month\": 500.00}}"), 200);
        Response stored = get(POLICIES + "amount-exact");
        verify(stored, 200);
        assertTrue(stored.body().contains("12345678901234567.89"), () -> "Body: " + stored.body());
        assertTrue(stored.body().contains("500.00"), () -> "Body: " + stored.body());
    }

    @Test
    void testValuesOfLaterReleasesAreRejectedWithMessage() {
        String head = "{\"priority\": 1, \"kind\": \"budget\", \"quantity\": \"cost\", \"object\": \"*\", \"cost\": {\"month\": 5}, ";

        verifyRejected(put(POLICIES + "m2-per", head + "\"per\": [\"subject\", \"model\"]}"), 422, "not supported yet");
        verifyRejected(put(POLICIES + "m2-per-pool", head + "\"per\": [\"project\"]}"), 422, "not supported yet");
        for (String field : new String[] {"attachedTo", "harness", "broker", "packages"}) {
            verifyRejected(put(POLICIES + "m2-" + field, head + "\"per\": [\"subject\"], \"" + field + "\": [\"x\"]}"), 422,
                    "'" + field + "' is not supported yet");
        }
        verifyRejected(put(POLICIES + "m2-kind", head.replace("\"budget\"", "\"cap\"") + "\"per\": [\"subject\"]}"), 400, "\"kind\"");
    }

    @Test
    void testPolicyReferencedByAnAssignmentCannotBeDeleted() {
        verify(put(POLICIES + "del-grant", GRANT_POLICY), 200);
        verify(put(ASSIGNMENTS + "del-a/one", assignment("del-grant", ALICE, "del-a")), 200);
        verify(put(ASSIGNMENTS + "del-b/one", assignment("del-grant", ALICE, "del-b")), 200);

        verifyRejected(delete(POLICIES + "del-grant"), 409, "referenced by 2 assignment(s)");
        verify(get(POLICIES + "del-grant"), 200);

        verify(delete(ASSIGNMENTS + "del-a/one"), 204);
        verifyRejected(delete(POLICIES + "del-grant"), 409, "referenced by 1 assignment(s)");
        verify(delete(ASSIGNMENTS + "del-b/one"), 204);
        verify(delete(POLICIES + "del-grant"), 204);
    }

    @Test
    void testProjectPolicyIsOnlyReferencedFromItsFolder() {
        verify(put(POLICIES + "delp-a/project-policy", policy(1, "\"*\"", "")), 200);
        verify(put(ASSIGNMENTS + "delp-a/one", assignment("delp-a/project-policy", ALICE, "delp-a")), 200);
        verify(put(POLICIES + "delp-b/project-policy", policy(1, "\"*\"", "")), 200);

        verify(delete(POLICIES + "delp-b/project-policy"), 204);
        verify(delete(POLICIES + "delp-a/project-policy"), 409);
    }

    @Test
    void testChangingPolicyRescopesItsAssignments() {
        verify(put(POLICIES + "re-grant", policy(10, "[\"opus\"]", "")), 200);
        verify(put(POLICIES + "re-other", policy(20, "[\"opus\"]", "")), 200);
        verify(put(ASSIGNMENTS + "re-a/grant", assignment("re-grant", ALICE, "re-a")), 200);
        verify(put(ASSIGNMENTS + "re-a/other", assignment("re-other", ALICE, "re-a")), 200);

        // moving the grant onto the priority of the other policy would tie them
        verifyRejected(put(POLICIES + "re-grant", policy(20, "[\"opus\"]", "")), 409, "has the same priority 20");
        // a priority that ties with nothing, or a model that overlaps nothing, passes and applies to the assignment
        verify(put(POLICIES + "re-grant", policy(20, "[\"sonnet\"]", "")), 200);
        verify(put(POLICIES + "re-grant", policy(30, "[\"opus\"]", "")), 200);
        assertEquals(30, json(get(POLICIES + "re-grant")).get("priority").asInt());
        // a new amount leaves the assignments as they are
        verify(put(POLICIES + "re-grant", """
                { "priority": 30, "kind": "budget", "quantity": "cost", "object": ["opus"], "per": ["subject"], "cost": { "month": 250 } }"""), 200);
        assertEquals(250, json(get(POLICIES + "re-grant")).get("cost").get("month").asInt());

        // pinning the subject in the policy would pin it twice for the existing assignment
        verifyRejected(put(POLICIES + "re-grant", policy(30, "[\"opus\"]", "\"subject\": " + ALICE)), 422,
                "Assignment 're-a/grant' would become invalid: subject is pinned by both");
        // and so would pinning the cost object
        verifyRejected(put(POLICIES + "re-grant", policy(30, "[\"opus\"]", "\"costObject\": \"re-a\"")), 422,
                "costObject is pinned by both");
    }

    // admin apply

    @Test
    @SneakyThrows
    void testApplyCreatesPoliciesAndAssignmentsInOneBatch() {
        String body = """
                { "manifests": [
                  { "kind": "PolicyAssignment", "name": "policy_assignments/platform/apply-a/grant-1",
                    "spec": { "policy": "apply-grant", "costObject": "apply-a", "subject": %s } },
                  { "kind": "Policy", "name": "policies/platform/apply-grant", "spec": %s }
                ] }
                """.formatted(ALICE, GRANT_POLICY);

        Response response = send(HttpMethod.POST, "/v1/admin/apply", null, body, "authorization", "admin");

        verify(response, 200);
        JsonNode parsed = json(response);
        assertEquals(2, parsed.get("applied").asInt(), () -> "Body: " + response.body());
        assertEquals(0, parsed.get("failed").asInt());
        verify(get(POLICIES + "apply-grant"), 200);
        assertEquals("apply-a/grant-1", json(get(ASSIGNMENTS + "apply-a/grant-1")).get("id").asText());
    }

    @Test
    @SneakyThrows
    void testApplyPrecheckRejectsTheWholeBatch() {
        String body = """
                { "manifests": [
                  { "kind": "Policy", "name": "policies/platform/applybad-grant", "spec": %s },
                  { "kind": "PolicyAssignment", "name": "policy_assignments/platform/applybad-a/one",
                    "spec": { "policy": "applybad-grant", "costObject": "applybad-a" } }
                ] }
                """.formatted(GRANT_POLICY);

        Response response = send(HttpMethod.POST, "/v1/admin/apply", null, body, "authorization", "admin");

        verify(response, 422);
        JsonNode results = json(response).get("results");
        assertEquals("SKIPPED", results.get(0).get("status").asText());
        assertEquals("FAILED", results.get(1).get("status").asText());
        assertTrue(results.get(1).get("error").asText().contains("subject is pinned by neither"));
        verify(get(POLICIES + "applybad-grant"), 404);
    }

    @Test
    @SneakyThrows
    void testApplyChecksTiesWithinTheBatch() {
        String body = """
                { "manifests": [
                  { "kind": "Policy", "name": "policies/platform/applytie-first", "spec": %s },
                  { "kind": "Policy", "name": "policies/platform/applytie-second", "spec": %s },
                  { "kind": "PolicyAssignment", "name": "policy_assignments/platform/applytie-a/one",
                    "spec": { "policy": "applytie-first", "costObject": "applytie-a", "subject": %s } },
                  { "kind": "PolicyAssignment", "name": "policy_assignments/platform/applytie-a/two",
                    "spec": { "policy": "applytie-second", "costObject": "applytie-a", "subject": %s } }
                ] }
                """.formatted(policy(5, "\"*\"", ""), policy(5, "\"*\"", ""), ALICE, ALICE);

        Response response = send(HttpMethod.POST, "/v1/admin/apply", null, body, "authorization", "admin");

        verify(response, 422);
        assertTrue(json(response).get("results").get(3).get("error").asText().contains("has the same priority 5"));
    }

    @Test
    @SneakyThrows
    void testApplyWithoutPrecheckReportsPerRecordFailures() {
        String body = """
                { "precheck": false, "manifests": [
                  { "kind": "Policy", "name": "policies/platform/applynp-grant", "spec": %s },
                  { "kind": "PolicyAssignment", "name": "policy_assignments/platform/applynp-a/one",
                    "spec": { "policy": "applynp-missing", "costObject": "applynp-a", "subject": %s } },
                  { "kind": "PolicyAssignment", "name": "policy_assignments/platform/applynp-a/two",
                    "spec": { "policy": "applynp-grant", "costObject": "applynp-a", "subject": %s } }
                ] }
                """.formatted(GRANT_POLICY, ALICE, ALICE);

        Response response = send(HttpMethod.POST, "/v1/admin/apply", null, body, "authorization", "admin");

        verify(response, 200);
        JsonNode parsed = json(response);
        assertEquals(2, parsed.get("applied").asInt());
        assertEquals(1, parsed.get("failed").asInt());
        verify(get(ASSIGNMENTS + "applynp-a/one"), 404);
        verify(get(ASSIGNMENTS + "applynp-a/two"), 200);
    }

    @Test
    @SneakyThrows
    void testApplyRejectsMalformedNamesAndSpecs() {
        String body = """
                { "manifests": [
                  { "kind": "Policy", "name": "policies/platform/a/b/c", "spec": %s },
                  { "kind": "PolicyAssignment", "name": "policy_assignments/platform/no-folder", "spec": { "policy": "p" } },
                  { "kind": "Policy", "name": "policies/public/grant", "spec": %s },
                  { "kind": "Policy", "name": "policies/platform/unknown-field", "spec": { "colour": "red" } }
                ] }
                """.formatted(GRANT_POLICY, GRANT_POLICY);

        Response response = send(HttpMethod.POST, "/v1/admin/validate", null, body, "authorization", "admin");

        JsonNode results = json(response).get("results");
        assertEquals(4, results.size(), () -> "Body: " + response.body());
        Map<String, String> errorsByEntity = new HashMap<>();
        for (JsonNode result : results) {
            assertEquals("FAILED", result.get("status").asText(), () -> "Body: " + response.body());
            errorsByEntity.put(result.get("entityId").asText(), result.get("error").asText());
        }
        assertTrue(errorsByEntity.get("policies/platform/a/b/c").contains("At most one folder level"));
        assertTrue(errorsByEntity.get("policy_assignments/platform/no-folder").contains("<costObject>/<id>"));
        assertTrue(errorsByEntity.get("policies/public/grant").contains("must be 'platform'"));
        assertTrue(errorsByEntity.get("policies/platform/unknown-field").contains("unrecognized field"));
    }
}
