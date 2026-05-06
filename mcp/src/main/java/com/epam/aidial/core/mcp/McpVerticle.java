package com.epam.aidial.core.mcp;

import com.epam.aidial.core.mcp.client.DialClient;
import com.epam.aidial.core.mcp.ratelimit.McpSessionLimiter;
import com.epam.aidial.core.mcp.transport.VertxMcpTransportProvider;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Context;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import lombok.extern.slf4j.Slf4j;

/**
 * Hosts the MCP tool surface in its own verticle to isolate it from the Core HTTP hot path.
 * Core routes {@code /mcp} traffic in via {@link McpRequestHandler}; the verticle does not
 * bind its own port. See {@code mcp/CONTRIBUTING.md} for the extraction discipline.
 */
@Slf4j
public class McpVerticle extends AbstractVerticle {

    private static final String SERVER_NAME = "dial-mcp";
    private static final String SERVER_VERSION = "0.1.0";
    private static final String DEFAULT_DIAL_TARGET_URL = "http://localhost:8080";
    private static final String DIAL_TARGET_URL_ENV = "MCP_DIAL_TARGET_URL";
    private static final String DIAL_TARGET_URL_KEY = "dialTargetUrl";

    private final VertxMcpTransportProvider transportProvider;
    private final JsonObject mcpSettings;
    private McpAsyncServer server;
    private DialClient dialClient;

    public McpVerticle(VertxMcpTransportProvider transportProvider, JsonObject mcpSettings) {
        this.transportProvider = transportProvider;
        this.mcpSettings = mcpSettings != null ? mcpSettings : new JsonObject();
    }

    @Override
    public void start(Promise<Void> startPromise) {
        Context vertxContext = vertx.getOrCreateContext();
        String targetUrl = resolveDialTargetUrl();
        dialClient = new DialClient(vertx, vertxContext, targetUrl);
        log.info("MCP DialClient bound to {} (threading bridge: captured-context dispatch)", targetUrl);

        McpSessionLimiter limiter = buildLimiterIfEnabled(mcpSettings);
        transportProvider.setLimiter(limiter);

        // No-op validator: zero tools registered until M.1.x, and DIAL excludes the SDK's
        // transitive json-schema-validator (incompatible with :config's networknt 1.5.2).
        JsonSchemaValidator noopValidator = (schema, instance) -> JsonSchemaValidator.ValidationResponse.asValid("");
        server = McpServer.async(transportProvider)
                .serverInfo(SERVER_NAME, SERVER_VERSION)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .jsonSchemaValidator(noopValidator)
                .build();
        log.info("MCP verticle started; transport adapter wired (M.0.0-bridge), DialClient ready (M.0.1-pre)");
        startPromise.complete();
    }

    private static McpSessionLimiter buildLimiterIfEnabled(JsonObject mcpSettings) {
        JsonObject rateLimit = mcpSettings.getJsonObject("rateLimit", new JsonObject());
        if (!rateLimit.getBoolean("enabled", true)) {
            return null;
        }
        int callsPerMinute = rateLimit.getInteger("callsPerMinute", 60);
        int burstCapacity = rateLimit.getInteger("burstCapacity", 10);
        JsonObject concurrency = mcpSettings.getJsonObject("concurrency", new JsonObject());
        int maxConcurrent = concurrency.getInteger("maxConcurrentCallsPerSession", 5);
        return new McpSessionLimiter(callsPerMinute, burstCapacity, maxConcurrent);
    }

    private String resolveDialTargetUrl() {
        String fromEnv = System.getenv(DIAL_TARGET_URL_ENV);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        String fromSettings = mcpSettings.getString(DIAL_TARGET_URL_KEY);
        if (fromSettings != null && !fromSettings.isBlank()) {
            return fromSettings;
        }
        return DEFAULT_DIAL_TARGET_URL;
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        transportProvider.closeGracefully().subscribe(
                null,
                err -> {
                    log.error("MCP transport graceful close failed", err);
                    stopPromise.fail(err);
                },
                stopPromise::complete);
    }
}
