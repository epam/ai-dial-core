package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.openapi.annotations.ApiHeader;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.OpenApiDescriptions;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.security.ConfigAuthorizationService;
import com.epam.aidial.core.server.security.EntityBucketBinding;
import com.epam.aidial.core.server.security.Operation;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.data.MetadataBase;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import io.vertx.core.Future;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import lombok.extern.slf4j.Slf4j;

/**
 * Read-only sibling of {@link ConfigResourceController} that serves
 * {@code GET /v1/metadata/{type}/{bucket}/{path}} — the unified metadata-listing route.
 * Returns {@link com.epam.aidial.core.storage.data.ResourceFolderMetadata} /
 * {@link com.epam.aidial.core.storage.data.ResourceItemMetadata} (same shape the user Resource
 * API uses), so listings are now blob-only. File-sourced entries are not surfaced here; during
 * MVP, operators consult {@code aidial.config.json} directly.
 *
 * <p>The singleton {@code settings} type has no listing surface — responds 405. Every 405
 * response on the metadata route advertises {@code Allow: GET} because the metadata surface is
 * read-only; the singleton's write verbs live on the entity URL {@code /v1/settings/platform/global}.
 */
@Slf4j
public class ConfigResourceMetadataController implements Controller {

    private static final String ALLOW_GET = "GET";

    private final ProxyContext context;
    private final ConfigAuthorizationService authorizationService;
    private final ResourceService resourceService;
    private final AsyncTaskExecutor taskExecutor;
    private final String entityType;
    private final String bucket;
    private final String path;

    public ConfigResourceMetadataController(ProxyContext context,
                                            ConfigAuthorizationService authorizationService,
                                            ResourceService resourceService,
                                            AsyncTaskExecutor taskExecutor,
                                            String entityType,
                                            String bucket,
                                            String path) {
        this.context = context;
        this.authorizationService = authorizationService;
        this.resourceService = resourceService;
        this.taskExecutor = taskExecutor;
        this.entityType = entityType;
        this.bucket = bucket;
        this.path = path;
    }

