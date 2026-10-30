package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.openapi.annotations.ApiExtension;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.OpenApiDescriptions;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.orgunit.UserOrgUnits;
import com.epam.aidial.core.server.service.OrgUnitService;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public class UserOrgUnitsController implements Controller {

    private final Proxy proxy;
    private final ProxyContext context;

    @ApiOperation(
            method = "GET",
            path = "/v1/user/org-units",
            operationId = "getUserOrgUnits",
            tags = {"Org units"},
            responses = {
                    @ApiResponse(code = 200, description = OpenApiDescriptions.RESPONSE_SUCCESS,
                            body = @ApiSchema(implementation = UserOrgUnits.class)),
                    @ApiResponse(code = 401, description = OpenApiDescriptions.RESPONSE_UNAUTHORIZED),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 500)
            },
            extensions = {
                    @ApiExtension(name = "x-preview", value = "true")
            }
    )
    @Override
    public Future<?> handle() {
        OrgUnitService orgUnitService = proxy.getOrgUnitService();
        if (orgUnitService.isEnabled()) {
            boolean admin = proxy.getAccessService().hasExplicitAdminAccess(context);
            UserOrgUnits.AdminAccess adminAccess = admin ? UserOrgUnits.AdminAccess.FULL : UserOrgUnits.AdminAccess.NONE;
            proxy.getTaskExecutor()
                    .submit(() -> new UserOrgUnits(adminAccess, orgUnitService.unitsOf(context, admin)))
                    .onSuccess(units -> context.respond(HttpStatus.OK, units))
                    .onFailure(error -> context.respond(error, "Failed to read the caller's org units"));
        } else {
            context.respond(HttpStatus.NOT_FOUND, "Route is not found");
        }
        return Future.succeededFuture();
    }
}
