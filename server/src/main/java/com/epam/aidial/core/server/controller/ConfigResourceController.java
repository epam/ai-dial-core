package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.GlobalSettings;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Role;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.openapi.annotations.ApiHeader;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiOperations;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.OpenApiDescriptions;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.openapi.annotations.ResponseProfile;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.config.ConfigPostProcessor;
import com.epam.aidial.core.server.config.InvalidEntityRecord;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.config.SecretFieldProcessor;
import com.epam.aidial.core.server.config.ValidationWarning;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.EntityMetadata;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.security.ConfigAuthorizationService;
import com.epam.aidial.core.server.security.EntityBucketBinding;
import com.epam.aidial.core.server.security.Operation;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.util.UpstreamExtraDataMerger;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/**
 * Controller for the {@code /v1/{type}/{bucket}/{path}} CONFIG_RESOURCE route — gates on
 * the {@link EntityBucketBinding} allowlist and {@link ConfigAuthorizationService}, then
 * dispatches GET to per-type read handlers and PUT/DELETE for models, interceptors, roles,
 * keys, routes, schemas, and the settings singleton. POST is universally 405 with
 * {@code Allow: GET, PUT, DELETE}. PUT is a pure upsert honoring RFC 7232
 * {@code If-None-Match: *} (create-only gate, 412 if exists) and {@code If-Match: <etag>}
 * (412 on mismatch). Per-bucket listings live on the sibling
 * {@link com.epam.aidial.core.server.data.RouteTemplate#CONFIG_RESOURCE_METADATA} route.
 */
@Slf4j
public class ConfigResourceController implements Controller {

    private static final String SETTINGS_SINGLETON_NAME = "global";
    private static final String ALLOW_HEADER = "GET, PUT, DELETE";

