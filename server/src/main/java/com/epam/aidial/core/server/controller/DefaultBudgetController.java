package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.DefaultBudget;
import com.epam.aidial.core.server.limiter.budget.DefaultBudgetService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import lombok.extern.slf4j.Slf4j;

/**
 * The caller's own default budget. Only a user signed in with a JWT, calling directly, may read or set it.
 */
@Slf4j
public class DefaultBudgetController {

    private static final DefaultBudget UNSET = new DefaultBudget(null);

    private final Proxy proxy;
    private final ProxyContext context;
    private final DefaultBudgetService service;

    public DefaultBudgetController(Proxy proxy, ProxyContext context) {
        this.proxy = proxy;
        this.context = context;
        this.service = proxy.getDefaultBudgetService();
    }

    @ApiOperation(
            method = "GET",
            path = "/v1/user/budgets/default",
            operationId = "getUserDefaultBudget",
            tags = {"Limits"},
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = DefaultBudget.class)),
                    @ApiResponse(code = 401),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 500)
            }
    )
    public Future<?> getDefault() {
        if (isForbidden()) {
            return Future.succeededFuture();
        }
        proxy.getTaskExecutor().submit(() -> service.findDefault(context))
                .onSuccess(budget -> context.respond(HttpStatus.OK, budget == null ? UNSET : new DefaultBudget(budget.id())))
                .onFailure(this::handleError);
        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "PUT",
            path = "/v1/user/budgets/default",
            operationId = "putUserDefaultBudget",
            tags = {"Limits"},
            requestBody = @ApiSchema(implementation = DefaultBudget.class),
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = DefaultBudget.class)),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 401),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 500)
            }
    )
    public Future<?> putDefault() {
        if (isForbidden()) {
            return Future.succeededFuture();
        }
        context.getRequest().body().onSuccess(this::putDefault).onFailure(this::handleError);
        return Future.succeededFuture();
    }

    private void putDefault(Buffer body) {
        JsonNode budget = parseBudget(body);
        if (budget == null) {
            context.respond(HttpStatus.BAD_REQUEST, "The body must be a JSON object with a \"budget\" string or null");
            return;
        }
        if (budget.isNull()) {
            proxy.getTaskExecutor().submit(() -> {
                service.clearDefault(context);
                return null;
            }).onSuccess(ignored -> context.respond(HttpStatus.OK, UNSET)).onFailure(this::handleError);
            return;
        }
        String budgetId = budget.textValue();
        proxy.getTaskExecutor().submit(() -> service.setDefault(context, budgetId))
                .onSuccess(isSet -> {
                    if (isSet) {
                        context.respond(HttpStatus.OK, new DefaultBudget(budgetId));
                    } else {
                        // the same message for any id the caller does not hold, so it tells nothing about other people's budgets
                        context.respond(HttpStatus.BAD_REQUEST, "You hold no budget " + budgetId);
                    }
                })
                .onFailure(this::handleError);
    }

    /**
     * @return the {@code budget} field, a text or a null node, or {@code null} when the body is not such an object
     */
    private static JsonNode parseBudget(Buffer body) {
        JsonNode request;
        try {
            request = ProxyUtil.MAPPER.readTree(body.getBytes());
        } catch (Exception e) {
            return null;
        }
        JsonNode budget = request == null || !request.isObject() ? null : request.get("budget");
        return budget != null && (budget.isTextual() || budget.isNull()) ? budget : null;
    }

    /**
     * An API key has no budgets, and an application calling with a per-request key must not choose who pays for the user.
     */
    private boolean isForbidden() {
        if (context.getApiKeyData().getPerRequestKey() != null || context.getExtractedClaims() == null || context.getUserId() == null) {
            context.respond(HttpStatus.FORBIDDEN, "Only a signed-in user can manage their default budget");
            return true;
        }
        return false;
    }

    private void handleError(Throwable error) {
        log.error("Failed to process the default budget", error);
        context.respond(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to process the default budget");
    }
}
