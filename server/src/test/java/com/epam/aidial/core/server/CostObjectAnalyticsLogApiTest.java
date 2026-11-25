package com.epam.aidial.core.server;

import com.epam.aidial.core.server.limiter.budget.CostObject;
import com.epam.aidial.core.server.log.AnalyticsLogContext;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The cost object and its source in the analytics row (spec: analytics-log, "Cost object in the analytics row").
 */
class CostObjectAnalyticsLogApiTest extends ResourceBaseTest {

    private static final String CONFIG = "dial-config/budget-spend-caps.json";
    // a caller without a budget is not refused in shadow mode, so its call writes a row
    private static final String SHADOW_CONFIG = "dial-config/budget-spend-caps-shadow.json";
    private static final String MESSAGES_PATH = "/anthropic/v1/messages";
    private static final String MESSAGES_ANSWER = "{\"id\":\"msg_01\",\"type\":\"message\",\"role\":\"assistant\","
            + "\"model\":\"claude-spend\",\"content\":[{\"type\":\"text\",\"text\":\"Who's there?\"}],"
            + "\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":10,\"output_tokens\":8}}";

    @Test
    @DialConfigLocation(CONFIG)
    void testApiKeyCallerHasTheKeyProject() {
        try (TestWebServer server = upstream()) {
            verify(postMessages("api-key", "proxyKey1"), 200);

            AnalyticsLogContext row = modelRow();
            assertEquals("spend", row.getProject());
            assertEquals("spend", row.getCostObject());
            assertEquals(CostObject.Source.KEY, row.getCostObjectSource());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testTokenWithProjectHasTheClaim() {
        budget();
        try (TestWebServer server = upstream()) {
            verify(postMessages("authorization", "user@projA"), 200);

            AnalyticsLogContext row = modelRow();
            assertEquals("projA", row.getProject());
            assertEquals("projA", row.getBudgetCheck().costObject());
            assertEquals("projA", row.getCostObject());
            assertEquals(CostObject.Source.CLAIM, row.getCostObjectSource());
        }
    }

    @Test
    @DialConfigLocation(SHADOW_CONFIG)
    void testTokenWithoutProjectHasTheDefault() {
        try (TestWebServer server = upstream()) {
            verify(postMessages("authorization", "user"), 200);

            AnalyticsLogContext row = modelRow();
            assertNull(row.getProject());
            assertEquals("ORG", row.getCostObject());
            assertEquals(CostObject.Source.DEFAULT, row.getCostObjectSource());
        }
    }

    private AnalyticsLogContext modelRow() {
        awaitCondition(() -> findRow() != null);
        return findRow();
    }

    private AnalyticsLogContext findRow() {
        return List.copyOf(analyticsRows).stream()
                .filter(row -> "claude-spend".equals(row.getDeploymentName()))
                .findFirst()
                .orElse(null);
    }

    private static TestWebServer upstream() {
        TestWebServer server = new TestWebServer(4848);
        server.map(HttpMethod.POST, MESSAGES_PATH,
                request -> TestWebServer.createResponse(200, MESSAGES_ANSWER, "Content-Type", "application/json"));
        return server;
    }

    /**
     * A budget of $10 a day in {@code projA}, for the Manager title.
     */
    private void budget() {
        verify(send(HttpMethod.PUT, "/v1/policies/platform/projA-a1", null, """
                { "priority": 10, "kind": "budget", "quantity": "cost", "object": "*", "per": ["subject"],
                  "cost": { "day": 10 } }
                """, "authorization", "admin"), 200);
        ObjectNode assignment = ProxyUtil.MAPPER.createObjectNode();
        assignment.put("policy", "projA-a1");
        assignment.put("costObject", "projA");
        assignment.set("subject", ProxyUtil.convertToObject("""
                [ { "source": "title", "function": "EQUAL", "targets": ["Manager"] } ]""", JsonNode.class));
        verify(send(HttpMethod.PUT, "/v1/policy_assignments/platform/projA/a1", null, assignment.toString(),
                "authorization", "admin"), 200);
    }

    private Response postMessages(String header, String value) {
        return send(HttpMethod.POST, MESSAGES_PATH, null,
                "{\"model\":\"claude-spend\",\"max_tokens\":100,\"stream\":false,"
                        + "\"messages\":[{\"role\":\"user\",\"content\":\"Knock, knock\"}]}",
                "content-type", "application/json", header, value, "anthropic-version", "2023-06-01");
    }
}
