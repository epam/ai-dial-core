package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.openapi.annotations.ApiExtension;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.service.resource.ComplexResourceService;
import com.epam.aidial.core.storage.data.MetadataBase;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import io.vertx.core.Future;
import io.vertx.core.http.HttpHeaders;
import lombok.extern.slf4j.Slf4j;

/**
 * Metadata listing for the v2 complex resource API. In children mode it lists the classified, enriched
 * immediate children of a grouping level; in files mode it lists the files of a resource's current version.
 */
@Slf4j
public class ComplexResourceMetadataController extends AccessControlBaseController {

    private final ComplexResourceService complexResourceService;
    // True to list files of a skill's current version; false to list the children of a grouping level.
    private final boolean filesMode;
    // Optional subfolder inside the version, for files mode.
    private final String subPath;

    public ComplexResourceMetadataController(Proxy proxy, ProxyContext context, boolean filesMode, String subPath) {
        super(proxy, context, false);
        this.complexResourceService = proxy.getComplexResourceService();
        this.filesMode = filesMode;
        this.subPath = subPath;
    }

    private String getContentType() {
        String acceptType = context.getRequest().getHeader(HttpHeaders.ACCEPT);
        return acceptType != null && acceptType.contains(MetadataBase.MIME_TYPE)
                ? MetadataBase.MIME_TYPE
                : "application/json";
    }

    @Override
    @ApiOperation(
            method = "GET",
            path = "/v2/metadata/skills/{bucket}/{path}",
            operationId = "listSkillMetadata",
            tags = {"Skills"},
            parameters = {
                    @ApiParameter(name = "bucket", in = ParameterIn.PATH, description = "The target bucket.", required = true),
                    @ApiParameter(name = "path", in = ParameterIn.PATH, required = true,
                            description = "The grouping folder path within the bucket; empty lists the bucket root."),
                    @ApiParameter(name = "token", in = ParameterIn.QUERY,
                            description = "Continuation token from a previous page; omit for the first page."),
                    @ApiParameter(name = "limit", in = ParameterIn.QUERY, schema = Integer.class,
                            description = "Maximum number of items per page (0-1000, default 100)."),
                    @ApiParameter(name = "recursive", in = ParameterIn.QUERY, schema = Boolean.class,
                            description = "If true, lists the whole subtree; otherwise only immediate children.")
            },
            responses = {
                    @ApiResponse(code = 200, description = "The complex resources and grouping folders at this level",
                            body = @ApiSchema(implementation = MetadataBase.class)),
                    @ApiResponse(code = 400, description = "Bad request - limit out of range"),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404, description = "Grouping folder not found"),
                    @ApiResponse(code = 500)
            },
            extensions = {
                    @ApiExtension(name = "x-preview", value = "true")
            }
    )
    @ApiOperation(
            method = "GET",
            path = "/v2/metadata/skills/{bucket}/{path}/files/{filePath}",
            operationId = "listSkillFileMetadata",
            tags = {"Skills"},
            parameters = {
                    @ApiParameter(name = "bucket", in = ParameterIn.PATH, description = "The target bucket.", required = true),
                    @ApiParameter(name = "path", in = ParameterIn.PATH, required = true,
                            description = "The resource path within the bucket."),
                    @ApiParameter(name = "filePath", in = ParameterIn.PATH, required = true,
                            description = "The relative path of a subfolder inside the resource to scope the listing."),
                    @ApiParameter(name = "token", in = ParameterIn.QUERY,
                            description = "Continuation token from a previous page; omit for the first page."),
                    @ApiParameter(name = "limit", in = ParameterIn.QUERY, schema = Integer.class,
                            description = "Maximum number of items per page (0-1000, default 100)."),
                    @ApiParameter(name = "recursive", in = ParameterIn.QUERY, schema = Boolean.class,
                            description = "If true, lists all files of the current version; otherwise only immediate entries.")
            },
            responses = {
                    @ApiResponse(code = 200, description = "The files of the resource's current version",
                            body = @ApiSchema(implementation = MetadataBase.class)),
                    @ApiResponse(code = 400, description = "Bad request - limit out of range"),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404, description = "Resource not found"),
                    @ApiResponse(code = 500)
            },
            extensions = {
                    @ApiExtension(name = "x-preview", value = "true")
            }
    )
    protected Future<?> handle(ResourceDescriptor resource, boolean hasWriteAccess) {
        String token = context.getRequest().getParam("token");
        int limit = Integer.parseInt(context.getRequest().getParam("limit", "100"));
        boolean recursive = Boolean.parseBoolean(context.getRequest().getParam("recursive", "false"));
        if (limit < 0 || limit > 1000) {
            return context.respond(HttpStatus.BAD_REQUEST, "Limit is out of allowed range: [0, 1000]");
        }

        proxy.getTaskExecutor().submit(() -> filesMode
                        ? complexResourceService.listFiles(resource, subPath, token, limit, recursive)
                        : complexResourceService.listChildren(resource, token, limit, recursive))
                .onSuccess(result -> {
                    if (result == null) {
                        context.respond(HttpStatus.NOT_FOUND);
                    } else {
                        proxy.getAccessService().filterForbidden(context, resource, result);
                        context.respond(HttpStatus.OK, getContentType(), result);
                    }
                })
                .onFailure(error -> {
                    log.warn("Failed to list metadata: {}", resource.getUrl(), error);
                    context.respond(error, "Failed to list metadata: " + resource.getUrl());
                });

        return Future.succeededFuture();
    }
}
