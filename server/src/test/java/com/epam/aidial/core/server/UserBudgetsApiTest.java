package com.epam.aidial.core.server;

import com.epam.aidial.core.config.RateLimitSchedule;
import com.epam.aidial.core.server.limiter.CalendarPeriod;
import com.epam.aidial.core.server.limiter.CalendarWindowCalculator;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code budgets[]} of {@code GET /v1/user/limits} and {@code GET /v1/user/usage}.
 */
class UserBudgetsApiTest extends ResourceBaseTest {

    private static final String POLICIES = "/v1/policies/platform/";
    private static final String ASSIGNMENTS = "/v1/policy_assignments/platform/";
    private static final String MANAGER = """
            [ { "source": "title", "function": "EQUAL", "targets": ["Manager"] } ]""";
    private static final String DIRECTOR = """
            [ { "source": "title", "function": "EQUAL", "targets": ["Director"] } ]""";

    @Test
    @DialConfigLocation("dial-config/user-budgets.json")
    void testBudgetOfEveryMatchingCostObjectIsListed() {
        putPolicy("project", 10, 200);
        putPolicy("personal", 5, 50);
        putAssignment("projA/m1", "project", MANAGER);
        putAssignment("projB/m1", "project", MANAGER);
        putAssignment("ORG/m1", "personal", MANAGER);
        putAssignment("projC/d1", "project", DIRECTOR);

        JsonNode budgets = userStats("/v1/user/limits").get("budgets");

        assertEquals(List.of("ORG", "projA", "projB"), costObjects(budgets));
        assertEquals(5, budgets.get(0).get("day").get("total").asInt());
        assertEquals(50, budgets.get(0).get("month").get("total").asInt());
        assertEquals(10, budgets.get(1).get("day").get("total").asInt());
        assertEquals(200, budgets.get(2).get("month").get("total").asInt());
    }

    @Test
    @DialConfigLocation("dial-config/user-budgets.json")
    void testEveryMatchingBudgetOfOneCostObjectIsListedByPriority() {
        putPolicy("team", 10, 200, 2);
        putPolicy("org-wide", 50, 1000, 1);
        putAssignment("projA/m1", "team", MANAGER);
        putAssignment("projA/m2", "org-wide", MANAGER);

        JsonNode budgets = userStats("/v1/user/limits").get("budgets");

        assertEquals(List.of("projA", "projA"), costObjects(budgets));
        assertEquals("org-wide", budgets.get(0).get("policy").asText());
        assertEquals(1, budgets.get(0).get("priority").asInt());
        assertEquals(50, budgets.get(0).get("day").get("total").asInt());
        assertEquals("team", budgets.get(1).get("policy").asText());
        assertEquals(2, budgets.get(1).get("priority").asInt());
        assertEquals(200, budgets.get(1).get("month").get("total").asInt());
    }

    @Test
    @DialConfigLocation("dial-config/user-budgets.json")
    void testResetsAtIsKnownBeforeTheFirstCharge() {
        putPolicy("project", 10, 200);
        putAssignment("projA/m1", "project", MANAGER);
        RateLimitSchedule schedule = new RateLimitSchedule();
        String dayResetsAt = resetsAt(CalendarPeriod.DAY, schedule);
        String monthResetsAt = resetsAt(CalendarPeriod.MONTH, schedule);

        JsonNode budget = userStats("/v1/user/usage").get("budgets").get(0);

        assertEquals(0, budget.get("day").get("used").asInt());
        assertEquals(0, budget.get("month").get("used").asInt());
        assertEquals(dayResetsAt, budget.get("day").get("resetsAt").asText());
        assertEquals(monthResetsAt, budget.get("month").get("resetsAt").asText());
    }

    @Test
    @DialConfigLocation("dial-config/user-budgets.json")
    void testBudgetOfAnotherSubjectIsNotListed() {
        putPolicy("project", 10, 200);
        putAssignment("projC/d1", "project", DIRECTOR);

        JsonNode stats = userStats("/v1/user/limits");

        assertTrue(stats.get("budgets").isEmpty());
    }

    @Test
    @DialConfigLocation("dial-config/user-budgets.json")
    void testLegacyCostStatsAreKept() {
        putPolicy("project", 10, 200);
        putAssignment("projA/m1", "project", MANAGER);

        JsonNode stats = userStats("/v1/user/limits");

        assertNotNull(stats.get("deployments"));
        assertNotNull(stats.get("dayCostStats"));
        assertNotNull(stats.get("monthCostStats"));
    }

    @Test
    @DialConfigLocation("dial-config/user-budgets.json")
    void testApiKeyCallerHasNoBudgets() {
        putPolicy("project", 10, 200);
        putAssignment("projA/m1", "project", MANAGER);

        Response response = send(HttpMethod.GET, "/v1/user/limits", null, null);
        verify(response, 200);

        assertTrue(readJson(response).get("budgets").isEmpty());
    }

    private void putPolicy(String name, int day, int month) {
        putPolicy(name, day, month, 10);
    }

    private void putPolicy(String name, int day, int month, int priority) {
        verify(send(HttpMethod.PUT, POLICIES + name, null, """
                { "priority": %d, "kind": "budget", "quantity": "cost", "object": "*", "per": ["subject"],
                  "cost": { "day": %d, "month": %d } }
                """.formatted(priority, day, month), "authorization", "admin"), 200);
    }

    private void putAssignment(String path, String policy, String subject) {
        verify(send(HttpMethod.PUT, ASSIGNMENTS + path, null, """
                { "policy": "%s", "costObject": "%s", "subject": %s }
                """.formatted(policy, path.substring(0, path.indexOf('/')), subject), "authorization", "admin"), 200);
    }

    private JsonNode userStats(String path) {
        Response response = send(HttpMethod.GET, path, null, null, "authorization", "user");
        verify(response, 200);
        return readJson(response);
    }

    private static List<String> costObjects(JsonNode budgets) {
        List<String> costObjects = new ArrayList<>();
        budgets.forEach(budget -> costObjects.add(budget.get("costObject").asText()));
        return costObjects;
    }

    @SneakyThrows
    private static JsonNode readJson(Response response) {
        return ProxyUtil.MAPPER.readTree(response.body());
    }

    private static String resetsAt(CalendarPeriod period, RateLimitSchedule schedule) {
        long millis = CalendarWindowCalculator.nextPeriodStart(period, System.currentTimeMillis(), schedule);
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                Instant.ofEpochMilli(millis).atZone(ZoneId.of(schedule.getTimezone())));
    }
}
