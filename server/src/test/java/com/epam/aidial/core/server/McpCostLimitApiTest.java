package com.epam.aidial.core.server;

import com.epam.aidial.core.config.RateLimitSchedule;
import com.epam.aidial.core.server.limiter.CostRateLimit;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class McpCostLimitApiTest extends ResourceBaseTest {

    private static final String CONFIG = "dial-config/mcp-cost-limit.json";
    private static final String MCP_RESPONSE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}}";
    private static final String TOOLS_LIST = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
    private static final String CHAT_REQUEST = "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

    @Test
    @DialConfigLocation(CONFIG)
    void mcpCallPassesWhenCostLimitIsUsedUpButModelCallIsRejected() {
        seedSpend("capped", "10");
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/search/mcp", request -> TestWebServer.createResponse(200, MCP_RESPONSE,
                    "Content-Type", "application/json"));

            Response mcp = send(HttpMethod.POST, "/v1/toolset/search/mcp", null, TOOLS_LIST,
                    "api-key", "proxyKey1", "Content-Type", "application/json");
            assertEquals(200, mcp.status(), mcp.body());

            Response model = send(HttpMethod.POST, "/openai/deployments/gpt-cost/chat/completions", null, CHAT_REQUEST,
                    "api-key", "proxyKey1", "Content-Type", "application/json");
            assertEquals(429, model.status(), model.body());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void requestLimitOfToolsetStillRejectsMcpCalls() {
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/search/mcp", request -> TestWebServer.createResponse(200, MCP_RESPONSE,
                    "Content-Type", "application/json"));

            Response first = send(HttpMethod.POST, "/v1/toolset/search/mcp", null, TOOLS_LIST,
                    "api-key", "proxyKey2", "Content-Type", "application/json");
            assertEquals(200, first.status(), first.body());

            Response second = send(HttpMethod.POST, "/v1/toolset/search/mcp", null, TOOLS_LIST,
                    "api-key", "proxyKey2", "Content-Type", "application/json");
            assertEquals(429, second.status(), second.body());
        }
    }

    private void seedSpend(String project, String dollars) {
        String bucket = "Keys/" + project + "/";
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromEntityPath(ResourceTypes.LIMIT, bucket, bucket, "costs");
        dial.getProxy().getResourceService().computeResource(descriptor, json -> {
            CostRateLimit limit = new CostRateLimit();
            limit.add(System.currentTimeMillis(), new RateLimitSchedule(), new BigDecimal(dollars));
            return ProxyUtil.convertToString(limit);
        });
    }
}
