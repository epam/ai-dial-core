package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.ExternalService;
import com.epam.aidial.core.config.Features;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.openapi.annotations.ApiHeader;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiOperations;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.OpenApiDescriptions;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.Conversation;
import com.epam.aidial.core.server.data.Prompt;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.service.AdminManagedFieldsWriteMode;
import com.epam.aidial.core.server.service.ApplicationSchemaService;
import com.epam.aidial.core.server.service.ApplicationService;
import com.epam.aidial.core.server.service.DeploymentService;
import com.epam.aidial.core.server.service.ExternalServiceStatusEnricher;
import com.epam.aidial.core.server.service.ExternalServicesWriteMode;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.service.ToolSetService;
import com.epam.aidial.core.server.util.ApplicationTypeSchemaProcessingException;
import com.epam.aidial.core.server.util.CredentialsLocatorFactory;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.validation.ApplicationTypeResourceException;
import com.epam.aidial.core.server.validation.ApplicationTypeSchemaValidationException;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.data.MetadataBase;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.Future;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import jakarta.validation.ValidationException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;

import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.epam.aidial.core.storage.http.HttpStatus.BAD_GATEWAY;
import static com.epam.aidial.core.storage.http.HttpStatus.BAD_REQUEST;
import static com.epam.aidial.core.storage.http.HttpStatus.FORBIDDEN;
import static com.epam.aidial.core.storage.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static com.epam.aidial.core.storage.resource.ResourceTypes.CONVERSATION;
import static com.epam.aidial.core.storage.resource.ResourceTypes.PROMPT;

@Slf4j
@SuppressWarnings("checkstyle:Indentation")
public class ResourceController extends AccessControlBaseController {

    private final AsyncTaskExecutor taskExecutor;
    private final ResourceService resourceService;
    private final ApplicationService applicationService;
    private final ApplicationSchemaService applicationSchemaService;
    private final boolean metadata;
    private final AccessService accessService;

    private final ToolSetService toolSetService;
    private final DeploymentService deploymentService;

    public ResourceController(Proxy proxy, ProxyContext context, boolean metadata) {
        // PUT and DELETE require write access, GET - read
        super(proxy, context, !HttpMethod.GET.equals(context.getRequest().method()));
        this.taskExecutor = proxy.getTaskExecutor();
        this.toolSetService = proxy.getToolSetService();
        this.applicationService = proxy.getApplicationService();
        this.accessService = proxy.getAccessService();
        this.resourceService = proxy.getResourceService();
        this.applicationSchemaService = proxy.getApplicationSchemaService();
        this.deploymentService = proxy.getDeploymentService();
        this.metadata = metadata;
    }

