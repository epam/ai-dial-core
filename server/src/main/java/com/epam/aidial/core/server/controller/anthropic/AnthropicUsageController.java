package com.epam.aidial.core.server.controller.anthropic;

import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.AnthropicUsageData;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import io.vertx.core.http.HttpHeaders;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Serves the DIAL spend caps in the shape Claude Code reads from Anthropic's {@code /api/oauth/usage}.
 */
@Slf4j
@RequiredArgsConstructor
public class AnthropicUsageController {

    private final Proxy proxy;
    private final ProxyContext context;

    @ApiOperation(
            method = "GET",
            path = "/anthropic/api/oauth/usage",
            operationId = "getAnthropicUsage",
            tags = {"Anthropic"},
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = AnthropicUsageData.class)),
                    @ApiResponse(code = 401),
                    @ApiResponse(code = 500)
            }
    )
    public Future<?> handle() {
        proxy.getRateLimiter().getSpendCaps(context)
                .onSuccess(caps -> {
                    context.getResponse().putHeader(HttpHeaders.CACHE_CONTROL, "no-store");
                    context.respond(HttpStatus.OK, AnthropicLimitResponses.buildUsage(caps));
                })
                .onFailure(error -> {
                    log.error("AnthropicUsageController. Failed to get spend limits", error);
                    context.respond(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to get spend limits");
                });
        return Future.succeededFuture();
    }
}