    // Per design 02 §4 / 03 §3: conservative floor for admin-config entity names, extendable
    // on client request when a concrete workflow is broken. Applied to the URL-decoded segment.
    private static final Pattern ENTITY_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9._%:-]+$");

    private final ProxyContext context;
    private final ConfigAuthorizationService authorizationService;
    private final MergedConfigStore mergedConfigStore;
    private final ResourceService resourceService;
    private final AsyncTaskExecutor taskExecutor;
    private final SecretFieldProcessor secretFieldProcessor;
    private final boolean softValidation;
    private final ApiKeyStore apiKeyStore;
    private final LockService lockService;
    private final String entityType;
    private final String bucket;
    private final String path;

    public ConfigResourceController(ProxyContext context,
                                    ConfigAuthorizationService authorizationService,
                                    MergedConfigStore mergedConfigStore,
                                    ResourceService resourceService,
                                    AsyncTaskExecutor taskExecutor,
                                    SecretFieldProcessor secretFieldProcessor,
                                    boolean softValidation,
                                    ApiKeyStore apiKeyStore,
                                    LockService lockService,
                                    String entityType,
                                    String bucket,
                                    String path) {
        this.context = context;
        this.authorizationService = authorizationService;
        this.mergedConfigStore = mergedConfigStore;
        this.resourceService = resourceService;
        this.taskExecutor = taskExecutor;
        this.secretFieldProcessor = secretFieldProcessor;
        this.softValidation = softValidation;
        this.apiKeyStore = apiKeyStore;
        this.lockService = lockService;
        this.entityType = entityType;
        this.bucket = bucket;
        this.path = path;
    }

    @Override
    @ApiOperations({
            // Models
            @ApiOperation(
                    method = "GET",
                    path = "/v1/models/{bucket}/{path}",
                    operationId = "getModelByPath",
                    tags = {"Models"},
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "path", in = ParameterIn.PATH, required = true, description = "Model name"),
                            @ApiParameter(name = "If-None-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_MATCH)
                    },
                    responses = {
                            @ApiResponse(code = 200, description = "Success", body = @ApiSchema(allOf = {Model.class, EntityMetadata.class}),
                                    headers = {
                                            @ApiHeader(name = "ETag", description = "Entity tag for the model", required = true)
                                    })
                    },
                    responseProfile = ResponseProfile.CONFIG_RESOURCE_FULL
            ),
            @ApiOperation(
                    method = "PUT",
                    path = "/v1/models/{bucket}/{path}",
                    operationId = "saveModel",
                    requestBody = @ApiSchema(implementation = Model.class),
                    tags = {"Models"},
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "path", in = ParameterIn.PATH, required = true, description = "Model name"),
                            @ApiParameter(name = "If-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_MATCH),
                            @ApiParameter(name = "If-None-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_NONE_MATCH)
                    },
                    responses = {
                            @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = ResourceItemMetadata.class),
                                    headers = {
                                            @ApiHeader(name = "ETag", description = "Entity tag for the saved model", required = true)
                                    })
                    },
                    responseProfile = ResponseProfile.CONFIG_RESOURCE_FULL
            ),
            @ApiOperation(
                    method = "DELETE",
                    path = "/v1/models/{bucket}/{path}",
                    operationId = "deleteModel",
                    tags = {"Models"},
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "path", in = ParameterIn.PATH, required = true, description = "Model name"),
                            @ApiParameter(name = "If-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_MATCH)
                    },
                    responses = {
                            @ApiResponse(code = 204, description = "Success")
                    },
                    responseProfile = ResponseProfile.CONFIG_RESOURCE_FULL
            ),
            // Interceptors
            @ApiOperation(
                    method = "GET",
                    path = "/v1/interceptors/{bucket}/{path}",
                    operationId = "getInterceptor",
                    tags = {"Interceptors"},
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "path", in = ParameterIn.PATH, required = true, description = "Interceptor name"),
                            @ApiParameter(name = "If-None-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_MATCH)
                    },
                    responses = {
                            @ApiResponse(code = 200, description = "Success", body = @ApiSchema(allOf = {Interceptor.class, EntityMetadata.class}),
                                    headers = {
                                            @ApiHeader(name = "ETag", description = "Entity tag for the interceptor", required = true)
                                    })
                    },
                    responseProfile = ResponseProfile.CONFIG_RESOURCE_FULL
            ),
            @ApiOperation(
                    method = "PUT",
                    path = "/v1/interceptors/{bucket}/{path}",
                    operationId = "saveInterceptor",
                    requestBody = @ApiSchema(implementation = Interceptor.class),
                    tags = {"Interceptors"},
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "path", in = ParameterIn.PATH, required = true, description = "Interceptor name"),
                            @ApiParameter(name = "If-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_MATCH),
                            @ApiParameter(name = "If-None-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_NONE_MATCH)
                    },
                    responses = {
                            @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = ResourceItemMetadata.class),
                                    headers = {
                                            @ApiHeader(name = "ETag", description = "Entity tag for the saved interceptor", required = true)
                                    })
                    },
                    responseProfile = ResponseProfile.CONFIG_RESOURCE_FULL
            ),
            @ApiOperation(
                    method = "DELETE",
                    path = "/v1/interceptors/{bucket}/{path}",
                    operationId = "deleteInterceptor",
                    tags = {"Interceptors"},
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "path", in = ParameterIn.PATH, required = true, description = "Interceptor name"),
                            @ApiParameter(name = "If-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_MATCH)
                    },
                    responses = {
                            @ApiResponse(code = 204, description = "Success")
                    },
                    responseProfile = ResponseProfile.CONFIG_RESOURCE_FULL
            ),
            // Roles
            @ApiOperation(
                    method = "GET",
                    path = "/v1/roles/{bucket}/{path}",
                    operationId = "getRole",
                    tags = {"Roles"},
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "path", in = ParameterIn.PATH, required = true, description = "Role name"),
                            @ApiParameter(name = "If-None-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_MATCH)
                    },
                    responses = {
                            @ApiResponse(code = 200, description = "Success", body = @ApiSchema(allOf = {Role.class, EntityMetadata.class}),
                                    headers = {
                                            @ApiHeader(name = "ETag", description = "Entity tag for the role", required = true)
                                    })
                    },
                    responseProfile = ResponseProfile.CONFIG_RESOURCE_FULL
            ),
            @ApiOperation(
                    method = "PUT",
                    path = "/v1/roles/{bucket}/{path}",
                    operationId = "saveRole",
                    requestBody = @ApiSchema(implementation = Role.class),
                    tags = {"Roles"},
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "path", in = ParameterIn.PATH, required = true, description = "Role name"),
                            @ApiParameter(name = "If-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_MATCH),
                            @ApiParameter(name = "If-None-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_NONE_MATCH)
                    },
                    responses = {
                            @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = ResourceItemMetadata.class),
                                    headers = {
                                            @ApiHeader(name = "ETag", description = "Entity tag for the saved role", required = true)
                                    })
                    },
                    responseProfile = ResponseProfile.CONFIG_RESOURCE_FULL
            ),
            @ApiOperation(
                    method = "DELETE",
                    path = "/v1/roles/{bucket}/{path}",
                    operationId = "deleteRole",
                    tags = {"Roles"},
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "path", in = ParameterIn.PATH, required = true, description = "Role name"),
                            @ApiParameter(name = "If-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_MATCH)
                    },
                    responses = {
                            @ApiResponse(code = 204, description = "Success")
                    },
                    responseProfile = ResponseProfile.CONFIG_RESOURCE_FULL
            ),
            // Keys
            @ApiOperation(
                    method = "GET",
                    path = "/v1/keys/{bucket}/{path}",
                    operationId = "getKey",
                    tags = {"Keys"},
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "path", in = ParameterIn.PATH, required = true, description = "Key name"),
                            @ApiParameter(name = "If-None-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_MATCH)
                    },
                    responses = {
                            @ApiResponse(code = 200, description = "Success", body = @ApiSchema(allOf = {Key.class, EntityMetadata.class}),
                                    headers = {
                                            @ApiHeader(name = "ETag", description = "Entity tag for the key", required = true)
                                    })
                    },
                    responseProfile = ResponseProfile.CONFIG_RESOURCE_FULL
            ),
            @ApiOperation(
                    method = "PUT",
                    path = "/v1/keys/{bucket}/{path}",
                    operationId = "saveKey",
                    requestBody = @ApiSchema(implementation = Key.class),
                    tags = {"Keys"},
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "path", in = ParameterIn.PATH, required = true, description = "Key name"),
                            @ApiParameter(name = "If-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_MATCH),
                            @ApiParameter(name = "If-None-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_NONE_MATCH)
                    },
                    responses = {
                            @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = ResourceItemMetadata.class),
                                    headers = {
                                            @ApiHeader(name = "ETag", description = "Entity tag for the saved key", required = true)
                                    })
                    },
                    responseProfile = ResponseProfile.CONFIG_RESOURCE_FULL
            ),
            @ApiOperation(
                    method = "DELETE",
                    path = "/v1/keys/{bucket}/{path}",
                    operationId = "deleteKey",
                    tags = {"Keys"},
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "path", in = ParameterIn.PATH, required = true, description = "Key name"),
                            @ApiParameter(name = "If-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_MATCH)
                    },
                    responses = {
                            @ApiResponse(code = 204, description = "Success")
                    },
                    responseProfile = ResponseProfile.CONFIG_RESOURCE_FULL
            ),
            // Routes
            @ApiOperation(
                    method = "GET",
                    path = "/v1/routes/{bucket}/{path}",
                    operationId = "getRoute",
                    tags = {"Routes"},
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "path", in = ParameterIn.PATH, required = true, description = "Route name"),
                            @ApiParameter(name = "If-None-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_MATCH)
                    },
                    responses = {
                            @ApiResponse(code = 200, description = "Success", body = @ApiSchema(allOf = {Route.class, EntityMetadata.class}),
                                    headers = {
                                            @ApiHeader(name = "ETag", description = "Entity tag for the route", required = true)
                                    })
                    },
                    responseProfile = ResponseProfile.CONFIG_RESOURCE_FULL
            ),
            @ApiOperation(
                    method = "PUT",
                    path = "/v1/routes/{bucket}/{path}",
                    operationId = "saveRoute",
                    requestBody = @ApiSchema(implementation = Route.class),
                    tags = {"Routes"},
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "path", in = ParameterIn.PATH, required = true, description = "Route name"),
                            @ApiParameter(name = "If-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_MATCH),
                            @ApiParameter(name = "If-None-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_NONE_MATCH)
                    },
                    responses = {
                            @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = ResourceItemMetadata.class),
                                    headers = {
                                            @ApiHeader(name = "ETag", description = "Entity tag for the saved route", required = true)
                                    })
                    },
                    responseProfile = ResponseProfile.CONFIG_RESOURCE_FULL
            ),
            @ApiOperation(
                    method = "DELETE",
                    path = "/v1/routes/{bucket}/{path}",
                    operationId = "deleteRoute",
                    tags = {"Routes"},
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "path", in = ParameterIn.PATH, required = true, description = "Route name"),
                            @ApiParameter(name = "If-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_MATCH)
                    },
                    responses = {
                            @ApiResponse(code = 204, description = "Success")
                    },
                    responseProfile = ResponseProfile.CONFIG_RESOURCE_FULL
            ),
            // Schemas
            @ApiOperation(
                    method = "GET",
                    path = "/v1/schemas/{bucket}/{path}",
                    operationId = "getSchema",
                    tags = {"Schemas"},
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "path", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.SCHEMA_ID),
                            @ApiParameter(name = "If-None-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_MATCH)
                    },
                    responses = {
                            @ApiResponse(code = 200, description = "Success", body = @ApiSchema(allOfSchemaRefs = {"ProxyResponse"}, allOf = {EntityMetadata.class}),
                                    headers = {
                                            @ApiHeader(name = "ETag", description = "Entity tag for the schema", required = true)
                                    }
                            )
                    },
                    responseProfile = ResponseProfile.CONFIG_RESOURCE_FULL
            ),
            @ApiOperation(
                    method = "PUT",
                    path = "/v1/schemas/{bucket}/{path}",
                    operationId = "saveSchema",
                    tags = {"Schemas"},
                    requestBody = @ApiSchema(schemaRef = "ProxyRequest"),
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "path", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.SCHEMA_ID),
                            @ApiParameter(name = "If-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_MATCH),
                            @ApiParameter(name = "If-None-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_NONE_MATCH)
                    },
                    responses = {
                            @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = ResourceItemMetadata.class),
                                    headers = {
                                            @ApiHeader(name = "ETag", description = "Entity tag for the saved schema", required = true)
                                    })
                    },
                    responseProfile = ResponseProfile.CONFIG_RESOURCE_FULL
            ),
            @ApiOperation(
                    method = "DELETE",
                    path = "/v1/schemas/{bucket}/{path}",
                    operationId = "deleteSchema",
                    tags = {"Schemas"},
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "path", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.SCHEMA_ID),
                            @ApiParameter(name = "If-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_MATCH)
                    },
                    responses = {
                            @ApiResponse(code = 204, description = "Success")
                    },
                    responseProfile = ResponseProfile.CONFIG_RESOURCE_FULL
            ),
            // Global Settings
            @ApiOperation(
                    method = "GET",
                    path = "/v1/settings/{bucket}/{path}",
                    operationId = "getGlobalSettings",
                    tags = {"Global Settings"},
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "path", in = ParameterIn.PATH, required = true, description = "Must be 'global'"),
                            @ApiParameter(name = "If-None-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_MATCH)
                    },
                    responses = {
                            @ApiResponse(code = 200, description = "Success", body = @ApiSchema(allOf = {GlobalSettings.class, EntityMetadata.class}))
                    },
                    responseProfile = ResponseProfile.CONFIG_RESOURCE_FULL
            ),
            @ApiOperation(
                    method = "PUT",
                    path = "/v1/settings/{bucket}/{path}",
                    operationId = "saveGlobalSettings",
                    requestBody = @ApiSchema(implementation = GlobalSettings.class),
                    tags = {"Global Settings"},
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "path", in = ParameterIn.PATH, required = true, description = "Must be 'global'"),
                            @ApiParameter(name = "If-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_MATCH),
                            @ApiParameter(name = "If-None-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_NONE_MATCH)
                    },
                    responses = {
                            @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = ResourceItemMetadata.class),
                                    headers = {
                                            @ApiHeader(name = "ETag", description = "Entity tag for the saved settings", required = true)
                                    })
                    },
                    responseProfile = ResponseProfile.CONFIG_RESOURCE_FULL
            ),
            @ApiOperation(
                    method = "DELETE",
                    path = "/v1/settings/{bucket}/{path}",
                    operationId = "deleteGlobalSettings",
                    tags = {"Global Settings"},
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "path", in = ParameterIn.PATH, required = true, description = "Must be 'global'"),
                            @ApiParameter(name = "If-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_MATCH)
                    },
                    responses = {
                            @ApiResponse(code = 204, description = "Success")
                    },
                    responseProfile = ResponseProfile.CONFIG_RESOURCE_FULL
            )
    })
    public Future<?> handle() throws Exception {
        if (!EntityBucketBinding.isAllowed(entityType, bucket)) {
            // Body-less 404 — must be indistinguishable from "entity not found" so an unauthenticated
            // probe cannot tell from the response whether the (type, bucket) pair is invalid or
            // merely empty. See 04-security-and-audit.md §1.2.
            context.respond(HttpStatus.NOT_FOUND);
            return Future.succeededFuture();
        }

        HttpMethod method = context.getRequest().method();
        Operation operation = (method == HttpMethod.GET || method == HttpMethod.HEAD)
                ? Operation.READ
                : Operation.WRITE;
        if (!authorizationService.isAuthorized(context, entityType, path, bucket, operation)) {
            context.respond(HttpStatus.FORBIDDEN, "Forbidden");
            return Future.succeededFuture();
        }

        if (method == HttpMethod.GET || method == HttpMethod.HEAD) {
            return handleGet();
        }
        if ((method == HttpMethod.PUT || method == HttpMethod.DELETE)
                && !ENTITY_NAME_PATTERN.matcher(path == null ? "" : path).matches()) {
            context.respond(HttpStatus.BAD_REQUEST,
                    "Invalid entity name segment: must match " + ENTITY_NAME_PATTERN.pattern());
            return Future.succeededFuture();
        }
        if (resourceType() == ResourceTypes.GLOBAL_SETTINGS) {
            // Singleton has its own write surface: PUT-upsert + idempotent DELETE; POST is 405.
            if (method == HttpMethod.PUT) {
                return handleSettingsPut();
            }
            if (method == HttpMethod.DELETE) {
                return handleSettingsDelete();
            }
            return respondMethodNotAllowed();
        }
        if (method == HttpMethod.PUT) {
            return handlePut();
        }
        if (method == HttpMethod.DELETE) {
            return handleDelete();
        }

        return respondMethodNotAllowed();
    }

    private Future<?> handleGet() throws JsonProcessingException {
        Config config = context.getConfig();
        boolean admin = authorizationService.isAdmin(context);
        // Per-entity GET is blob-only (slice U.1): only canonical-ID lookups resolve here.
        // File-sourced entries are inspected via /v1/admin/config/file/{type}[/{name}].
        // Slice U.4: secret fields drop on response via @JsonProperty(WRITE_ONLY) — there is no
        // ?reveal_secrets=true reveal flow and no security-admin tier.
        return switch (resourceType()) {
            case MODEL -> handleSingleGet(
                    config.getModels(), ResourceTypes.MODEL,
                    (key, model) -> projectItem(model, key));
            case INTERCEPTOR -> handleSingleGet(
                    config.getInterceptors(), ResourceTypes.INTERCEPTOR,
                    (key, interceptor) -> projectItem(interceptor, key));
            case ROLE -> handleSingleGet(
                    config.getRoles(), ResourceTypes.ROLE,
                    (key, role) -> projectItem(role, key));
            case PROJECT_KEY -> handleSingleGet(
                    config.getKeys(), ResourceTypes.PROJECT_KEY,
                    (key, value) -> projectItem(value, key));
            case ROUTE -> handleSingleGet(
                    config.getRoutes(), ResourceTypes.ROUTE,
                    (key, route) -> projectItem(route, key));
            case APP_TYPE_SCHEMA -> handleSchemaGet(config, admin);
            case GLOBAL_SETTINGS -> handleSettingsGet(config);
            default -> respondMethodNotAllowed();
        };
    }

    private <T> Future<?> handleSingleGet(Map<String, T> source,
                                          ResourceTypes resourceType,
                                          BiFunction<String, T, ObjectNode> projector) {
        Map<String, InvalidEntityRecord> invalid = mergedConfigStore.getInvalidEntities()
                .getOrDefault(resourceType, Map.of());
        boolean admin = authorizationService.isAdmin(context);

        if (path == null || path.isEmpty()) {
            // Per-entity URL with empty name is not a listing surface — listings live on
            // /v1/metadata/{type}/{bucket}/.
            context.respond(HttpStatus.NOT_FOUND);
            return Future.succeededFuture();
        }
        // Per-entity GET is blob-only (U.1): MergedConfigStore keys API entries by canonical ID
        // ("models/platform/gpt-4"); file-sourced entries are not addressable here. Operators
        // inspect file entries via /v1/admin/config/file/{type}[/{name}] — see FileConfigController.
        T item = source.get(canonicalId());
        if (item != null) {
            // RFC 7232 If-None-Match: emit 304 only when the entity has a blob-backed ETag and the
            // client-supplied tag matches.
            final T matchedItem = item;
            final String matched = canonicalId();
            return respondNotModifiedIfMatched(matched)
                    .onSuccess(notModified -> {
                        if (!notModified) {
                            context.respond(HttpStatus.OK, projector.apply(matched, matchedItem));
                        }
                    })
                    .onFailure(this::handleWriteError);
        }
        InvalidEntityRecord invalidRecord = invalid.get(canonicalId());
        if (invalidRecord != null) {
            context.respond(HttpStatus.OK, projectInvalidItem(invalidRecord, admin));
            return Future.succeededFuture();
        }
        context.respond(HttpStatus.NOT_FOUND);
        return Future.succeededFuture();
    }

    /**
     * Emits a {@code 304 Not Modified} if the matched entity's stored blob has an ETag
     * that matches the client's {@code If-None-Match} header. The blob-metadata fetch
     * runs on the blocking executor to keep the Vert.x event loop unblocked; the response is
     * written back on the event loop via the future-chain completion.
     */
    private Future<Boolean> respondNotModifiedIfMatched(String matchedKey) {
        ResourceDescriptor descriptor = singleEntityDescriptor();
        if (descriptor == null) {
            return Future.succeededFuture(false);
        }
        EtagHeader etag = ProxyUtil.etag(context.getRequest());
        return taskExecutor.<HttpException>submit(() -> {
            ResourceItemMetadata meta = resourceService.getResourceMetadata(descriptor);
            if (meta == null || meta.getEtag() == null) {
                return null;
            }
            try {
                etag.validate(meta.getEtag());
            } catch (HttpException e) {
                if (e.getStatus() == HttpStatus.NOT_MODIFIED) {
                    return e;
                }
                // Other status codes (e.g. If-Match failure) are not surfaced here — GET ignores
                // If-Match per RFC 7232; only If-None-Match drives 304/200 on GET.
            }
            return null;
        }).map(notModified -> {
            if (notModified != null) {
                context.respond(notModified);
                return true;
            }
            return false;
        });
    }

    /**
     * Returns a single-entity {@link ResourceDescriptor} for the current request, or {@code null}
     * when the entity type cannot be mapped or the {@code path} is empty/folder-like. Used by
     * the GET 304 path to fetch blob metadata.
     */
    private ResourceDescriptor singleEntityDescriptor() {
        if (path == null || path.isEmpty() || path.endsWith("/")) {
            return null;
        }
        return descriptorFor(resourceType());
    }

    /**
     * Builds a {@link ResourceDescriptor} for the given type using the controller's {@code path},
     * mapping each type to its fixed (bucket, location) pair. Returns {@code null} for types not
     * served by the per-entity surface (e.g. {@code GLOBAL_SETTINGS} uses its own handlers).
     */
    private ResourceDescriptor descriptorFor(ResourceTypes type) {
        return switch (type) {
            case MODEL -> ResourceDescriptorFactory.fromDecoded(ResourceTypes.MODEL,
                    ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION, path);
            case INTERCEPTOR -> ResourceDescriptorFactory.fromDecoded(ResourceTypes.INTERCEPTOR,
                    ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION, path);
            case ROLE -> ResourceDescriptorFactory.fromDecoded(ResourceTypes.ROLE,
                    ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION, path);
            case PROJECT_KEY -> ResourceDescriptorFactory.fromDecoded(ResourceTypes.PROJECT_KEY,
                    ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION, path);
            case ROUTE -> ResourceDescriptorFactory.fromDecoded(ResourceTypes.ROUTE,
                    ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION, path);
            case APP_TYPE_SCHEMA -> ResourceDescriptorFactory.fromDecoded(ResourceTypes.APP_TYPE_SCHEMA,
                    ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION, path);
            default -> null;
        };
    }

    private String canonicalId() {
        return entityType + "/" + bucket + "/" + path;
    }

    private Future<?> handleSchemaGet(Config config, boolean admin) throws JsonProcessingException {
        Map<String, String> schemas = config.getApplicationTypeSchemas();
        Map<String, InvalidEntityRecord> invalid = mergedConfigStore.getInvalidEntities()
                .getOrDefault(ResourceTypes.APP_TYPE_SCHEMA, Map.of());
        if (path == null || path.isEmpty()) {
            context.respond(HttpStatus.NOT_FOUND);
            return Future.succeededFuture();
        }
        // Per-entity GET is blob-only (U.1): canonical-ID lookup only; file-defined schemas
        // are inspected via /v1/admin/config/file/schemas/{name}.
        String schemaJson = schemas.get(canonicalId());
        if (schemaJson != null) {
            final String matched = canonicalId();
            final String json = schemaJson;
            return respondNotModifiedIfMatched(matched)
                    .onSuccess(notModified -> {
                        if (!notModified) {
                            try {
                                context.respond(HttpStatus.OK, projectSchemaItem(matched, json));
                            } catch (JsonProcessingException e) {
                                context.respond(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
                            }
                        }
                    })
                    .onFailure(this::handleWriteError);
        }
        InvalidEntityRecord invalidRecord = invalid.get(canonicalId());
        if (invalidRecord != null) {
            context.respond(HttpStatus.OK, projectInvalidItem(invalidRecord, admin));
            return Future.succeededFuture();
        }
        context.respond(HttpStatus.NOT_FOUND);
        return Future.succeededFuture();
    }

    private Future<?> handleSettingsGet(Config config) {
        if (path == null || path.isEmpty()) {
            // Per-entity URL with empty name is not a listing surface (see handleSingleGet).
            context.respond(HttpStatus.NOT_FOUND);
            return Future.succeededFuture();
        }
        if (!SETTINGS_SINGLETON_NAME.equals(path)) {
            context.respond(HttpStatus.NOT_FOUND);
            return Future.succeededFuture();
        }
        // Blob-only (U.1): the per-entity endpoint reflects the API blob; when no blob is present
        // it returns 404. File-defined or schema-default values are projected via
        // /v1/admin/config/file/settings/global.
        if (!mergedConfigStore.isSettingsFromApi()) {
            context.respond(HttpStatus.NOT_FOUND);
            return Future.succeededFuture();
        }
        ObjectNode body = ProxyUtil.MAPPER.createObjectNode();
        body.set("globalInterceptors", ProxyUtil.MAPPER.valueToTree(config.getGlobalInterceptors()));
        body.set("retriableErrorCodes", ProxyUtil.MAPPER.valueToTree(config.getRetriableErrorCodes()));
        body.put("name", SETTINGS_SINGLETON_NAME);
        body.put("status", "valid");
        context.respond(HttpStatus.OK, body);
        return Future.succeededFuture();
    }

    private Future<?> handleSettingsPut() {
        if (!SETTINGS_SINGLETON_NAME.equals(path)) {
            context.respond(HttpStatus.NOT_FOUND);
            return Future.succeededFuture();
        }
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.GLOBAL_SETTINGS, ResourceDescriptor.PLATFORM_BUCKET,
                ResourceDescriptor.PLATFORM_LOCATION, SETTINGS_SINGLETON_NAME);
        EtagHeader etag = ProxyUtil.etag(context.getRequest());

        context.getRequest().body().compose(body -> {
            JsonNode requestNode = parseJsonBody(body);
            if (!requestNode.isObject()) {
                throw new HttpException(HttpStatus.BAD_REQUEST, "Request body must be a JSON object");
            }
            // Deserialize through the typed GlobalSettings POJO so unknown fields are dropped and types
            // are validated; re-serialize so the blob is canonical (locked field set, no extras).
            GlobalSettings settings = treeToEntity(requestNode, GlobalSettings.class);
            String blobBody = serializeForBlob(settings);
            String author = context.getUserDisplayName();
            return taskExecutor.submit(() -> lockService.underBucketLocks(MergedConfigStore.ADMIN_BUCKET_LOCATIONS, () -> {
                // RFC 7232 conditional headers must be honored on the singleton too: read prior
                // metadata, validate If-None-Match/If-Match, then persist with ANY so the blob
                // layer doesn't re-validate against a stale snapshot.
                ResourceItemMetadata existing = resourceService.getResourceMetadata(descriptor);
                etag.validate(existing == null ? null : existing.getEtag());
                ResourceItemMetadata meta = resourceService.putResource(
                        descriptor, blobBody, EtagHeader.ANY, author, false);
                mergedConfigStore.applySettingsWrite(settings);
                return meta;
            }));
        }).onSuccess(meta -> context.putHeader(HttpHeaders.ETAG, meta.getEtag())
                .respond(HttpStatus.OK, createNameEnvelope(SETTINGS_SINGLETON_NAME)))
                .onFailure(this::handleWriteError);

        return Future.succeededFuture();
    }

    private Future<?> handleSettingsDelete() {
        if (!SETTINGS_SINGLETON_NAME.equals(path)) {
            context.respond(HttpStatus.NOT_FOUND);
            return Future.succeededFuture();
        }
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.GLOBAL_SETTINGS, ResourceDescriptor.PLATFORM_BUCKET,
                ResourceDescriptor.PLATFORM_LOCATION, SETTINGS_SINGLETON_NAME);
        EtagHeader etag = ProxyUtil.etag(context.getRequest());

        taskExecutor.submit(() -> lockService.underBucketLocks(MergedConfigStore.ADMIN_BUCKET_LOCATIONS, () -> {
            // Idempotent on bare DELETE — deleteResource returns false when the blob is absent and
            // both outcomes collapse to 204 since the post-state (no API blob) is identical.
            // If-Match passes through to deleteResource which throws 412 on mismatch.
            resourceService.deleteResource(descriptor, etag, false);
            mergedConfigStore.applySettingsDelete();
            return true;
        })).onSuccess(v -> context.respond(HttpStatus.NO_CONTENT)).onFailure(this::handleWriteError);

        return Future.succeededFuture();
    }

    /**
     * PUT-upsert: creates the entity when no prior blob exists, updates it otherwise.
     * Honors RFC 7232 {@code If-None-Match: *} (412 if the entity already exists) and
     * {@code If-Match: <etag>} (412 on mismatch) via {@link EtagHeader#validate(String)}. Both
     * arms call {@link MergedConfigStore#applyEntityWrite} uniformly (partial-update fast path).
     */
    private Future<?> handlePut() {
        WriteSpec spec = prepareWrite();
        if (spec == null) {
            return Future.succeededFuture();
        }
        ResourceDescriptor descriptor = spec.descriptor();
        String name = path;
        EtagHeader etag = ProxyUtil.etag(context.getRequest());
        String author = context.getUserDisplayName();

        context.getRequest().body().compose(body -> {
            JsonNode requestNode = parseJsonBody(body);
            return taskExecutor.submit(() -> lockService.underBucketLocks(MergedConfigStore.ADMIN_BUCKET_LOCATIONS, () -> {
                // The admin-write lock alone serializes all writes to admin-only types
                // cluster-wide (these types are never written by non-admin paths), so the
                // per-resource lock is redundant here. See 02-architecture.md §4.4.
                // Read existing blob first — both to validate conditional headers and to drive
                // preserve-on-omit when secrets are involved. One combined fetch (metadata +
                // body) saves a Redis/blob round-trip. Returns null when no blob exists
                // (file-sourced entries don't show up here; the union with file entries is
                // reapplied via applyEntityWrite below).
                Pair<ResourceItemMetadata, String> existingPair =
                        resourceService.getResourceWithMetadata(descriptor, EtagHeader.ANY);
                ResourceItemMetadata existing = existingPair == null ? null : existingPair.getLeft();
                String existingBody = existingPair == null ? null : existingPair.getRight();
                // Honor If-Match / If-None-Match: * before the write — yields RFC-compliant 412
                // PRECONDITION_FAILED. Bare PUT (no conditional header) passes through and the
                // write proceeds as an upsert.
                etag.validate(existing == null ? null : existing.getEtag());

                String blobBody;
                Key keyEntity = null;
                String keySecret = null;
                String oldSecret = null;
                Object entity = null;
                if (spec.entityClass() == null) {
                    blobBody = requestNode.toString();
                } else {
                    if (!requestNode.isObject()) {
                        throw new HttpException(HttpStatus.BAD_REQUEST,
                                "Request body must be a JSON object");
                    }
                    JsonNode source;
                    if (spec.hasEncryptedFields() && existingBody != null) {
                        // Update arm with secret fields — preserve omitted/sentinel-masked
                        // ciphertext from the prior blob (see SecretFieldProcessor).
                        JsonNode existingBlobNode;
                        try {
                            existingBlobNode = ProxyUtil.BLOB_MAPPER.readTree(existingBody);
                        } catch (JsonProcessingException e) {
                            // Don't echo getOriginalMessage() — the stored blob can carry
                            // ciphertext or other content we don't want to surface verbatim.
                            throw new HttpException(HttpStatus.INTERNAL_SERVER_ERROR,
                                    "Stored entity is malformed at " + locationOf(e));
                        }
                        if (spec.isKey()) {
                            // Recover the prior plaintext secret so a rotation can revoke the old
                            // auth bearer (FINDING #2). Deliberately non-fatal: a corrupt prior blob
                            // must NOT abort the rotation — the new secret is authoritative and any
                            // stale entry is cleaned at the next full rebuild.
                            try {
                                Key prior = ProxyUtil.BLOB_MAPPER.treeToValue(existingBlobNode, Key.class);
                                secretFieldProcessor.decryptFields(prior, descriptor);
                                oldSecret = prior.getKey();
                            } catch (Exception e) {
                                log.warn("Could not recover prior key secret for rotation at {}; "
                                        + "proceeding with new secret as authoritative", descriptor.getUrl());
                            }
                        }
                        source = secretFieldProcessor.mergePreservingOmittedSecrets(
                                existingBlobNode, requestNode, spec.entityClass());
                    } else {
                        // Create arm (no prior blob) or no encrypted fields — use the request
                        // body verbatim. SecretFieldProcessor encryptFields below will handle
                        // any plaintext secrets.
                        source = requestNode;
                    }
                    entity = treeToEntity(source, spec.entityClass());
                    if (entity instanceof Model m) {
                        checkCrossReferences(m);
                    }
                    if (spec.isKey()) {
                        keyEntity = (Key) entity;
                        validateKeyForApiWrite(keyEntity, "PUT");
                        // Capture before encryptFields mutates Key.key to ciphertext in place;
                        // ApiKeyStore is indexed by plaintext secret (see ApiKeyStore.getApiKeyData).
                        keySecret = keyEntity.getKey();
                    }
                    if (spec.hasEncryptedFields()) {
                        secretFieldProcessor.encryptFields(entity, descriptor);
                    }
                    blobBody = serializeForBlob(entity);
                }
                // Conditional headers were already validated above; persist with ANY so the
                // blob layer doesn't re-validate against a stale snapshot.
                ResourceItemMetadata meta = resourceService.putResource(
                        descriptor, blobBody, EtagHeader.ANY, author, false);
                if (keySecret != null) {
                    apiKeyStore.addOrUpdateKey(keySecret, apiKeyData(keyEntity));
                    if (oldSecret != null && !oldSecret.isBlank() && !oldSecret.equals(keySecret)) {
                        apiKeyStore.removeKey(oldSecret);
                    }
                }
                // Decrypt-in-place after blob put. PUT-upsert can produce a mixed
                // plaintext/ciphertext entity (preserve-on-omit on the update arm); decryptValue
                // is idempotent on plaintext and restores ciphertext fields to plaintext,
                // yielding a fully-decrypted entity for applyEntityWrite to put into the merged
                // Config (read-after-write parity).
                if (entity != null && spec.hasEncryptedFields()) {
                    secretFieldProcessor.decryptFields(entity, descriptor);
                }
                mergedConfigStore.applyEntityWrite(typeOf(descriptor),
                        MergedConfigStore.canonicalId(descriptor),
                        entity != null ? entity : requestNode);
                return meta;
            }));
        }).onSuccess(meta -> context.putHeader(HttpHeaders.ETAG, meta.getEtag())
                .respond(HttpStatus.OK, createNameEnvelope(name)))
                .onFailure(this::handleWriteError);

        return Future.succeededFuture();
    }

    private Future<?> handleDelete() {
        WriteSpec spec = prepareWrite();
        if (spec == null) {
            return Future.succeededFuture();
        }
        ResourceDescriptor descriptor = spec.descriptor();
        EtagHeader etag = ProxyUtil.etag(context.getRequest());

        taskExecutor.submit(() -> lockService.underBucketLocks(MergedConfigStore.ADMIN_BUCKET_LOCATIONS, () -> {
            // Pre-read + delete must run under the same lock so the secret extracted for
            // apiKeyStore.removeKey matches the secret in the blob being deleted (no race
            // with a concurrent PUT swapping the key). The admin-write lock alone provides
            // this — admin-only types are never written by non-admin paths, and all admin
            // writes serialize cluster-wide on this lock. See 02-architecture.md §4.4.
            String deletedSecret = null;
            if (spec.isKey()) {
                String existing = resourceService.getResource(descriptor, EtagHeader.ANY, false);
                if (existing != null) {
                    try {
                        JsonNode node = ProxyUtil.BLOB_MAPPER.readTree(existing);
                        Key key = ProxyUtil.BLOB_MAPPER.treeToValue(node, Key.class);
                        secretFieldProcessor.decryptFields(key, descriptor);
                        deletedSecret = key.getKey();
                    } catch (Exception e) {
                        // Corrupt blob or decrypt failure. Fall back to the in-memory snapshot
                        // so the DELETE ordering invariant (apiKeyStore.removeKey BEFORE the
                        // new merged Config becomes visible) is preserved — otherwise the live
                        // secret remains authenticatable until the next full rebuild.
                        log.warn("Could not extract key secret from blob, falling back to "
                                + "in-memory snapshot: {}", e.getMessage());
                        String canonicalId = "keys/" + descriptor.getBucketName() + "/" + descriptor.getName();
                        Config snapshot = mergedConfigStore.get();
                        if (snapshot != null) {
                            Key inMemory = snapshot.getKeys().get(canonicalId);
                            if (inMemory != null && StringUtils.isNotBlank(inMemory.getKey())) {
                                deletedSecret = inMemory.getKey();
                            }
                        }
                        if (deletedSecret == null) {
                            throw new HttpException(HttpStatus.INTERNAL_SERVER_ERROR,
                                    "Stored key entity is unreadable and no in-memory snapshot is available; "
                                            + "delete aborted to preserve auth-store ordering");
                        }
                    }
                }
            }
            boolean deleted = resourceService.deleteResource(descriptor, etag, false);
            if (!deleted) {
                throw new HttpException(HttpStatus.NOT_FOUND,
                        "Resource not found: " + descriptor.getUrl());
            }
            if (deletedSecret != null) {
                apiKeyStore.removeKey(deletedSecret);
            }
            mergedConfigStore.applyEntityDelete(typeOf(descriptor), MergedConfigStore.canonicalId(descriptor));
            return true;
        })).onSuccess(v -> context.respond(HttpStatus.NO_CONTENT)).onFailure(this::handleWriteError);

        return Future.succeededFuture();
    }

    private static ResourceTypes typeOf(ResourceDescriptor descriptor) {
        // prepareWrite() always builds descriptors with a ResourceTypes constant — see below;
        // ResourceDescriptor exposes the wider ResourceType interface so the cast is required here.
        return (ResourceTypes) descriptor.getType();
    }

    private static ApiKeyData apiKeyData(Key key) {
        ApiKeyData data = new ApiKeyData();
        data.setOriginalKey(key);
        return data;
    }

    private static void validateKeyForApiWrite(Key key, String method) {
        if (StringUtils.isBlank(key.getKey())) {
            throw new HttpException(HttpStatus.BAD_REQUEST,
                    "Key.key must be provided explicitly on " + method);
        }
        validateProjectKey(key);
    }

    /**
     * Validate the write target and return its spec. Returns {@code null} after writing the
     * appropriate 4xx response when the request can't proceed — callers short-circuit on null.
     */
    private WriteSpec prepareWrite() {
        if (path == null || path.isEmpty() || path.endsWith("/")) {
            context.respond(HttpStatus.BAD_REQUEST, "Resource name must not be empty or a folder");
            return null;
        }
        ResourceTypes type = resourceType();
        ResourceDescriptor descriptor = descriptorFor(type);
        if (descriptor == null) {
            respondMethodNotAllowed();
            return null;
        }
        return switch (type) {
            case MODEL -> new WriteSpec(descriptor, Model.class, true, false);
            case INTERCEPTOR -> new WriteSpec(descriptor, Interceptor.class, false, false);
            case ROLE -> new WriteSpec(descriptor, Role.class, false, false);
            case PROJECT_KEY -> new WriteSpec(descriptor, Key.class, true, true);
            case ROUTE -> new WriteSpec(descriptor, Route.class, true, false);
            case APP_TYPE_SCHEMA -> new WriteSpec(descriptor, null, false, false);
            default -> {
                respondMethodNotAllowed();
                yield null;
            }
        };
    }

    private static void validateProjectKey(Key key) {
        // Mirrors ApiKeyStore#validateProjectKey (private there); duplicated to translate
        // IllegalArgumentException into HttpException without widening visibility upstream.
        if (StringUtils.isBlank(key.getProject())) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Project key is undefined");
        }
        if (StringUtils.isBlank(key.getRole()) && (key.getRoles() == null || key.getRoles().isEmpty())) {
            throw new HttpException(HttpStatus.BAD_REQUEST,
                    "Invalid key: at least one role must be assigned to the key " + key.getProject());
        }
    }

    private static JsonNode parseJsonBody(Buffer body) {
        String text = body == null ? "" : body.toString(StandardCharsets.UTF_8);
        if (text.isBlank()) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Request body must not be empty");
        }
        try {
            return ProxyUtil.BLOB_MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            // getOriginalMessage() echoes the offending token verbatim, which can include
            // submitted credentials (Key.key, Upstream.key, etc.). Surface only the location.
            throw new HttpException(HttpStatus.BAD_REQUEST, "Invalid JSON at " + locationOf(e));
        }
    }

    static <T> T treeToEntity(JsonNode node, Class<T> cls) {
        try {
            return ProxyUtil.BLOB_MAPPER.treeToValue(node, cls);
        } catch (JsonProcessingException e) {
            // Same rationale as parseJsonBody — the mapping error can embed the offending
            // field value, including secrets in a partially-typed request body.
            throw new HttpException(HttpStatus.BAD_REQUEST,
                    "Failed to parse entity at " + locationOf(e));
        }
    }

    private void handleWriteError(Throwable error) {
        if (error instanceof HttpException exception) {
            context.respond(exception);
        } else {
            context.respond(HttpStatus.INTERNAL_SERVER_ERROR, error.getMessage());
        }
    }

    /**
     * Cross-reference check for Model writes. Strict mode aborts with HTTP 422 carrying a
     * {@code {"validationWarnings":[...]}} JSON body. Soft mode logs and proceeds — the next
     * merged-config rebuild's skip path records the entity in
     * {@link MergedConfigStore#getInvalidEntities()}.
     */
    private void checkCrossReferences(Model entity) {
        Config snapshot = mergedConfigStore.get();
        if (snapshot == null) {
            return;
        }
        List<ValidationWarning> warnings = new ArrayList<>();
        ConfigPostProcessor.validateCrossReferences(entity, snapshot, warnings);
        UpstreamExtraDataMerger.validateNoOverlap(entity);
        if (warnings.isEmpty()) {
            return;
        }
        if (softValidation) {
            log.warn("Soft-mode cross-ref warnings for model '{}': {}", path, warnings);
            return;
        }
        ObjectNode body = ProxyUtil.MAPPER.createObjectNode();
        ArrayNode arr = body.putArray("validationWarnings");
        for (ValidationWarning warning : warnings) {
            ObjectNode w = arr.addObject();
            w.put("field", warning.getField());
            w.put("message", warning.getMessage());
        }
        throw new HttpException(HttpStatus.UNPROCESSABLE_ENTITY, body.toString());
    }

    static String serializeForBlob(Object entity) {
        try {
            return ProxyUtil.BLOB_MAPPER.writeValueAsString(entity);
        } catch (JsonProcessingException e) {
            // writeValueAsString failures don't carry a useful JsonLocation and the message
            // can echo entity field values — surface only the entity class to keep the trail.
            throw new HttpException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to serialize entity of type " + entity.getClass().getSimpleName());
        }
    }

    private static String locationOf(JsonProcessingException e) {
        return e.getLocation() == null
                ? "unknown location"
                : "line " + e.getLocation().getLineNr() + ", column " + e.getLocation().getColumnNr();
    }

    private ObjectNode createNameEnvelope(String name) {
        ObjectNode body = ProxyUtil.MAPPER.createObjectNode();
        body.put("name", name);
        return body;
    }

    private ObjectNode projectItem(Object item, String name) {
        // Default MAPPER respects @JsonProperty(WRITE_ONLY) on @EncryptedField fields, so secrets
        // simply drop from the response. There is no plaintext-reveal path (slice U.4 retired
        // the ?reveal_secrets=true / security-admin reveal flow).
        ObjectNode node = ProxyUtil.MAPPER.valueToTree(item);
        node.put("name", name);
        node.put("status", "valid");
        return node;
    }

    private ObjectNode projectSchemaItem(String name, String json)
            throws JsonProcessingException {
        // applicationTypeSchemas stores raw JSON strings; parse for projection.
        JsonNode schema = ProxyUtil.MAPPER.readTree(json);
        ObjectNode node = ProxyUtil.MAPPER.createObjectNode();
        if (schema.isObject()) {
            node.setAll((ObjectNode) schema);
        } else {
            node.set("schema", schema);
        }
        node.put("name", name);
        node.put("status", "valid");
        return node;
    }

    private ObjectNode projectInvalidItem(InvalidEntityRecord record, boolean admin) {
        ObjectNode node = ProxyUtil.MAPPER.createObjectNode();
        Class<?> entityClass = entityClassFor(entityType);
        // Invalid blobs may not have been decrypted (decryption_error reason) so the raw payload may
        // still contain ENC[...] envelopes. Drop encrypted fields entirely so ciphertext never leaks.
        ObjectNode payload = entityClass == null
                ? (record.getPayload() instanceof ObjectNode raw ? raw.deepCopy() : null)
                : SecretFieldProcessor.stripEncryptedFields(record.getPayload(), entityClass);
        if (payload != null) {
            node.setAll(payload);
        }
        node.put("name", record.getSimpleName());
        node.put("status", "invalid");
        if (admin) {
            ArrayNode warnings = node.putArray("validationWarnings");
            for (ValidationWarning warning : record.getValidationWarnings()) {
                ObjectNode w = warnings.addObject();
                w.put("field", warning.getField());
                w.put("message", warning.getMessage());
            }
        }
        return node;
    }

    private static Class<?> entityClassFor(String entityType) {
        return switch (ResourceTypes.of(entityType)) {
            case MODEL -> Model.class;
            case INTERCEPTOR -> Interceptor.class;
            case ROLE -> Role.class;
            case PROJECT_KEY -> Key.class;
            case ROUTE -> Route.class;
            default -> null;
        };
    }

    private ResourceTypes resourceType() {
        return ResourceTypes.of(entityType);
    }

    private Future<?> respondMethodNotAllowed() {
        context.putHeader("Allow", ALLOW_HEADER);
        context.respond(HttpStatus.METHOD_NOT_ALLOWED, "Not implemented");
        return Future.succeededFuture();
    }

    private record WriteSpec(
            ResourceDescriptor descriptor,
            Class<?> entityClass,        // null for schemas (raw JSON-string body)
            boolean hasEncryptedFields,
            boolean isKey
    ) {}

}