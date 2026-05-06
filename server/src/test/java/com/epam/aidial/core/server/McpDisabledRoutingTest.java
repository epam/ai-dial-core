package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the {@code mcp.enabled = false} kill-switch from spec §7.1: when MCP is disabled,
 * the verticle is not deployed and the {@code /mcp} mount returns 404 (not 503).
 */
class McpDisabledRoutingTest extends ResourceBaseTest {

    @Override
    protected JsonObject additionalSettingsOverrides() {
        return new JsonObject().put("mcp", new JsonObject().put("enabled", false));
    }

    @Test
    void getMcpRoot_returns404() {
        Response response = send(HttpMethod.GET, "/mcp");
        assertEquals(404, response.status());
    }

    @Test
    void getMcpSubPath_returns404() {
        Response response = send(HttpMethod.GET, "/mcp/anything");
        assertEquals(404, response.status());
    }
}
