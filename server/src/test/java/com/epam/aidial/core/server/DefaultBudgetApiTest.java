package com.epam.aidial.core.server;

import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The user's default budget and its endpoints (spec: default-budget).
 */
class DefaultBudgetApiTest extends ResourceBaseTest {

    private static final String CONFIG = "dial-config/budget-spend-caps.json";
    private static final String DEFAULT_PATH = "/v1/user/budgets/default";
    private static final String POLICIES = "/v1/policies/platform/";
    private static final String ASSIGNMENTS = "/v1/policy_assignments/platform/";
    private static final String MANAGER = "Manager";
    private static final String UNSET = "{\"budget\":null}";
    private static final String NOT_HELD = "You hold no budget ";

    @Test
    @DialConfigLocation(CONFIG)
    void testSetAndReadHeldBudget() {
        budget("projA/a1", MANAGER, null);

        verify(putDefault("{\"budget\":\"projA/a1\"}"), 200, "{\"budget\":\"projA/a1\"}");
        verify(getDefault("user"), 200, "{\"budget\":\"projA/a1\"}");
        assertEquals("{\"budget\":\"projA/a1\"}", readRecord());
    }

    @Test
    void testSetWhenSpendingLimitsOff() {
        budget("projA/a1", MANAGER, null);

        verify(putDefault("{\"budget\":\"projA/a1\"}"), 200, "{\"budget\":\"projA/a1\"}");
        verify(getDefault("user"), 200, "{\"budget\":\"projA/a1\"}");
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testReplaceDefault() {
        budget("projA/a1", MANAGER, null);
        budget("projB/b1", MANAGER, null);
        verify(putDefault("{\"budget\":\"projA/a1\"}"), 200);

        verify(putDefault("{\"budget\":\"projB/b1\"}"), 200, "{\"budget\":\"projB/b1\"}");
        verify(getDefault("user"), 200, "{\"budget\":\"projB/b1\"}");
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testClearDefault() {
        budget("projA/a1", MANAGER, null);
        verify(putDefault("{\"budget\":\"projA/a1\"}"), 200);

        verify(putDefault(UNSET), 200, UNSET);
        verify(getDefault("user"), 200, UNSET);
        assertNull(readRecord());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testClearWhenNothingSet() {
        verify(putDefault(UNSET), 200, UNSET);
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testBudgetOfAnotherSubjectRejected() {
        budget("projA/a1", MANAGER, null);
        budget("projA/other", "Director", null);
        verify(putDefault("{\"budget\":\"projA/a1\"}"), 200);

        verify(putDefault("{\"budget\":\"projA/other\"}"), 400, NOT_HELD + "projA/other");
        verify(getDefault("user"), 200, "{\"budget\":\"projA/a1\"}");
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testUnknownBudgetRejected() {
        verify(putDefault("{\"budget\":\"projA/missing\"}"), 400, NOT_HELD + "projA/missing");
        verify(getDefault("user"), 200, UNSET);
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testMalformedBody() {
        verify(putDefault("{}"), 400);
        verify(putDefault("{\"budget\":5}"), 400);
        verify(putDefault("[\"projA/a1\"]"), 400);
        verify(putDefault("not json"), 400);
        verify(putDefault(""), 400);
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testNothingSet() {
        verify(getDefault("user"), 200, UNSET);
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testWithdrawnBudgetReadsAsUnset() {
        budget("projA/a1", MANAGER, null);
        verify(putDefault("{\"budget\":\"projA/a1\"}"), 200);

        verify(send(HttpMethod.DELETE, ASSIGNMENTS + "projA/a1", null, null, "authorization", "admin"), 204);

        verify(getDefault("user"), 200, UNSET);
        // reading leaves the record as it is
        assertEquals("{\"budget\":\"projA/a1\"}", readRecord());
    }

    @Test
    @DialConfigLocation(CONFIG)
    @SneakyThrows
    void testExpiredBudgetReadsAsUnset() {
        Instant expiresAt = Instant.now().plusSeconds(2);
        budget("projA/a1", MANAGER, expiresAt.toString());
        verify(putDefault("{\"budget\":\"projA/a1\"}"), 200);
        while (!Instant.now().isAfter(expiresAt)) {
            Thread.sleep(200);
        }

        verify(getDefault("user"), 200, UNSET);
        verify(putDefault("{\"budget\":\"projA/a1\"}"), 400, NOT_HELD + "projA/a1");
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testApiKeyCallerForbidden() {
        verify(send(HttpMethod.GET, DEFAULT_PATH, null, null, "api-key", "proxyKey1"), 403);
        verify(send(HttpMethod.PUT, DEFAULT_PATH, null, UNSET, "api-key", "proxyKey1"), 403);
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testPerRequestKeyForbidden() {
        budget("projA/a1", MANAGER, null);
        ApiKeyData appKey = createAppKey("user", Map.of());
        apiKeyStore.assignPerRequestApiKey(appKey);

        verify(send(HttpMethod.PUT, DEFAULT_PATH, null, "{\"budget\":\"projA/a1\"}", "api-key", appKey.getPerRequestKey()), 403);
        verify(send(HttpMethod.GET, DEFAULT_PATH, null, null, "api-key", appKey.getPerRequestKey()), 403);
        verify(getDefault("user"), 200, UNSET);
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testRecordUnreachableThroughResourceApi() {
        budget("projA/a1", MANAGER, null);
        verify(putDefault("{\"budget\":\"projA/a1\"}"), 200);

        Response read = send(HttpMethod.GET, "/v1/user_budget_default/" + bucket + "/default", null, null, "authorization", "user");
        assertEquals(404, read.status(), read.body());
        Response write = send(HttpMethod.PUT, "/v1/user_budget_default/" + bucket + "/default", null,
                "{\"budget\":\"projA/other\"}", "authorization", "user");
        assertEquals(404, write.status(), write.body());
        Response metadata = send(HttpMethod.GET, "/v1/metadata/user_budget_default/" + bucket + "/", null, null, "authorization", "user");
        assertEquals(404, metadata.status(), metadata.body());
        assertEquals("{\"budget\":\"projA/a1\"}", readRecord());
    }

    private Response getDefault(String authorization) {
        return send(HttpMethod.GET, DEFAULT_PATH, null, null, "authorization", authorization);
    }

    private Response putDefault(String body) {
        return send(HttpMethod.PUT, DEFAULT_PATH, null, body, "authorization", "user", "content-type", "application/json");
    }

    private String readRecord() {
        String bucketLocation = "Users/user/";
        ResourceDescriptor record = ResourceDescriptorFactory.fromEntityPath(ResourceTypes.USER_BUDGET_DEFAULT, bucketLocation, bucketLocation,
                "default");
        return dial.getProxy().getResourceService().getResource(record);
    }

    /**
     * One policy per assignment, named after it, granted to the callers with the given title.
     */
    private void budget(String instanceId, String title, String expiresAt) {
        String name = instanceId.replace('/', '-');
        ObjectNode policy = ProxyUtil.MAPPER.createObjectNode();
        policy.put("priority", 10);
        policy.put("kind", "budget");
        policy.put("quantity", "cost");
        policy.put("object", "*");
        policy.putArray("per").add("subject");
        policy.putObject("cost").put("day", 10);
        verify(send(HttpMethod.PUT, POLICIES + name, null, policy.toString(), "authorization", "admin"), 200);

        ObjectNode assignment = ProxyUtil.MAPPER.createObjectNode();
        assignment.put("policy", name);
        assignment.put("costObject", instanceId.substring(0, instanceId.indexOf('/')));
        ObjectNode rule = assignment.putArray("subject").addObject();
        rule.put("source", "title");
        rule.put("function", "EQUAL");
        rule.putArray("targets").add(title);
        if (expiresAt != null) {
            assignment.put("expiresAt", expiresAt);
        }
        verify(send(HttpMethod.PUT, ASSIGNMENTS + instanceId, null, assignment.toString(), "authorization", "admin"), 200);
    }
}