    @Override
    @ApiOperations({
            // Applications
            @ApiOperation(
                    method = "PUT",
                    path = "/v1/applications/{bucket}/{application_path}",
                    operationId = "saveCustomApplication",
                    requestBody = @ApiSchema(implementation = Application.class),
                    tags = {"Applications"},
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "application_path", in = ParameterIn.PATH, required = true,
                                    description = OpenApiDescriptions.APPLICATION_PATH_SAVE),
                            @ApiParameter(name = "If-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_MATCH_UPLOAD_APPLICATION),
                            @ApiParameter(name = "If-None-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_NONE_MATCH_UPLOAD_APPLICATION)
                    },
                    responses = {
                            @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = ResourceItemMetadata.class),
                                    headers = {
                                            @ApiHeader(name = "ETag", description = "Entity tag for the saved application", required = true)
                                    }),
                            @ApiResponse(code = 400),
                            @ApiResponse(code = 403),
                            @ApiResponse(code = 412),
                            @ApiResponse(code = 413),
                            @ApiResponse(code = 500),
                            @ApiResponse(code = 502),
                            @ApiResponse(code = 504)
                    }
            ),
            @ApiOperation(
                    method = "GET",
                    path = "/v1/applications/{bucket}/{application_path}",
                    operationId = "getCustomApplication",
                    tags = {"Applications"},
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "application_path", in = ParameterIn.PATH, required = true,
                                    description = OpenApiDescriptions.APPLICATION_PATH)
                    },
                responses = {
                            @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = Application.class),
                                    headers = {
                                            @ApiHeader(name = "ETag", description = "Entity tag for the application", required = true)
                                    }),
                            @ApiResponse(code = 400),
                            @ApiResponse(code = 403),
                            @ApiResponse(code = 404),
                            @ApiResponse(code = 500),
                            @ApiResponse(code = 502),
                            @ApiResponse(code = 504)
                    }
            ),
            @ApiOperation(
                    method = "DELETE",
                    path = "/v1/applications/{bucket}/{application_path}",
                    operationId = "deleteCustomApplication",
                    tags = {"Applications"},
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "application_path", in = ParameterIn.PATH, required = true,
                                    description = OpenApiDescriptions.APPLICATION_PATH),
                            @ApiParameter(name = "If-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_MATCH_DELETE_APPLICATION)
                    },
                    responses = {
                            @ApiResponse(code = 200, description = "Success"),
                            @ApiResponse(code = 400),
                            @ApiResponse(code = 403),
                            @ApiResponse(code = 404),
                            @ApiResponse(code = 412),
                            @ApiResponse(code = 500)
                    }
            ),
            @ApiOperation(
                    method = "GET",
                    path = "/v1/metadata/applications/{bucket}/{path}",
                    operationId = "getApplicationMetadata",
                    tags = {"Applications"},
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "path", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.METADATA_PATH_APPLICATIONS),
                            @ApiParameter(name = "token", in = ParameterIn.QUERY, description = OpenApiDescriptions.METADATA_TOKEN),
                            @ApiParameter(name = "limit", in = ParameterIn.QUERY, description = OpenApiDescriptions.METADATA_LIMIT, schema = Integer.class),
                            @ApiParameter(name = "recursive", in = ParameterIn.QUERY, description = OpenApiDescriptions.METADATA_RECURSIVE, schema = Boolean.class),
                            @ApiParameter(name = "permissions", in = ParameterIn.QUERY, description = OpenApiDescriptions.METADATA_PERMISSIONS_APPLICATIONS, schema = Boolean.class)
                    },
                    responses = {
                            @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = MetadataBase.class)),
                            @ApiResponse(code = 400),
                            @ApiResponse(code = 403),
                            @ApiResponse(code = 404),
                            @ApiResponse(code = 500)
                    }
            ),
            // Conversations
            @ApiOperation(
                    method = "PUT",
                    path = "/v1/conversations/{bucket}/{conversation_path}",
                    operationId = "saveConversation",
                    requestBody = @ApiSchema(implementation = Conversation.class),
                    tags = {"Conversations"},
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "conversation_path", in = ParameterIn.PATH, required = true,
                                    description = OpenApiDescriptions.CONVERSATION_PATH),
                            @ApiParameter(name = "If-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_MATCH_UPLOAD_CONVERSATION),
                            @ApiParameter(name = "If-None-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_NONE_MATCH_UPLOAD_CONVERSATION)
                    },
                    responses = {
                            @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = ResourceItemMetadata.class),
                                    headers = {
                                            @ApiHeader(name = "ETag", description = "Entity tag for the saved conversation", required = true)
                                    }),
                            @ApiResponse(code = 400),
                            @ApiResponse(code = 403),
                            @ApiResponse(code = 412),
                            @ApiResponse(code = 413),
                            @ApiResponse(code = 500)
                    }
            ),
            @ApiOperation(
                    method = "GET",
                    path = "/v1/conversations/{bucket}/{conversation_path}",
                    operationId = "getConversation",
                    tags = {"Conversations"},
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "conversation_path", in = ParameterIn.PATH, required = true,
                                    description = OpenApiDescriptions.CONVERSATION_PATH)
                    },
                    responses = {
                            @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = Conversation.class),
                                    headers = {
                                            @ApiHeader(name = "ETag", description = "Entity tag for the conversation", required = true)
                                    }),
                            @ApiResponse(code = 400),
                            @ApiResponse(code = 403),
                            @ApiResponse(code = 404),
                            @ApiResponse(code = 500),
                            @ApiResponse(code = 502),
                            @ApiResponse(code = 504)
                    }
            ),
            @ApiOperation(
                    method = "DELETE",
                    path = "/v1/conversations/{bucket}/{conversation_path}",
                    operationId = "deleteConversation",
                    tags = {"Conversations"},
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "conversation_path", in = ParameterIn.PATH, required = true,
                                    description = OpenApiDescriptions.CONVERSATION_PATH),
                            @ApiParameter(name = "If-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_MATCH_DELETE_CONVERSATION)
                    },
                    responses = {
                            @ApiResponse(code = 200, description = "Success"),
                            @ApiResponse(code = 400),
                            @ApiResponse(code = 403),
                            @ApiResponse(code = 404),
                            @ApiResponse(code = 412),
                            @ApiResponse(code = 500)
                    }
            ),
            @ApiOperation(
                    method = "GET",
                    path = "/v1/metadata/conversations/{bucket}/{path}",
                    operationId = "getConversationMetadata",
                    tags = {"Conversations"},
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "path", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.METADATA_PATH_CONVERSATIONS),
                            @ApiParameter(name = "token", in = ParameterIn.QUERY, description = OpenApiDescriptions.METADATA_TOKEN),
                            @ApiParameter(name = "limit", in = ParameterIn.QUERY, description = OpenApiDescriptions.METADATA_LIMIT, schema = Integer.class),
                            @ApiParameter(name = "recursive", in = ParameterIn.QUERY, description = OpenApiDescriptions.METADATA_RECURSIVE, schema = Boolean.class),
                            @ApiParameter(name = "permissions", in = ParameterIn.QUERY, description = OpenApiDescriptions.METADATA_PERMISSIONS_CONVERSATIONS,
                                schema = Boolean.class)
                    },
                    responses = {
                            @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = MetadataBase.class)),
                            @ApiResponse(code = 400),
                            @ApiResponse(code = 403),
                            @ApiResponse(code = 404),
                            @ApiResponse(code = 500)
                    }
            ),
            // Prompts
            @ApiOperation(
                    method = "PUT",
                    path = "/v1/prompts/{bucket}/{prompt_path}",
                    operationId = "savePrompt",
                    requestBody = @ApiSchema(implementation = Prompt.class),
                    tags = {"Prompts"},
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "prompt_path", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.PROMPT_PATH),
                            @ApiParameter(name = "If-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_MATCH_UPLOAD_PROMPT),
                            @ApiParameter(name = "If-None-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_NONE_MATCH_UPLOAD_PROMPT)
                    },
                    responses = {
                            @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = ResourceItemMetadata.class),
                                    headers = {
                                            @ApiHeader(name = "ETag", description = "Entity tag for the saved prompt", required = true)
                                    }),
                            @ApiResponse(code = 400),
                            @ApiResponse(code = 403),
                            @ApiResponse(code = 412),
                            @ApiResponse(code = 413),
                            @ApiResponse(code = 500)
                    }
            ),
            @ApiOperation(
                    method = "GET",
                    path = "/v1/prompts/{bucket}/{prompt_path}",
                    operationId = "getPrompt",
                    tags = {"Prompts"},
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "prompt_path", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.PROMPT_PATH)
                    },
                    responses = {
                            @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = Prompt.class),
                                    headers = {
                                            @ApiHeader(name = "ETag", description = "Entity tag for the prompt", required = true)
                                    }),
                            @ApiResponse(code = 400),
                            @ApiResponse(code = 403),
                            @ApiResponse(code = 404),
                            @ApiResponse(code = 500),
                            @ApiResponse(code = 502),
                            @ApiResponse(code = 504)
                    }
            ),
            @ApiOperation(
                    method = "DELETE",
                    path = "/v1/prompts/{bucket}/{prompt_path}",
                    operationId = "deletePrompt",
                    tags = {"Prompts"},
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "prompt_path", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.PROMPT_PATH),
                            @ApiParameter(name = "If-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_MATCH_DELETE_PROMPT)
                    },
                    responses = {
                            @ApiResponse(code = 200, description = "Success"),
                            @ApiResponse(code = 400),
                            @ApiResponse(code = 403),
                            @ApiResponse(code = 404),
                            @ApiResponse(code = 412),
                            @ApiResponse(code = 500)
                    }
            ),
            @ApiOperation(
                    method = "GET",
                    path = "/v1/metadata/prompts/{bucket}/{path}",
                    operationId = "getPromptMetadata",
                    tags = {"Prompts"},
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "path", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.METADATA_PATH_PROMPTS),
                            @ApiParameter(name = "token", in = ParameterIn.QUERY, description = OpenApiDescriptions.METADATA_TOKEN),
                            @ApiParameter(name = "limit", in = ParameterIn.QUERY, description = OpenApiDescriptions.METADATA_LIMIT, schema = Integer.class),
                            @ApiParameter(name = "recursive", in = ParameterIn.QUERY, description = OpenApiDescriptions.METADATA_RECURSIVE, schema = Boolean.class),
                            @ApiParameter(name = "permissions", in = ParameterIn.QUERY, description = OpenApiDescriptions.METADATA_PERMISSIONS_PROMPTS, schema = Boolean.class)
                    },
                    responses = {
                            @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = MetadataBase.class)),
                            @ApiResponse(code = 400),
                            @ApiResponse(code = 403),
                            @ApiResponse(code = 404),
                            @ApiResponse(code = 500)
                    }
            ),
            // Toolsets
            @ApiOperation(
                    method = "PUT",
                    path = "/v1/toolsets/{bucket}/{toolset_path}",
                    operationId = "saveToolSet",
                    requestBody = @ApiSchema(implementation = ToolSet.class),
                    tags = {"Toolsets"},
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "toolset_path", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.TOOLSET_PATH),
                            @ApiParameter(name = "If-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_MATCH_UPLOAD_TOOLSET),
                            @ApiParameter(name = "If-None-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_NONE_MATCH_UPLOAD_TOOLSET)
                    },
                    responses = {
                            @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = ResourceItemMetadata.class),
                                    headers = {
                                            @ApiHeader(name = "ETag", description = "Entity tag for the saved toolset", required = true)
                                    }),
                            @ApiResponse(code = 400),
                            @ApiResponse(code = 403),
                            @ApiResponse(code = 412),
                            @ApiResponse(code = 413),
                            @ApiResponse(code = 500)
                    }
            ),
            @ApiOperation(
                    method = "GET",
                    path = "/v1/toolsets/{bucket}/{toolset_path}",
                    operationId = "getCustomToolSet",
                    tags = {"Toolsets"},
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "toolset_path", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.TOOLSET_PATH)
                    },
                    responses = {
                            @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = ToolSet.class),
                                    headers = {
                                            @ApiHeader(name = "ETag", description = "Entity tag for the toolset", required = true)
                                    }),
                            @ApiResponse(code = 400),
                            @ApiResponse(code = 403),
                            @ApiResponse(code = 404),
                            @ApiResponse(code = 500),
                            @ApiResponse(code = 502),
                            @ApiResponse(code = 504)
                    }
            ),
            @ApiOperation(
                    method = "DELETE",
                    path = "/v1/toolsets/{bucket}/{toolset_path}",
                    operationId = "deleteToolSet",
                    tags = {"Toolsets"},
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "toolset_path", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.TOOLSET_PATH),
                            @ApiParameter(name = "If-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_MATCH_DELETE_TOOLSET)
                    },
                    responses = {
                            @ApiResponse(code = 200, description = "Success"),
                            @ApiResponse(code = 400),
                            @ApiResponse(code = 403),
                            @ApiResponse(code = 404),
                            @ApiResponse(code = 412),
                            @ApiResponse(code = 500)
                    }
            ),
            @ApiOperation(
                    method = "GET",
                    path = "/v1/metadata/toolsets/{bucket}/{path}",
                    operationId = "getToolSetMetadata",
                    tags = {"Toolsets"},
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "path", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.METADATA_PATH_TOOLSETS),
                            @ApiParameter(name = "token", in = ParameterIn.QUERY, description = OpenApiDescriptions.METADATA_TOKEN),
                            @ApiParameter(name = "limit", in = ParameterIn.QUERY, description = OpenApiDescriptions.METADATA_LIMIT, schema = Integer.class),
                            @ApiParameter(name = "recursive", in = ParameterIn.QUERY, description = OpenApiDescriptions.METADATA_RECURSIVE, schema = Boolean.class),
                            @ApiParameter(name = "permissions", in = ParameterIn.QUERY, description = OpenApiDescriptions.METADATA_PERMISSIONS_TOOLSETS, schema = Boolean.class)
                    },
                    responses = {
                            @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = MetadataBase.class)),
                            @ApiResponse(code = 400),
                            @ApiResponse(code = 403),
                            @ApiResponse(code = 404),
                            @ApiResponse(code = 500)
                    }
            ),
            // Files (delete only - upload/download handled by UploadFileController/DownloadFileController)
            @ApiOperation(
                    method = "DELETE",
                    path = "/v1/files/{bucket}/{file_path}",
                    operationId = "deleteFile",
                    tags = {"Files"},
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "file_path", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.FILE_PATH),
                            @ApiParameter(name = "If-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_MATCH_DELETE_FILE)
                    },
                    responses = {
                            @ApiResponse(code = 200, description = "Success"),
                            @ApiResponse(code = 400),
                            @ApiResponse(code = 403),
                            @ApiResponse(code = 404),
                            @ApiResponse(code = 412),
                            @ApiResponse(code = 500)
                    }
            )
    })
    protected Future<?> handle(ResourceDescriptor descriptor, boolean hasWriteAccess) {
        if (context.getRequest().method() == HttpMethod.GET) {
            return metadata ? getMetadata(descriptor) : getResource(descriptor, hasWriteAccess);
        }

        if (context.getRequest().method() == HttpMethod.PUT) {
            return putResource(descriptor);
        }

        if (context.getRequest().method() == HttpMethod.DELETE) {
            return deleteResource(descriptor);
        }
        log.warn("Unsupported HTTP method for accessing resource {}", descriptor.getUrl());
        return context.respond(HttpStatus.BAD_REQUEST, "Unsupported HTTP method");
    }

    private String getContentType() {
        String acceptType = context.getRequest().getHeader(HttpHeaders.ACCEPT);
        return acceptType != null && metadata && acceptType.contains(MetadataBase.MIME_TYPE)
                ? MetadataBase.MIME_TYPE
                : "application/json";
    }

    private Future<?> getMetadata(ResourceDescriptor descriptor) {
        ProxyUtil.MetadataQuery query;
        try {
            query = ProxyUtil.metadataQuery(context.getRequest());
        } catch (IllegalArgumentException error) {
            return context.respond(BAD_REQUEST, "Bad query parameters. Limit must be in [0, 1000] range. Recursive must be true/false");
        }

        taskExecutor.submit(() -> {
            MetadataBase result = resourceService.getMetadata(descriptor, query.token(), query.limit(), query.recursive());
            if (result == null) {
                return null;
            }
            accessService.filterForbidden(context, descriptor, result);
            if (context.getBooleanRequestQueryParam("permissions")) {
                accessService.populatePermissions(context, List.of(result));
            }
            return result;
        }).onSuccess(result -> {
            if (result == null) {
                context.respond(HttpStatus.NOT_FOUND, "Not found: " + descriptor.getUrl());
            } else {
                context.respond(HttpStatus.OK, getContentType(), result);
            }
        }).onFailure(error -> {
            context.respond(HttpStatus.INTERNAL_SERVER_ERROR);
            log.warn("Can't list resource: {}", descriptor.getUrl(), error);
        });

        return Future.succeededFuture();
    }

    private Future<?> getResource(ResourceDescriptor descriptor, boolean hasWriteAccess) {
        if (descriptor.isFolder()) {
            return context.respond(BAD_REQUEST, "Folder not allowed: " + descriptor.getUrl());
        }

        EtagHeader etagHeader = ProxyUtil.etag(context.getRequest());
        Future<Pair<ResourceItemMetadata, String>> responseFuture;
        if (descriptor.getType().equals(ResourceTypes.APPLICATION)) {
            responseFuture = getApplicationData(descriptor, hasWriteAccess, etagHeader);
        } else if (descriptor.getType().equals(ResourceTypes.TOOL_SET)) {
            responseFuture = getToolsetData(descriptor, hasWriteAccess, etagHeader);
        } else {
            responseFuture = getResourceData(descriptor, etagHeader);
        }

        responseFuture.onSuccess(pair -> context.putHeader(HttpHeaders.ETAG, pair.getKey().getEtag())
                .exposeHeaders()
                .respond(HttpStatus.OK, pair.getValue()))
                .onFailure(error -> handleError(descriptor, error));

        return Future.succeededFuture();
    }

    private Future<Pair<ResourceItemMetadata, String>> getApplicationData(ResourceDescriptor descriptor, boolean hasWriteAccess, EtagHeader etagHeader) {
        return taskExecutor.submit(() -> {
            Pair<ResourceItemMetadata, Application> result = applicationService.getApplication(descriptor, etagHeader);
            ResourceItemMetadata meta = result.getKey();

            Application application = result.getValue();
            overlayUserAuthoredServices(descriptor, application);
            enrichExternalServiceStatuses(descriptor, application);
            clearExternalServiceSecrets(application);

            if (!accessService.hasAdminAccess(context)) {
                application.setAppIdentity(null);
            }
            String body = hasWriteAccess
                    ? ProxyUtil.convertToString(application)
                    : ProxyUtil.convertToString(clearApplicationProperties(application));

            return Pair.of(meta, body);

        });
    }

    // Inline (admin-authored) definitions take precedence — see overlay(). Secrets are stripped downstream by
    // clearExternalServiceSecrets, so the decrypted user-authored secret never leaks (identity shaper here).
    private void overlayUserAuthoredServices(ResourceDescriptor descriptor, Application application) {
        if (!application.isAllowUserExternalServices() || context.getUserId() == null) {
            return;
        }
        String appPart = descriptor.getDecodedUrl().substring(CredentialsLocatorFactory.APPLICATIONS_PREFIX.length());
        Map<String, ExternalService> merged = proxy.getUserExternalServiceService()
                .overlay(application.getExternalServices(), context.getUserId(), appPart, Function.identity());
        application.setExternalServices(merged);
    }

    private void enrichExternalServiceStatuses(ResourceDescriptor descriptor, Application application) {
        Map<String, ExternalService> services = application.getExternalServices();
        if (services == null || services.isEmpty()) {
            return;
        }
        ExternalServiceStatusEnricher enricher = new ExternalServiceStatusEnricher(
                context, proxy.getResourceAuthSettingsService());
        for (Map.Entry<String, ExternalService> entry : services.entrySet()) {
            ResourceAuthSettings authSettings = entry.getValue() == null ? null : entry.getValue().getAuthSettings();
            if (authSettings == null) {
                continue;
            }
            try {
                String scopeId = descriptor.getUrl() + CredentialsLocatorFactory.EXTERNAL_SERVICES_SEPARATOR + entry.getKey();
                CredentialsLocator locator = CredentialsLocatorFactory.fromExternalServiceScope(scopeId, context);
                enricher.enrich(locator, authSettings);
            } catch (RuntimeException e) {
                log.warn("Failed to compute external-service status for '{}' on '{}'", entry.getKey(), descriptor.getUrl(), e);
            }
        }
    }

    private static void clearExternalServiceSecrets(Application application) {
        Map<String, ExternalService> services = application.getExternalServices();
        if (services == null) {
            return;
        }
        for (ExternalService service : services.values()) {
            if (service != null && service.getAuthSettings() != null) {
                service.setAuthSettings(service.getAuthSettings().withoutSecrets());
            }
        }
    }

    private Application clearApplicationProperties(Application application) {
        application.setEndpoint(null);
        application.setAppIdentity(null);
        Features features = application.getFeatures();
        if (features != null) {
            features.setConfigurationEndpoint(null);
            features.setRateEndpoint(null);
            features.setTokenizeEndpoint(null);
            features.setTruncatePromptEndpoint(null);
        }
        Application.Mcp mcp = application.getMcp();
        if (mcp != null) {
            mcp.setEndpoint(null);
        }
        try {
            return proxy.getApplicationSchemaService().filterCustomClientProperties(application);
        } catch (ApplicationTypeSchemaProcessingException | ApplicationTypeResourceException | ApplicationTypeSchemaValidationException ex) {
            log.warn("Failed to modify application to fulfill schema's restrictions %s".formatted(application.getName()), ex);
            application.setApplicationProperties(null);
            application.setInvalid(true);
            return application;
        }
    }

    private Future<Pair<ResourceItemMetadata, String>> getResourceData(ResourceDescriptor descriptor, EtagHeader etag) {
        return taskExecutor.submit(() -> {
            Pair<ResourceItemMetadata, String> result = resourceService.getResourceWithMetadata(descriptor, etag);

            if (result == null) {
                throw new ResourceNotFoundException();
            }

            return result;
        });
    }

    private Future<Pair<ResourceItemMetadata, String>> getToolsetData(ResourceDescriptor descriptor, boolean hasWriteAccess, EtagHeader etagHeader) {
        return taskExecutor.submit(() -> {
            Pair<ResourceItemMetadata, ToolSet> result = toolSetService.getToolSet(descriptor, etagHeader);
            ResourceItemMetadata meta = result.getKey();
            ToolSet toolSet = result.getValue();
            toolSetService.setResourceAuthStatuses(context, toolSet, descriptor.getUrl());
            toolSet.clearAuthSettings();
            if (!hasWriteAccess) {
                toolSet.setEndpoint(null);
            }
            String body = ProxyUtil.convertToString(toolSet);
            return Pair.of(meta, body);
        });
    }

    private void validateCustomApplication(Application application) {
        try {
            checkCreateCodeApp(application);
            validateSchemaBasedApplication(application);
            if (!application.getInterceptors().isEmpty()) {
                if (!accessService.hasAdminAccess(context)) {
                    throw new HttpException(FORBIDDEN, "Only admins are allowed to set interceptors");
                }
                Config config = context.getConfig();
                for (String interceptor : application.getInterceptors()) {
                    if (!config.getInterceptors().containsKey(interceptor)) {
                        throw new HttpException(BAD_REQUEST, "Unknown interceptor: " + interceptor);
                    }
                }
            }
            for (String dependency : application.getDependencies()) {
                try {
                    deploymentService.findDeployment(context, dependency);
                } catch (ResourceNotFoundException | IllegalArgumentException e) {
                    throw new HttpException(BAD_REQUEST, "Unknown dependency: " + dependency);
                } catch (PermissionDeniedException e) {
                    throw new HttpException(FORBIDDEN, "Forbidden dependency: " + dependency);
                }
            }
        } catch (IllegalArgumentException | ValidationException e) {
            throw new HttpException(BAD_REQUEST, String.format("Custom application validation failed %s", e.getMessage()), e);
        } catch (ApplicationTypeSchemaValidationException e) {
            throw new HttpException(BAD_REQUEST, String.format("Custom application validation failed %s", e.validationMessages), e);
        } catch (ApplicationTypeResourceException e) {
            throw new HttpException(FORBIDDEN, "Failed to access application resource " + e.getResourceUri(), e);
        } catch (ApplicationTypeSchemaProcessingException e) {
            throw new HttpException(INTERNAL_SERVER_ERROR, "Custom application processing exception", e);
        }
    }

    private void validateSchemaBasedApplication(Application application) {
        if (!application.hasApplicationTypeSchemaId()) {
            return;
        }
        // force reload application schema
        applicationSchemaService.getSchema(application.getApplicationTypeSchemaId(), true);
        if (application.getApplicationProperties() != null) {
            List<ResourceDescriptor> files = applicationSchemaService.getFiles(application);
            files.stream().filter(resource -> !(accessService.hasReadAccess(resource, context)))
                    .findAny().ifPresent(file -> {
                        throw new HttpException(FORBIDDEN, "No read access to file: " + file.getUrl());
                    });
        }
        Application.Mcp mcp = applicationSchemaService.getMcp(application);
        String mcpEndpoint = mcp == null ? null : mcp.getEndpoint();
        String chatCompletionEndpoint = applicationSchemaService.getChatCompletionEndpoint(application);
        if (mcpEndpoint == null && chatCompletionEndpoint == null) {
            throw new IllegalArgumentException("At least MCP or chat completion endpoint must be provided");
        }
    }

    private void checkCreateCodeApp(Application application) {
        if (application != null && application.getFunction() != null && !accessService.canCreateCodeApps(context)) {
            throw new PermissionDeniedException("User doesn't have sufficient permissions to create code app");
        }
    }

    private Future<?> putResource(ResourceDescriptor descriptor) {
        if (descriptor.isFolder()) {
            return context.respond(BAD_REQUEST, "Folder not allowed: " + descriptor.getUrl());
        }

        if (!ResourceDescriptorFactory.isValidResourcePath(descriptor)) {
            return context.respond(BAD_REQUEST, "Resource name and/or parent folders must not end with .(dot)");
        }

        int contentLength = ProxyUtil.contentLength(context.getRequest(), 0);
        int contentLimit = resourceService.getMaxSize();

        if (contentLength > contentLimit) {
            String message = "Resource size: %s exceeds max limit: %s".formatted(contentLength, contentLimit);
            return context.respond(HttpStatus.REQUEST_ENTITY_TOO_LARGE, message);
        }

        Future<Pair<EtagHeader, String>> requestFuture = context.getRequest().body().map(bytes -> {
            if (bytes.length() > contentLimit) {
                String message = "Resource size: %s exceeds max limit: %s".formatted(bytes.length(), contentLimit);
                throw new HttpException(HttpStatus.REQUEST_ENTITY_TOO_LARGE, message);
            }

            EtagHeader etag = ProxyUtil.etag(context.getRequest());
            String body = bytes.toString(StandardCharsets.UTF_8);

            return Pair.of(etag, body);
        });

        String author = context.getUserDisplayName();
        // forwardAuthToken is a security-sensitive field — by default the service strips it on every write.
        // Admin writes to public/ are the only path that may set it; user-bucket writes (and admin writes
        // to user buckets, blocked elsewhere) continue to be stripped. See docs U.3 / 04 §1.
        boolean adminPublicWrite = descriptor.isPublic() && accessService.hasAdminAccess(context);
        Future<ResourceItemMetadata> responseFuture;
        if (descriptor.getType() == ResourceTypes.APPLICATION) {
            responseFuture = requestFuture.compose(pair -> {
                EtagHeader etag = pair.getKey();
                Application application = ProxyUtil.convertToObject(pair.getValue(), Application.class);
                if (application == null) {
                    throw new HttpException(BAD_REQUEST, "Application can't be empty");
                }
                JsonNode bodyJson = ProxyUtil.parseTree(pair.getValue());
                ExternalServicesWriteMode externalServicesWriteMode =
                        ProxyUtil.hasTopLevelField(bodyJson, "external_services", "externalServices")
                                ? ExternalServicesWriteMode.OVERRIDE
                                : ExternalServicesWriteMode.PRESERVE_IF_OMITTED;
                AdminManagedFieldsWriteMode adminManagedFieldsWriteMode =
                        AdminManagedFieldsWriteMode.of(adminPublicWrite, bodyJson);
                return taskExecutor.submit(() -> {
                    validateCustomApplication(application);
                    return applicationService.putApplication(descriptor, etag, author, application, adminPublicWrite,
                            adminManagedFieldsWriteMode, externalServicesWriteMode).getKey();
                });
            });
        } else if (descriptor.getType() == ResourceTypes.TOOL_SET) {
            responseFuture = requestFuture.compose(pair -> {
                EtagHeader etag = pair.getKey();
                ToolSet toolSet = ProxyUtil.convertToObject(pair.getValue(), ToolSet.class);
                if (toolSet == null) {
                    throw new HttpException(BAD_REQUEST, "ToolSet can't be empty");
                }
                return taskExecutor.submit(() -> toolSetService.putToolSet(descriptor, etag, author, toolSet, adminPublicWrite).getKey());
            });
        } else {
            responseFuture = requestFuture.compose(pair -> {
                EtagHeader etag = pair.getKey();
                String body = pair.getValue();
                validateRequestBody(descriptor, body);
                return taskExecutor.submit(() -> resourceService.putResource(descriptor, body, etag, author));
            });
        }

        responseFuture.onSuccess((metadata) -> context.putHeader(HttpHeaders.ETAG, metadata.getEtag())
                .exposeHeaders()
                .respond(HttpStatus.OK, metadata))
                .onFailure(error -> handleError(descriptor, error));

        return Future.succeededFuture();
    }

    private Future<?> deleteResource(ResourceDescriptor descriptor) {
        if (descriptor.isFolder()) {
            return context.respond(BAD_REQUEST, "Folder not allowed: " + descriptor.getUrl());
        }

        EtagHeader etag = ProxyUtil.etag(context.getRequest());

        taskExecutor.submit(() -> proxy.getResourceOperationService().deleteResource(context, descriptor, etag))
                .onSuccess(deleted -> {
                    if (deleted) {
                        context.respond(HttpStatus.OK);
                    } else {
                        context.respond(HttpStatus.NOT_FOUND, "Not found: " + descriptor.getUrl());
                    }
                })
                .onFailure(error -> handleError(descriptor, error));

        return Future.succeededFuture();
    }

    private void handleError(ResourceDescriptor descriptor, Throwable error) {
        switch (error) {
            case HttpException exception -> context.respond(exception);
            case IllegalArgumentException ignored -> context.respond(BAD_REQUEST, error.getMessage());
            case ResourceNotFoundException ignored ->
                    context.respond(HttpStatus.NOT_FOUND, "Not found: " + descriptor.getUrl());
            case PermissionDeniedException ignored ->
                    context.respond(HttpStatus.FORBIDDEN, error.getMessage());
            case HttpConnectTimeoutException ignored -> {
                log.error("Timeout connecting to upstream service: {}", descriptor.getUrl(), error);
                context.respond(HttpStatus.GATEWAY_TIMEOUT, error.getMessage());
            }
            case ConnectException ignored -> {
                log.error("Cannot connect to upstream service: {}", descriptor.getUrl(), error);
                context.respond(BAD_GATEWAY, error.getMessage());
            }
            case null, default -> {
                context.respond(HttpStatus.INTERNAL_SERVER_ERROR);
                log.warn("Can't handle resource request: {}", descriptor.getUrl(), error);
            }
        }
    }

    private static void validateRequestBody(ResourceDescriptor descriptor, String body) {
        switch (descriptor.getType()) {
            case PROMPT -> ProxyUtil.convertToObject(body, Prompt.class);
            case CONVERSATION -> ProxyUtil.convertToObject(body, Conversation.class);
            default -> throw new IllegalArgumentException("Unsupported resource type " + descriptor.getType());
        }
    }
}