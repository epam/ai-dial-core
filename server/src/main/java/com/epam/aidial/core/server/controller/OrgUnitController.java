package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.openapi.annotations.ApiExtension;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.OpenApiDescriptions;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.orgunit.CreateOrgUnitRequest;
import com.epam.aidial.core.server.data.orgunit.DeleteOrgUnitResult;
import com.epam.aidial.core.server.data.orgunit.OrgUnitData;
import com.epam.aidial.core.server.data.orgunit.UpdateOrgUnitRequest;
import com.epam.aidial.core.server.service.OrgUnitService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.util.UrlUtil;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import io.vertx.core.Future;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;

/**
 * Reads, creates, renames and removes org units. Switched off, every route answers as one that does not exist.
 */
@Slf4j
public class OrgUnitController {

    private final Proxy proxy;
    private final ProxyContext context;
    private final OrgUnitService orgUnitService;

    public OrgUnitController(Proxy proxy, ProxyContext context) {
        this.proxy = proxy;
        this.context = context;
        this.orgUnitService = proxy.getOrgUnitService();
    }

    @ApiOperation(
            method = "GET",
            path = "/v1/org-units/{path}",
            operationId = "getOrgUnit",
            tags = {"Org units"},
            parameters = {
                    @ApiParameter(name = "path", in = ParameterIn.PATH, required = true,
                            description = "public, then each unit's name, separated by '/': public/Acme/Risk/US Risk"),
                    @ApiParameter(name = "recursive", in = ParameterIn.QUERY, schema = Boolean.class,
                            description = "If true, items lists every unit below, parents before children; otherwise only the units directly below.")
            },
            responses = {
                    @ApiResponse(code = 200, description = OpenApiDescriptions.RESPONSE_SUCCESS,
                            body = @ApiSchema(implementation = OrgUnitData.class)),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 500)
            },
            extensions = {
                    @ApiExtension(name = "x-preview", value = "true")
            }
    )
    public Future<?> getOrgUnit(String rawPath) {
        boolean recursive = context.getBooleanRequestQueryParam("recursive");
        boolean admin = proxy.getAccessService().hasExplicitAdminAccess(context);
        proxy.getTaskExecutor()
                .submit(() -> {
                    requireEnabled();
                    return orgUnitService.read(decode(rawPath), recursive, context, admin);
                })
                .onSuccess(unit -> context.respond(HttpStatus.OK, unit))
                .onFailure(this::respondError);
        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/org-units/{path}",
            operationId = "createOrgUnit",
            requestBody = @ApiSchema(implementation = CreateOrgUnitRequest.class),
            tags = {"Org units"},
            parameters = {
                    @ApiParameter(name = "path", in = ParameterIn.PATH, required = true,
                            description = "The parent: public, then each unit's name, separated by '/'")
            },
            responses = {
                    @ApiResponse(code = 200, description = "The unit is created",
                            body = @ApiSchema(implementation = OrgUnitData.class)),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 409, description = "The name is taken, the depth limit is reached, or no read rule on the folder or above it closes it"),
                    @ApiResponse(code = 500)
            },
            extensions = {
                    @ApiExtension(name = "x-preview", value = "true")
            }
    )
    public Future<?> createOrgUnit(String rawPath) {
        String author = context.getUserDisplayName();
        context.getRequest().body()
                .compose(body -> {
                    requireEnabled();
                    requireAdmin();
                    List<String> parentPath = decode(rawPath);
                    CreateOrgUnitRequest request = parse(body.toString(), CreateOrgUnitRequest.class,
                            "Request body must be {\"name\": \"<unit name>\", \"displayName\": \"<optional>\"}");
                    return proxy.getTaskExecutor().submit(() ->
                            orgUnitService.create(parentPath, request.name(), request.displayName(), author));
                })
                .onSuccess(unit -> context.respond(HttpStatus.OK, unit))
                .onFailure(this::respondError);
        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "PATCH",
            path = "/v1/org-units/{path}",
            operationId = "updateOrgUnit",
            requestBody = @ApiSchema(implementation = UpdateOrgUnitRequest.class),
            tags = {"Org units"},
            parameters = {
                    @ApiParameter(name = "path", in = ParameterIn.PATH, required = true,
                            description = "public, then each unit's name, separated by '/': public/Acme/Risk/US Risk")
            },
            responses = {
                    @ApiResponse(code = 200, description = "The display name is changed",
                            body = @ApiSchema(implementation = OrgUnitData.class)),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 500)
            },
            extensions = {
                    @ApiExtension(name = "x-preview", value = "true")
            }
    )
    public Future<?> updateOrgUnit(String rawPath) {
        context.getRequest().body()
                .compose(body -> {
                    requireEnabled();
                    requireAdmin();
                    List<String> path = decode(rawPath);
                    UpdateOrgUnitRequest request = parse(body.toString(), UpdateOrgUnitRequest.class,
                            "Request body must be {\"displayName\": \"<what clients show>\"}");
                    return proxy.getTaskExecutor().submit(() -> orgUnitService.updateDisplayName(path, request.displayName()));
                })
                .onSuccess(unit -> context.respond(HttpStatus.OK, unit))
                .onFailure(this::respondError);
        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "DELETE",
            path = "/v1/org-units/{path}",
            operationId = "deleteOrgUnit",
            tags = {"Org units"},
            parameters = {
                    @ApiParameter(name = "path", in = ParameterIn.PATH, required = true,
                            description = "public, then each unit's name, separated by '/': public/Acme/Risk/US Risk"),
                    @ApiParameter(name = "recursive", in = ParameterIn.QUERY, schema = Boolean.class,
                            description = "Also remove every unit below it")
            },
            responses = {
                    @ApiResponse(code = 200, description = "The unit is removed, with its folder markers and, unless content landed in it meanwhile, the read rules of its subtree",
                            body = @ApiSchema(implementation = DeleteOrgUnitResult.class)),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 409, description = "The unit holds content, or has units below it and recursive is not set"),
                    @ApiResponse(code = 500)
            },
            extensions = {
                    @ApiExtension(name = "x-preview", value = "true")
            }
    )
    public Future<?> deleteOrgUnit(String rawPath) {
        boolean recursive = context.getBooleanRequestQueryParam("recursive");
        proxy.getTaskExecutor()
                .submit(() -> {
                    requireEnabled();
                    requireAdmin();
                    return new DeleteOrgUnitResult(orgUnitService.delete(decode(rawPath), recursive));
                })
                .onSuccess(result -> context.respond(HttpStatus.OK, result))
                .onFailure(this::respondError);
        return Future.succeededFuture();
    }

    /**
     * Each segment decoded on its own. Refuses a segment that isn't valid url encoding, or that decodes to a "/": unit
     * names never contain one, so an encoded separator must not reach another unit.
     */
    private static List<String> decode(String rawPath) {
        List<String> segments;
        try {
            segments = Arrays.stream(rawPath.split("/", -1)).map(UrlUtil::decodePath).toList();
        } catch (RuntimeException e) {
            segments = null;
        }
        if (segments == null || segments.stream().anyMatch(segment -> segment.contains("/"))) {
            throw new IllegalArgumentException("Invalid org unit path: " + rawPath);
        }
        return segments;
    }

    private void requireEnabled() {
        if (!orgUnitService.isEnabled()) {
            throw new HttpException(HttpStatus.NOT_FOUND, "Route is not found");
        }
    }

    private void requireAdmin() {
        if (!proxy.getAccessService().hasExplicitAdminAccess(context)) {
            throw new HttpException(HttpStatus.FORBIDDEN, "Only administrators may manage org units");
        }
    }

    private static <T> T parse(String body, Class<T> type, String message) {
        T value;
        try {
            value = body == null || body.isEmpty() ? null : ProxyUtil.MAPPER.readValue(body, type);
        } catch (UnrecognizedPropertyException e) {
            throw new IllegalArgumentException("Unknown field '%s'. %s".formatted(e.getPropertyName(), message));
        } catch (Exception e) {
            throw new IllegalArgumentException(message);
        }
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private void respondError(Throwable error) {
        if (error instanceof HttpException httpException) {
            context.respond(httpException.getStatus(), httpException.getMessage());
        } else if (error instanceof ResourceNotFoundException) {
            context.respond(HttpStatus.NOT_FOUND, error.getMessage());
        } else if (error instanceof IllegalArgumentException) {
            context.respond(HttpStatus.BAD_REQUEST, error.getMessage());
        } else {
            log.error("Org unit request failed", error);
            context.respond(HttpStatus.INTERNAL_SERVER_ERROR, "Org unit request failed");
        }
    }
}
