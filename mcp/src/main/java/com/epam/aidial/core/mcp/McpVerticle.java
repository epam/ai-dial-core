package com.epam.aidial.core.mcp;

import com.epam.aidial.core.mcp.transport.VertxMcpTransportProvider;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
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

    private final VertxMcpTransportProvider transportProvider;
    private McpAsyncServer server;

    public McpVerticle(VertxMcpTransportProvider transportProvider) {
        this.transportProvider = transportProvider;
    }

    @Override
    public void start(Promise<Void> startPromise) {
        // No-op validator: zero tools registered until M.1.x, and DIAL excludes the SDK's
        // transitive json-schema-validator (incompatible with :config's networknt 1.5.2).
        JsonSchemaValidator noopValidator = (schema, instance) -> JsonSchemaValidator.ValidationResponse.asValid("");
        server = McpServer.async(transportProvider)
                .serverInfo(SERVER_NAME, SERVER_VERSION)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .jsonSchemaValidator(noopValidator)
                .build();
        log.info("MCP verticle started; transport adapter wired (M.0.0-bridge)");
        startPromise.complete();
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