    @ApiOperation(
            method = "GET",
            path = "/v1/metadata/settings/{bucket}/{path}",
            operationId = "getGlobalSettingsMetadata",
            tags = {"Global Settings"},
            parameters = {
                @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                @ApiParameter(name = "path", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.METADATA_PATH_APPLICATIONS),
                @ApiParameter(name = "token", in = ParameterIn.QUERY, description = OpenApiDescriptions.METADATA_TOKEN),
                @ApiParameter(name = "limit", in = ParameterIn.QUERY, description = OpenApiDescriptions.METADATA_LIMIT, schema = Integer.class),
                @ApiParameter(name = "recursive", in = ParameterIn.QUERY, description = OpenApiDescriptions.METADATA_RECURSIVE, schema = Boolean.class),
            },
            responses = {
                @ApiResponse(code = 400),
                @ApiResponse(code = 403),
                @ApiResponse(code = 404),
                @ApiResponse(code = 405, description = "Metadata listing is not supported for Global Settings",
                    headers = {
                        @ApiHeader(name = "Allow", description = "Supported HTTP methods", schema = String.class)
                    }),
                @ApiResponse(code = 500)
            }
    )
    @ApiOperation(
            method = "GET",
            path = "/v1/metadata/models/{bucket}/{path}",
            operationId = "getModelMetadata",
            tags = {"Models"},
            parameters = {
                @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                @ApiParameter(name = "path", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.METADATA_PATH_APPLICATIONS),
                @ApiParameter(name = "token", in = ParameterIn.QUERY, description = OpenApiDescriptions.METADATA_TOKEN),
                @ApiParameter(name = "limit", in = ParameterIn.QUERY, description = OpenApiDescriptions.METADATA_LIMIT, schema = Integer.class),
                @ApiParameter(name = "recursive", in = ParameterIn.QUERY, description = OpenApiDescriptions.METADATA_RECURSIVE, schema = Boolean.class),
            },
            responses = {
                @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = MetadataBase.class)),
                @ApiResponse(code = 400),
                @ApiResponse(code = 403),
                @ApiResponse(code = 404),
                @ApiResponse(code = 500)
            }
            )
    @ApiOperation(
            method = "GET",
            path = "/v1/metadata/interceptors/{bucket}/{path}",
            operationId = "getInterceptorMetadata",
            tags = {"Interceptors"},
            parameters = {
                @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                @ApiParameter(name = "path", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.METADATA_PATH_APPLICATIONS),
                @ApiParameter(name = "token", in = ParameterIn.QUERY, description = OpenApiDescriptions.METADATA_TOKEN),
                @ApiParameter(name = "limit", in = ParameterIn.QUERY, description = OpenApiDescriptions.METADATA_LIMIT, schema = Integer.class),
                @ApiParameter(name = "recursive", in = ParameterIn.QUERY, description = OpenApiDescriptions.METADATA_RECURSIVE, schema = Boolean.class),
            },
            responses = {
                @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = MetadataBase.class)),
                @ApiResponse(code = 400),
                @ApiResponse(code = 403),
                @ApiResponse(code = 404),
                @ApiResponse(code = 500)
            }
    )
    @ApiOperation(
            method = "GET",
            path = "/v1/metadata/roles/{bucket}/{path}",
            operationId = "getRoleMetadata",
            tags = {"Roles"},
            parameters = {
                @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                @ApiParameter(name = "path", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.METADATA_PATH_APPLICATIONS),
                @ApiParameter(name = "token", in = ParameterIn.QUERY, description = OpenApiDescriptions.METADATA_TOKEN),
                @ApiParameter(name = "limit", in = ParameterIn.QUERY, description = OpenApiDescriptions.METADATA_LIMIT, schema = Integer.class),
                @ApiParameter(name = "recursive", in = ParameterIn.QUERY, description = OpenApiDescriptions.METADATA_RECURSIVE, schema = Boolean.class),
            },
            responses = {
                @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = MetadataBase.class)),
                @ApiResponse(code = 400),
                @ApiResponse(code = 403),
                @ApiResponse(code = 404),
                @ApiResponse(code = 500)
            }
            )
    @ApiOperation(
            method = "GET",
            path = "/v1/metadata/keys/{bucket}/{path}",
            operationId = "getKeyMetadata",
            tags = {"Keys"},
            parameters = {
                @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                @ApiParameter(name = "path", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.METADATA_PATH_APPLICATIONS),
                @ApiParameter(name = "token", in = ParameterIn.QUERY, description = OpenApiDescriptions.METADATA_TOKEN),
                @ApiParameter(name = "limit", in = ParameterIn.QUERY, description = OpenApiDescriptions.METADATA_LIMIT, schema = Integer.class),
                @ApiParameter(name = "recursive", in = ParameterIn.QUERY, description = OpenApiDescriptions.METADATA_RECURSIVE, schema = Boolean.class),
            },
            responses = {
                @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = MetadataBase.class)),
                @ApiResponse(code = 400),
                @ApiResponse(code = 403),
                @ApiResponse(code = 404),
                @ApiResponse(code = 500)
            }
        )
    @ApiOperation(
            method = "GET",
            path = "/v1/metadata/routes/{bucket}/{path}",
            operationId = "getRouteMetadata",
            tags = {"Routes"},
            parameters = {
                @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                @ApiParameter(name = "path", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.METADATA_PATH_APPLICATIONS),
                @ApiParameter(name = "token", in = ParameterIn.QUERY, description = OpenApiDescriptions.METADATA_TOKEN),
                @ApiParameter(name = "limit", in = ParameterIn.QUERY, description = OpenApiDescriptions.METADATA_LIMIT, schema = Integer.class),
                @ApiParameter(name = "recursive", in = ParameterIn.QUERY, description = OpenApiDescriptions.METADATA_RECURSIVE, schema = Boolean.class),
            },
            responses = {
                @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = MetadataBase.class)),
                @ApiResponse(code = 400),
                @ApiResponse(code = 403),
                @ApiResponse(code = 404),
                @ApiResponse(code = 500)
            }
        )
    @ApiOperation(
            method = "GET",
            path = "/v1/metadata/schemas/{bucket}/{path}",
            operationId = "getSchemaMetadata",
            tags = {"Schemas"},
            parameters = {
                @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                @ApiParameter(name = "path", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.METADATA_PATH_APPLICATIONS),
                @ApiParameter(name = "token", in = ParameterIn.QUERY, description = OpenApiDescriptions.METADATA_TOKEN),
                @ApiParameter(name = "limit", in = ParameterIn.QUERY, description = OpenApiDescriptions.METADATA_LIMIT, schema = Integer.class),
                @ApiParameter(name = "recursive", in = ParameterIn.QUERY, description = OpenApiDescriptions.METADATA_RECURSIVE, schema = Boolean.class),
            },
            responses = {
                @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = MetadataBase.class)),
                @ApiResponse(code = 400),
                @ApiResponse(code = 403),
                @ApiResponse(code = 404),
                @ApiResponse(code = 500)
            }
        )
    @Override
    public Future<?> handle() throws Exception {
        if (!EntityBucketBinding.isAllowed(entityType, bucket)) {
            // Body-less 404 — defense-in-depth, indistinguishable from "entity not found".
            // See 04-security-and-audit.md §1.2.
            context.respond(HttpStatus.NOT_FOUND);
            return Future.succeededFuture();
        }

        HttpMethod method = context.getRequest().method();
        if (method != HttpMethod.GET && method != HttpMethod.HEAD) {
            context.putHeader("Allow", ALLOW_GET);
            context.respond(HttpStatus.METHOD_NOT_ALLOWED, "Not implemented");
            return Future.succeededFuture();
        }

        if (!authorizationService.isAuthorized(context, entityType, path, bucket, Operation.READ)) {
            context.respond(HttpStatus.FORBIDDEN, "Forbidden");
            return Future.succeededFuture();
        }

        if (ResourceTypes.of(entityType) == ResourceTypes.GLOBAL_SETTINGS) {
            context.putHeader("Allow", ALLOW_GET);
            context.respond(HttpStatus.METHOD_NOT_ALLOWED, "Not implemented");
            return Future.succeededFuture();
        }

        ProxyUtil.MetadataQuery query;
        try {
            query = ProxyUtil.metadataQuery(context.getRequest());
        } catch (IllegalArgumentException error) {
            context.respond(HttpStatus.BAD_REQUEST,
                    "Bad query parameters. Limit must be in [0, 1000] range. Recursive must be true/false");
            return Future.succeededFuture();
        }

        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.of(entityType), bucket, bucket + ResourceDescriptor.PATH_SEPARATOR, path);

        taskExecutor.submit(() -> resourceService.getMetadata(descriptor, query.token(), query.limit(), query.recursive()))
                .onSuccess(result -> {
                    if (result == null) {
                        context.respond(HttpStatus.NOT_FOUND, "Not found: " + descriptor.getUrl());
                    } else {
                        context.respond(HttpStatus.OK, getContentType(), result);
                    }
                }).onFailure(error -> {
                    log.warn("Can't list config metadata: {}", descriptor.getUrl(), error);
                    context.respond(HttpStatus.INTERNAL_SERVER_ERROR);
                });

        return Future.succeededFuture();
    }

    private String getContentType() {
        String acceptType = context.getRequest().getHeader(HttpHeaders.ACCEPT);
        return acceptType != null && acceptType.contains(MetadataBase.MIME_TYPE)
                ? MetadataBase.MIME_TYPE
                : "application/json";
    }
}