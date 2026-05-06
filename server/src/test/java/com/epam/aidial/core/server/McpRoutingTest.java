package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Routing-only verification of the {@code /mcp} mount with the default {@code mcp.enabled = true}.
 * The disabled-toggle case lives in {@link McpDisabledRoutingTest} as its own top-level class so
 * the embedded-Redis lifecycle does not collide on port 16370.
 */
class McpRoutingTest extends ResourceBaseTest {

    @Test
    void mcpPrefixDoesNotSwallowSimilarPaths() {
        // /mcpfoo must fall through to normal Proxy routing (no MCP short-circuit) and hit the
        // route-not-found path. Asserting the specific 404 — not just !=503 — guards against the
        // boundary check accidentally widening to swallow paths that share the /mcp prefix.
        Response response = send(HttpMethod.GET, "/mcpfoo");
        assertEquals(404, response.status());
    }

    @Test
    void healthEndpointStillWorks() {
        Response response = send(HttpMethod.GET, Proxy.HEALTH_CHECK_PATH);
        assertEquals(200, response.status());
    }
}
