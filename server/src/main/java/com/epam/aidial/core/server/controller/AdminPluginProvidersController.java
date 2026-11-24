package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.openapi.annotations.ApiExtension;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.data.PluginProviderItem;
import com.epam.aidial.core.server.security.ConfigAuthorizationService;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;

import java.util.List;

/**
 * Admin-only list of the plugin providers Core loaded at start, ordered by id, with what each declared about itself.
 * The answer is kept since start: it calls no plugin code and does not change with the config.
 */
public class AdminPluginProvidersController implements Controller {

    private final ProxyContext context;
    private final ConfigAuthorizationService authorizationService;
    private final MergedConfigStore mergedConfigStore;

    public AdminPluginProvidersController(ProxyContext context, ConfigAuthorizationService authorizationService,
                                          MergedConfigStore mergedConfigStore) {
        this.context = context;
        this.authorizationService = authorizationService;
        this.mergedConfigStore = mergedConfigStore;
    }

    @Override
    @ApiOperation(
            method = "GET",
            path = "/v1/admin/plugin-providers",
            operationId = "listPluginProviders",
            tags = {"Admin"},
            responses = {
                    @ApiResponse(code = 200, description = "The plugin providers loaded at start, ordered by id",
                            body = @ApiSchema(implementation = List.class, typeArguments = {PluginProviderItem.class})),
                    @ApiResponse(code = 403)
            },
            extensions = {
                    @ApiExtension(name = "x-preview", value = "true")
            }
    )
    public Future<?> handle() {
        if (!authorizationService.isAdmin(context)) {
            context.respond(HttpStatus.FORBIDDEN, "Forbidden");
            return Future.succeededFuture();
        }
        context.respond(HttpStatus.OK, mergedConfigStore.getPluginRegistry().list());
        return Future.succeededFuture();
    }
}
