package com.epam.aidial.core.mcp;

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

    @Override
    public void start(Promise<Void> startPromise) {
        log.info("MCP verticle started; transport adapter not yet wired (M.0.0-bridge)");
        startPromise.complete();
    }
}
