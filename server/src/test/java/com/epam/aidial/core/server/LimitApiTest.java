package com.epam.aidial.core.server;

import com.epam.aidial.core.config.RateLimitSchedule;
import com.epam.aidial.core.server.limiter.CalendarPeriod;
import com.epam.aidial.core.server.limiter.CalendarWindowCalculator;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LimitApiTest extends ResourceBaseTest {

    @Test
    public void testGetLimitStats_Success() {
        // the default (unconfigured) rateLimitSchedule - UTC, Monday, 00:00 - is what every dial-config
        // fixture here relies on; resetsAt is a pure function of "now" against that schedule, computed
        // the same way here and by the server, so it lands on the same instant unless the test happens
        // to straddle a period boundary at the exact millisecond
        RateLimitSchedule schedule = new RateLimitSchedule();
        String dayResetsAt = resetsAt(CalendarPeriod.DAY, schedule);
        String weekResetsAt = resetsAt(CalendarPeriod.WEEK, schedule);
        String monthResetsAt = resetsAt(CalendarPeriod.MONTH, schedule);

        Response response = send(HttpMethod.GET, "/v1/deployments/test-model-v1/limits", null, null);
        verifyJson(response, 200, """
                {
                  "minuteTokenStats": {
                    "total": %d,
                    "used": %d
                  },
                  "dayTokenStats": {
                    "total": %d,
                    "used": %d,
                    "resetsAt": "%s"
                  },
                  "weekTokenStats": {
                    "total": %d,
                    "used": %d,
                    "resetsAt": "%s"
                  },
                  "monthTokenStats": {
                    "total": %d,
                    "used": %d,
                    "resetsAt": "%s"
                  },
                  "hourRequestStats": {
                    "total": %d,
                    "used": %d
                  },
                  "dayRequestStats": {
                    "total": %d,
                    "used": %d,
                    "resetsAt": "%s"
                  },
                  "minuteCostStats": {
                    "total": %d,
                    "used": %d
                  },
                  "dayCostStats": {
                    "total": %d,
                    "used": %d,
                    "resetsAt": "%s"
                  },
                  "weekCostStats": {
                    "total": %d,
                    "used": %d,
                    "resetsAt": "%s"
                  },
                  "monthCostStats": {
                    "total": %d,
                    "used": %d,
                    "resetsAt": "%s"
                  }
                }
                """.formatted(
                        Long.MAX_VALUE, 0,
                        Long.MAX_VALUE, 0, dayResetsAt,
                        Long.MAX_VALUE, 0, weekResetsAt,
                        Long.MAX_VALUE, 0, monthResetsAt,
                        Long.MAX_VALUE, 0,
                        Long.MAX_VALUE, 0, dayResetsAt,
                        Long.MAX_VALUE, 0,
                        Long.MAX_VALUE, 0, dayResetsAt,
                        Long.MAX_VALUE, 0, weekResetsAt,
                        Long.MAX_VALUE, 0, monthResetsAt));
    }

    private static String resetsAt(CalendarPeriod period, RateLimitSchedule schedule) {
        long resetsAtMillis = CalendarWindowCalculator.nextPeriodStart(period, System.currentTimeMillis(), schedule);
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                Instant.ofEpochMilli(resetsAtMillis).atZone(ZoneId.of(schedule.getTimezone())));
    }

    @Test
    public void testGetLimitStats_UnknownModel() {
        Response response = send(HttpMethod.GET, "/v1/deployments/unknown-model/limits", null, null);
        verify(response, 404);
    }

    @Test
    public void testGetLimitStats_AccessDenied() {
        Response response = send(HttpMethod.GET, "/v1/deployments/gpt-4/limits", null, null);
        verify(response, 403);
    }

    @Test
    public void testGetUserLimits_Success() {
        JsonNode body = getUserLimits();

        JsonNode unlimited = deployment(body, "test-model-v1");
        for (String window : List.of("minuteTokenStats", "dayTokenStats", "weekTokenStats", "monthTokenStats",
                "hourRequestStats", "dayRequestStats")) {
            assertEquals(Long.MAX_VALUE, unlimited.get(window).get("total").asLong(), window);
            assertEquals(0, unlimited.get(window).get("used").asLong(), window);
        }

        JsonNode configured = deployment(body, "chat-gpt-35-turbo");
        assertEquals(100000, configured.get("minuteTokenStats").get("total").asLong());
        assertEquals(10000000, configured.get("dayTokenStats").get("total").asLong());
        // windows the role leaves unspecified fall back to unlimited
        assertEquals(Long.MAX_VALUE, configured.get("weekTokenStats").get("total").asLong());

        // the caller's budget sits at the top level; an entry carries its own attributed spend against the
        // unlimited sentinel, because only the global budget can cap spend on a single deployment
        assertNotNull(body.get("dayCostStats"));
        assertEquals(0, new BigDecimal("0").compareTo(body.get("dayCostStats").get("used").decimalValue()));
        assertEquals(Long.MAX_VALUE, configured.get("dayCostStats").get("total").asLong());
        assertEquals(0, new BigDecimal("0").compareTo(configured.get("dayCostStats").get("used").decimalValue()));
    }

    @Test
    public void testGetUserLimits_ExcludesInaccessibleDeployments() {
        // gpt-4 requires the power-user role, which proxyKey1 does not have
        assertNull(deploymentOrNull(getUserLimits(), "gpt-4"));
    }

    @Test
    public void testGetUserLimits_ExcludesApplicationsAndToolsets() {
        JsonNode body = getUserLimits();
        // omitting deploymentTypes defaults to "model" and reproduces today's response exactly
        assertNull(deploymentOrNull(body, "app"));
        assertNull(deploymentOrNull(body, "git"));
        assertNull(deploymentOrNull(body, "my-toolset_2"));
    }

    /**
     * {@code deploymentTypes=application} reports Applications instead of Models - toolsets/routes still
     * never appear, since this parameter only adds one more kind, not every non-Model kind.
     */
    @Test
    public void testGetUserLimits_DeploymentTypesApplication_ReportsApplicationsNotModels() {
        JsonNode body = getUserLimitsWithTypes("application");
        assertNotNull(deploymentOrNull(body, "app"));
        assertNull(deploymentOrNull(body, "test-model-v1"));
        assertNull(deploymentOrNull(body, "git"));
        assertNull(deploymentOrNull(body, "my-toolset_2"));
    }

    /**
     * {@code deploymentTypes=model,application} reports both kinds together in the same map.
     */
    @Test
    public void testGetUserLimits_DeploymentTypesModelAndApplication_ReportsBoth() {
        JsonNode body = getUserLimitsWithTypes("model,application");
        assertNotNull(deploymentOrNull(body, "app"));
        assertNotNull(deploymentOrNull(body, "test-model-v1"));
    }

    /**
     * The parameter applies identically to both endpoints, since they share the same underlying path.
     */
    @Test
    public void testGetUserUsage_DeploymentTypesApplication_SharesBehaviorWithLimits() {
        Response response = send(HttpMethod.GET, "/v1/user/usage?deploymentTypes=application", null, null);
        verify(response, 200);
        JsonNode body = readJson(response);
        // an Application with no aggregated-cost activity yet is empty usage, same as an unused Model
        assertNull(deploymentOrNull(body, "app"));
    }

    /**
     * {@code deploymentTypes=application} also lists a resource-based custom (non-config) Application,
     * by name only - {@link com.epam.aidial.core.server.service.DeploymentService#listDeploymentNames}
     * never reads its content, so this exercises that the lightweight listing round-trips a real
     * resource's name correctly, not just config-defined Applications.
     */
    @Test
    public void testGetUserLimits_DeploymentTypesApplication_IncludesCustomApplication() {
        String bucket = "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST";
        Response created = send(HttpMethod.PUT, "/v1/applications/" + bucket + "/my-custom-application", null, """
                {
                "endpoint": "http://application1/v1/completions",
                "display_name": "My Custom Application"
                }
                """);
        verify(created, 200);

        JsonNode body = getUserLimitsWithTypes("application");
        assertNotNull(deploymentOrNull(body, "applications/" + bucket + "/my-custom-application"));
    }

    @Test
    public void testGetUserLimits_ReportsUsageAfterCompletion() {
        completion("gpt-3-turbo");

        JsonNode used = deployment(getUserLimits(), "gpt-3-turbo");
        assertEquals(100, used.get("minuteTokenStats").get("total").asLong());
        assertEquals(30, used.get("minuteTokenStats").get("used").asLong());
        assertEquals(1000, used.get("dayTokenStats").get("total").asLong());
        assertEquals(30, used.get("dayTokenStats").get("used").asLong());
        assertEquals(30, used.get("weekTokenStats").get("used").asLong());
        assertEquals(30, used.get("monthTokenStats").get("used").asLong());
        // request counters are incremented at admission time, before the upstream call
        assertEquals(1, used.get("hourRequestStats").get("used").asLong());
        assertEquals(1, used.get("dayRequestStats").get("used").asLong());

        // the bulk endpoint must agree with the existing per-deployment one
        JsonNode single = readJson(send(HttpMethod.GET, "/v1/deployments/gpt-3-turbo/limits", null, null));
        for (String window : List.of("minuteTokenStats", "dayTokenStats", "weekTokenStats", "monthTokenStats",
                "hourRequestStats", "dayRequestStats")) {
            assertEquals(single.get(window).get("total").asLong(), used.get(window).get("total").asLong(), window);
            assertEquals(single.get(window).get("used").asLong(), used.get(window).get("used").asLong(), window);
        }
    }

    /**
     * A token with neither a subject nor a project leaves no principal to report limits for, which takes a
     * misconfigured identity provider - a key without a project is rejected at load time. It is a
     * server-side fault, so it must not sign the caller out.
     */
    @Test
    public void testGetUserLimits_UnresolvableInitiator() {
        Response response = send(HttpMethod.GET, "/v1/user/limits", null, null,
                "authorization", "no-subject");
        verifyNotExact(response, 500, "Failed to get user limit stats");

        // the usage endpoint answers identically - the two share the whole path
        verifyNotExact(send(HttpMethod.GET, "/v1/user/usage", null, null, "authorization", "no-subject"),
                500, "Failed to get user limit stats");
    }

    /**
     * The two endpoints differ only in which deployments appear: usage is a subset of limits, and a caller
     * who has used nothing gets an empty set rather than a row of zeros per accessible model.
     */
    @Test
    public void testGetUserUsage_ReportsSubsetOfLimits() {
        assertEquals(List.of(), deploymentIds(getUserUsage()));
        // the limits response still labels every accessible model
        assertNotNull(deployment(getUserLimits(), "test-model-v1"));

        completion("gpt-3-turbo");

        assertEquals(List.of("gpt-3-turbo"), deploymentIds(getUserUsage()));
        List<String> limits = deploymentIds(getUserLimits());
        assertTrue(limits.contains("gpt-3-turbo"), limits::toString);
        assertTrue(limits.size() > 1, limits::toString);

        JsonNode usage = deployment(getUserUsage(), "gpt-3-turbo");
        JsonNode all = deployment(getUserLimits(), "gpt-3-turbo");
        for (String window : List.of("minuteTokenStats", "dayTokenStats", "hourRequestStats")) {
            assertEquals(all.get(window).get("total").asLong(), usage.get(window).get("total").asLong(), window);
            assertEquals(all.get(window).get("used").asLong(), usage.get(window).get("used").asLong(), window);
        }
    }

    /**
     * A model name only has to be legal configuration, and brackets are - but they are illegal in a URI path, so
     * a single such model used to fail the whole report with 500 for every user who could access it.
     */
    @Test
    @DialConfigLocation("dial-config/bracket-named-model.json")
    public void testGetUserLimits_DeploymentNameThatIsNotUriSafe() {
        String deployment = "anthropic.claude-opus-4-8[1m]";
        String encodedId = "anthropic.claude-opus-4-8%5B1m%5D";

        assertEquals(List.of(), deploymentIds(getUserUsage()));
        assertNotNull(deployment(getUserLimits(), deployment));

        completion(deployment, encodedId);

        JsonNode used = deployment(getUserUsage(), deployment);
        assertEquals(100, used.get("minuteTokenStats").get("total").asLong());
        assertEquals(30, used.get("minuteTokenStats").get("used").asLong());
        assertEquals(1000, used.get("dayTokenStats").get("total").asLong());
        assertEquals(1, used.get("hourRequestStats").get("used").asLong());

        // the per-deployment endpoint agrees; its id is percent encoded in the request path
        JsonNode single = readJson(send(HttpMethod.GET, "/v1/deployments/" + encodedId + "/limits", null, null));
        assertEquals(30, single.get("minuteTokenStats").get("used").asLong());
    }

    private void completion(String deployment) {
        completion(deployment, deployment);
    }

    private void completion(String deployment, String encodedId) {
        String answer = "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"model\":\"" + deployment + "\","
                + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20,\"total_tokens\":30}}";

        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/chat/completions", 200, answer);

            Response response = send(HttpMethod.POST, "/openai/deployments/" + encodedId + "/chat/completions", null,
                    "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                    "content-type", "application/json");
            verify(response, 200);
        }
    }

    private JsonNode getUserLimits() {
        Response response = send(HttpMethod.GET, "/v1/user/limits", null, null);
        verify(response, 200);
        return readJson(response);
    }

    private JsonNode getUserLimitsWithTypes(String deploymentTypes) {
        Response response = send(HttpMethod.GET, "/v1/user/limits?deploymentTypes=" + deploymentTypes, null, null);
        verify(response, 200);
        return readJson(response);
    }

    private JsonNode getUserUsage() {
        Response response = send(HttpMethod.GET, "/v1/user/usage", null, null);
        verify(response, 200);
        return readJson(response);
    }

    @SneakyThrows
    private static JsonNode readJson(Response response) {
        return ProxyUtil.MAPPER.readTree(response.body());
    }

    private static JsonNode deployment(JsonNode body, String id) {
        JsonNode found = deploymentOrNull(body, id);
        assertNotNull(found, "deployment " + id + " is missing from " + body.get("deployments"));
        return found;
    }

    private static JsonNode deploymentOrNull(JsonNode body, String id) {
        return body.get("deployments").get(id);
    }

    private static List<String> deploymentIds(JsonNode body) {
        List<String> ids = new ArrayList<>();
        body.get("deployments").fieldNames().forEachRemaining(ids::add);
        return ids;
    }
}
