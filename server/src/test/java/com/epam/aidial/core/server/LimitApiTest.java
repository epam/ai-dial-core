package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class LimitApiTest extends ResourceBaseTest {

    @Test
    public void testGetLimitStats_Success() {
        Response response = send(HttpMethod.GET, "/v1/deployments/test-model-v1/limits", null, null);
        verifyJson(response, 200, """
                {
                  "minuteTokenStats": {
                    "total": %d,
                    "used": %d
                  },
                  "dayTokenStats": {
                    "total": %d,
                    "used": %d
                  },
                  "weekTokenStats": {
                    "total": %d,
                    "used": %d
                  },
                  "monthTokenStats": {
                    "total": %d,
                    "used": %d
                  },
                  "hourRequestStats": {
                    "total": %d,
                    "used": %d
                  },
                  "dayRequestStats": {
                    "total": %d,
                    "used": %d
                  },
                  "minuteCostStats": {
                    "total": %d,
                    "used": %d
                  },
                  "dayCostStats": {
                    "total": %d,
                    "used": %d
                  },
                  "weekCostStats": {
                    "total": %d,
                    "used": %d
                  },
                  "monthCostStats": {
                    "total": %d,
                    "used": %d
                  }
                }
                """.formatted(
                        Long.MAX_VALUE, 0, Long.MAX_VALUE, 0, Long.MAX_VALUE, 0, Long.MAX_VALUE, 0,
                        Long.MAX_VALUE, 0, Long.MAX_VALUE, 0,
                        Long.MAX_VALUE, 0, Long.MAX_VALUE, 0, Long.MAX_VALUE, 0, Long.MAX_VALUE, 0));
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

        // cost is per caller, so it appears once at the top level and never on a deployment
        assertNotNull(body.get("dayCostStats"));
        assertEquals(0, new BigDecimal("0").compareTo(body.get("dayCostStats").get("used").decimalValue()));
        assertNull(configured.get("dayCostStats"));
    }

    @Test
    public void testGetUserLimits_ExcludesInaccessibleDeployments() {
        // gpt-4 requires the power-user role, which proxyKey1 does not have
        assertNull(deploymentOrNull(getUserLimits(), "gpt-4"));
    }

    @Test
    public void testGetUserLimits_ExcludesApplicationsAndToolsets() {
        JsonNode body = getUserLimits();
        // "app" is an application and even has a limit configured under the default role, but DIAL never
        // records rate-limit usage for applications, so reporting it would imply a limit that cannot fire
        assertNull(deploymentOrNull(body, "app"));
        assertNull(deploymentOrNull(body, "git"));
        assertNull(deploymentOrNull(body, "my-toolset_2"));
    }

    @Test
    public void testGetUserLimits_ReportsUsageAfterCompletion() {
        String answer = "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"model\":\"gpt-3-turbo\","
                + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20,\"total_tokens\":30}}";

        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/chat/completions", 200, answer);

            Response response = send(HttpMethod.POST, "/openai/deployments/gpt-3-turbo/chat/completions", null,
                    "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                    "content-type", "application/json");
            verify(response, 200);
        }

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
     * A token with neither a subject nor a project leaves no principal to report limits for, so there is
     * no bucket to read the counters from. An api-key cannot reach this branch - the config rejects a key
     * without a project at startup.
     */
    @Test
    public void testGetUserLimits_UnresolvableInitiator() {
        Response response = send(HttpMethod.GET, "/v1/user/limits", null, null,
                "authorization", "no-subject");
        // the message proves the 401 came from resolving the initiator, not from the auth layer
        verifyNotExact(response, 401, "Can't find user bucket");
    }

    private JsonNode getUserLimits() {
        Response response = send(HttpMethod.GET, "/v1/user/limits", null, null);
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
        for (JsonNode deployment : body.get("deployments")) {
            if (id.equals(deployment.get("id").asText())) {
                return deployment;
            }
        }
        return null;
    }
}
