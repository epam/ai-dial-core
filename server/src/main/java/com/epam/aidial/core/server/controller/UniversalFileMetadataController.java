package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.FileTarget;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.data.FileMetadata;
import com.epam.aidial.core.storage.data.MetadataBase;
import com.epam.aidial.core.storage.data.ResourceFolderMetadata;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import io.vertx.core.Future;
import io.vertx.core.http.HttpHeaders;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;

@Slf4j
public class UniversalFileMetadataController {
    private final Proxy proxy;
    private final ProxyContext context;
    private final String encodedFilePath;

    public UniversalFileMetadataController(Proxy proxy, ProxyContext context, String encodedFilePath) {
        this.proxy = proxy;
        this.context = context;
        this.encodedFilePath = encodedFilePath == null ? "" : encodedFilePath;
    }

    @ApiOperation(
            method = "GET", path = "/v1/metadata/skills/{bucket}/{path}/files/{filePath}", operationId = "getSkillFileMetadataV1",
            tags = {"Files", "Skills"},
            parameters = {
                    @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true),
                    @ApiParameter(name = "path", in = ParameterIn.PATH, required = true, description = "Owning resource path before the first files segment"),
                    @ApiParameter(name = "filePath", in = ParameterIn.PATH, required = true, description = "Relative file path or folder ending in /"),
                    @ApiParameter(name = "token", in = ParameterIn.QUERY, description = "Continuation token from the previous folder listing"),
                    @ApiParameter(name = "limit", in = ParameterIn.QUERY, schema = Integer.class, description = "Page size, default 100, range 0..1000"),
                    @ApiParameter(name = "recursive", in = ParameterIn.QUERY, schema = Boolean.class),
                    @ApiParameter(name = "permissions", in = ParameterIn.QUERY, schema = Boolean.class, description = "Include owning-resource permissions on every node")
            },
            responses = {
                    @ApiResponse(code = 200, body = @ApiSchema(implementation = FileMetadata.class),
                            description = "File metadata with file ETag and owning resource reference, or a folder listing"),
                    @ApiResponse(code = 400), @ApiResponse(code = 403), @ApiResponse(code = 404), @ApiResponse(code = 500)
            })
    @ApiOperation(
            method = "GET", path = "/v1/metadata/projects/{bucket}/{path}/files/{filePath}", operationId = "getProjectFileMetadataV1",
            tags = {"Files", "Projects"},
            parameters = {
                    @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true),
                    @ApiParameter(name = "path", in = ParameterIn.PATH, required = true, description = "Owning resource path before the first files segment"),
                    @ApiParameter(name = "filePath", in = ParameterIn.PATH, required = true, description = "Relative file path or folder ending in /"),
                    @ApiParameter(name = "token", in = ParameterIn.QUERY, description = "Continuation token from the previous folder listing"),
                    @ApiParameter(name = "limit", in = ParameterIn.QUERY, schema = Integer.class, description = "Page size, default 100, range 0..1000"),
                    @ApiParameter(name = "recursive", in = ParameterIn.QUERY, schema = Boolean.class),
                    @ApiParameter(name = "permissions", in = ParameterIn.QUERY, schema = Boolean.class, description = "Include owning-resource permissions on every node")
            },
            responses = {
                    @ApiResponse(code = 200, body = @ApiSchema(implementation = FileMetadata.class),
                            description = "File metadata with file ETag and owning resource reference, or a folder listing"),
                    @ApiResponse(code = 400), @ApiResponse(code = 403), @ApiResponse(code = 404), @ApiResponse(code = 500)
            })
    public Future<?> handle(String resourceUrl) {
        ResourceDescriptor resource;
        try {
            resource = ResourceDescriptorFactory.fromAnyUrl(resourceUrl, proxy.getEncryptionService());
        } catch (IllegalArgumentException e) {
            return context.respond(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        proxy.getTaskExecutor().submit(() -> {
            Set<ResourceAccessType> permissions = proxy.getAccessService().lookupPermissions(Set.of(resource), context).get(resource);
            if (!permissions.contains(ResourceAccessType.READ)) {
                throw new HttpException(HttpStatus.FORBIDDEN, "You don't have an access to: " + resourceUrl);
            }
            FileTarget target = ResourceDescriptorFactory.fromFileUrl(resource.getUrl() + "/files/" + encodedFilePath, proxy.getEncryptionService());
            target.validatePath();
            ProxyUtil.MetadataQuery query = ProxyUtil.metadataQuery(context.getRequest());
            MetadataBase metadata = proxy.getComplexResourceService().getFileMetadata(resource, target.filePath(), query.token(), query.limit(), query.recursive());
            if (metadata != null && context.getBooleanRequestQueryParam("permissions")) {
                populatePermissions(metadata, permissions);
            }
            return metadata;
        }).compose(metadata -> {
            if (metadata == null) {
                return context.respond(HttpStatus.NOT_FOUND).mapEmpty();
            }
            String accept = context.getRequest().getHeader(HttpHeaders.ACCEPT);
            String contentType = accept != null && accept.contains(MetadataBase.MIME_TYPE) ? MetadataBase.MIME_TYPE : "application/json";
            return context.respond(HttpStatus.OK, contentType, metadata).mapEmpty();
        }).onFailure(error -> {
            log.warn("Failed complex file metadata request: {}", resourceUrl, error);
            if (error instanceof IllegalArgumentException) {
                String message = "Limit is out of allowed range".equals(error.getMessage())
                        ? "Limit is out of allowed range: [0, 1000]" : error.getMessage();
                context.respond(HttpStatus.BAD_REQUEST, message);
            } else {
                context.respond(error, "Failed to list files by path " + resourceUrl);
            }
        });
        return Future.succeededFuture();
    }

    private static void populatePermissions(MetadataBase metadata, Set<ResourceAccessType> permissions) {
        metadata.setPermissions(permissions);
        if (metadata instanceof ResourceFolderMetadata folder) {
            for (MetadataBase item : folder.getItems()) {
                populatePermissions(item, permissions);
            }
        }
    }
}
