package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Alias;
import com.epam.aidial.core.config.AliasPresentation;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Router;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.alias.AliasService;
import com.epam.aidial.core.server.data.ListData;
import com.epam.aidial.core.server.data.RouterData;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
public class RouterController {

    private final ProxyContext context;

    @ApiOperation(
            method = "GET",
            path = "/v1/routers/{router_name}",
            operationId = "getRouter",
            tags = {"Deployment listing"},
            parameters = {
                    @ApiParameter(name = "router_name", in = ParameterIn.PATH, required = true, description = "Router name, or the name of an alias of type router")
            },
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = RouterData.class)),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404)
            }
    )
    public Future<?> getRouter(String routerId) {
        Router router = context.getConfig().getRouters().get(routerId);
        if (router == null) {
            return getRouterAlias(routerId);
        }
        if (!router.hasAccess(context.getUserRoles())) {
            return context.respond(HttpStatus.FORBIDDEN);
        }
        return context.respond(HttpStatus.OK, RouterData.toData(router));
    }

    @ApiOperation(
            method = "GET",
            path = "/v1/routers",
            operationId = "getRouters",
            tags = {"Deployment listing"},
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = ListData.class, typeArguments = {RouterData.class}))
            }
    )
    public Future<?> getRouters() {
        List<RouterData> routers = new ArrayList<>();
        for (Router router : context.getConfig().getRouters().values()) {
            if (router.hasAccess(context.getUserRoles())) {
                routers.add(RouterData.toData(router));
            }
        }
        // only config aliases have the type router, and their router target is read from the config: no storage read
        for (Deployment view : context.getProxy().getDeploymentService().listAliasViews(context, AliasPresentation.ROUTER, List::of)) {
            routers.add(RouterData.toData((Router) view));
        }
        ListData<RouterData> list = new ListData<>();
        list.setData(routers);
        return context.respond(HttpStatus.OK, list);
    }

    /**
     * The entry of the config alias of type router {@code name}. A stored alias never has this type, so storage is not
     * read and this stays on the event loop: {@code 404} for no such alias or a missing target, {@code 403} when the
     * caller may not use it.
     */
    private Future<?> getRouterAlias(String name) {
        Alias alias = context.getConfig().getAliases().get(name);
        if (alias == null || alias.getEffectivePresentation() != AliasPresentation.ROUTER) {
            return context.respond(HttpStatus.NOT_FOUND);
        }
        AliasService aliasService = context.getProxy().getDeploymentService().getAliasService();
        if (Boolean.FALSE.equals(aliasService.hasConfigAccess(context, alias))) {
            return context.respond(HttpStatus.FORBIDDEN);
        }
        Deployment view = aliasService.toView(context, alias);
        if (view == null) {
            return context.respond(HttpStatus.NOT_FOUND);
        }
        return context.respond(HttpStatus.OK, RouterData.toData((Router) view));
    }
}
