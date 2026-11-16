package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Router;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.server.ProxyContext;
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
                    @ApiParameter(name = "router_name", in = ParameterIn.PATH, required = true, description = "Router name")
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
            return context.respond(HttpStatus.NOT_FOUND);
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
        ListData<RouterData> list = new ListData<>();
        list.setData(routers);
        return context.respond(HttpStatus.OK, list);
    }
}
