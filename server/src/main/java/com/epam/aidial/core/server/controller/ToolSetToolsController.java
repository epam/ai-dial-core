package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.credentials.data.credentials.AuthorizationHeader;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;
import com.epam.aidial.core.credentials.service.AuthorizationHeaderProvider;
import com.epam.aidial.core.credentials.service.ResourceCredentialsService;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.OpenApiDescriptions;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.openapi.annotations.ResponseProfile;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.service.ApplicationSchemaService;
import com.epam.aidial.core.server.service.DeploymentService;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.epam.aidial.core.server.upstream.UpstreamRouteProvider;
import com.epam.aidial.core.server.util.AuthSettingsResolver;
import com.epam.aidial.core.server.util.CredentialsLocatorFactory;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.util.UrlUtil;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.spec.McpSchema;
import io.vertx.core.Future;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
public class ToolSetToolsController implements Controller {

    private static final JsonSchemaValidator NOOP_SCHEMA_VALIDATOR =
            (Map<String, Object> schema, Object content) ->
                    JsonSchemaValidator.ValidationResponse.asValid(null);

    private final String toolSetId;
    private final boolean filterAllowed;
    private final CredentialsLocator credentialsLocator;
    private final Proxy proxy;
    private final ProxyContext context;
    private final AsyncTaskExecutor taskExecutor;
    private final DeploymentService deploymentService;
    private final AuthorizationHeaderProvider authorizationHeaderProvider;
    private final UpstreamRouteProvider upstreamRouteProvider;
    private final ApiKeyStore apiKeyStore;
    private final AccessService accessService;
    private final ResourceCredentialsService resourceCredentialsService;
    private final ApplicationSchemaService applicationSchemaService;
    private final AuthSettingsResolver authSettingsResolver;

    public ToolSetToolsController(Proxy proxy, ProxyContext context, String toolSetId, boolean filterAllowed) {
        this.proxy = proxy;
        this.context = context;
        this.toolSetId = toolSetId;
        this.filterAllowed = filterAllowed;
        this.taskExecutor = proxy.getTaskExecutor();
        this.deploymentService = proxy.getDeploymentService();
        this.authorizationHeaderProvider = proxy.getAuthorizationHeaderProvider();
        this.upstreamRouteProvider = proxy.getUpstreamRouteProvider();
        this.apiKeyStore = proxy.getApiKeyStore();
        this.accessService = proxy.getAccessService();
        this.resourceCredentialsService = proxy.getResourceCredentialsService();
        this.applicationSchemaService = proxy.getApplicationSchemaService();
        this.authSettingsResolver = proxy.getAuthSettingsResolver();
        this.credentialsLocator = CredentialsLocatorFactory.fromAnyUrl(
                UrlUtil.encodePath(toolSetId), context, ResourceTypes.TOOL_SET);
    }

    @Override
    @ApiOperation(
            method = "GET",
            path = "/v1/toolset/{toolset_name}/tools",
            operationId = "getToolSetTools",
            tags = {"Toolsets"},
            parameters = {
                    @ApiParameter(name = "toolset_name", in = ParameterIn.PATH, required = true,
                            description = OpenApiDescriptions.TOOLSET_ID)
            },
            responses = {
                    @ApiResponse(code = 200, description = "Success", schemaRef = "ToolSetToolsResponse")
            },
            responseProfile = ResponseProfile.TOOLSET_TOOLS)
    @ApiOperation(
            method = "GET",
            path = "/v1/toolset/{toolset_name}/allowed-tools",
            operationId = "getToolSetAllowedTools",
            tags = {"Toolsets"},
            parameters = {
                    @ApiParameter(name = "toolset_name", in = ParameterIn.PATH, required = true,
                            description = OpenApiDescriptions.TOOLSET_ID)
            },
            responses = {
                    @ApiResponse(code = 200, description = "Success", schemaRef = "AllowedToolsResponse")
            },
            responseProfile = ResponseProfile.TOOLSET_TOOLS)
    public Future<?> handle() {
        return taskExecutor.submit(() -> {
            Deployment deployment = deploymentService.findDeployment(context, toolSetId);
            if (!filterAllowed) {
                checkWriteAccess();
            }
            if (deployment instanceof Application application) {
                resolveMcpProperties(application);
            } else if (!(deployment instanceof ToolSet)) {
                throw new ResourceNotFoundException("Toolset is not found: " + toolSetId);
            }
            UpstreamRoute upstreamRoute = upstreamRouteProvider.get(deployment, null, this::getUpstreamEndpoint);
            upstreamRoute.next();
            context.setUpstreamRoute(upstreamRoute);
            context.setDeployment(deployment);
            fetchTools();
            return null;
        }).onFailure(this::handleError);
    }

    private void checkWriteAccess() {
        if (accessService.hasAdminAccess(context)) {
            return;
        }
        Deployment staticDeployment = context.getConfig().selectDeployment(toolSetId);
        if (staticDeployment != null) {
            throw new HttpException(HttpStatus.FORBIDDEN, "Only admin is allowed to view all tools for static toolsets");
        }
        try {
            String url = UrlUtil.encodePath(toolSetId);
            ResourceDescriptor resource = ResourceDescriptorFactory.fromAnyUrl(url, proxy.getEncryptionService());
            if (!accessService.hasWriteAccess(resource, context)) {
                throw new HttpException(HttpStatus.FORBIDDEN, "Admin or write access required to view all tools");
            }
        } catch (HttpException e) {
            throw e;
        } catch (Exception e) {
            throw new HttpException(HttpStatus.FORBIDDEN, "Admin or write access required to view all tools");
        }
    }

    private void resolveMcpProperties(Application application) {
        Application.Mcp mcp;
        if (application.hasApplicationTypeSchemaId()) {
            mcp = applicationSchemaService.getMcp(application);
            application.setMcp(mcp);
        } else {
            mcp = application.getMcp();
        }
        if (mcp == null) {
            throw new IllegalArgumentException("Application doesn't support MCP protocol: " + application.getName());
        }
    }

    private void fetchTools() {
        UpstreamRoute upstreamRoute = context.getUpstreamRoute();
        Upstream upstream = upstreamRoute.get();
        Objects.requireNonNull(upstream);
        Deployment deployment = context.getDeployment();

        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder(upstream.getEndpoint())
                .httpRequestCustomizer((builder, method, endpoint, body, transportContext) ->
                        customizeRequest(builder, deployment))
                .build();

        try (McpSyncClient client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("DIAL", "1.0"))
                .requestTimeout(Duration.ofMillis(proxy.getClientOptions().getIdleTimeout()))
                .jsonSchemaValidator(NOOP_SCHEMA_VALIDATOR)
                .build()) {
            client.initialize();
            if (filterAllowed) {
                // listTools() auto-paginates all pages via the SDK's expand() chain
                processUserToolsResult(client.listTools().tools());
            } else {
                // listTools(cursor) fetches exactly one page; null = first page
                String cursor = context.getRequest().getParam("nextCursor");
                processAdminToolsResult(client.listTools(cursor));
            }
        } catch (Exception e) {
            McpHttpClientTransportAuthorizationException authError =
                    ExceptionUtils.throwableOfType(e, McpHttpClientTransportAuthorizationException.class);
            if (authError != null) {
                log.warn("Authorization error when fetching tools from MCP server for toolset: {}", toolSetId, e);
                HttpStatus status = HttpStatus.fromStatusCode(authError.getResponseInfo().statusCode(), HttpStatus.UNAUTHORIZED);
                throw new HttpException(status, "Authorization required to fetch tools from toolset '"
                        + toolSetId + "'. Please sign in to the toolset.");
            }
            log.error("Failed to fetch tools from MCP server for toolset: {}", toolSetId, e);
            throw new HttpException(HttpStatus.BAD_GATEWAY, "Failed to fetch tools from MCP server");
        }
    }

    private void customizeRequest(HttpRequest.Builder builder, Deployment deployment) {
        if (deployment instanceof ToolSet toolSet) {
            ResourceAuthSettings authSettings = authSettingsResolver.resolve(toolSet, context);
            ResourceCredentials resourceCredentials = resourceCredentialsService.getRefreshedResourceCredentials(
                    credentialsLocator, authSettings, context.getInitiatorId()
            );
            if (resourceCredentials != null) {
                AuthorizationHeader authorizationHeader =
                        authorizationHeaderProvider.createAuthorizationHeader(resourceCredentials);
                if (authorizationHeader != null) {
                    builder.header(authorizationHeader.getHeaderName(), authorizationHeader.getHeaderValue());
                }
            }
            if (toolSet.isForwardPerRequestKey()) {
                builder.header(Proxy.HEADER_API_KEY, assignPerRequestKey());
            }
        } else if (deployment instanceof Application application) {
            Application.Mcp mcp = application.getMcp();
            if (mcp.isForwardPerRequestKey()) {
                builder.header(Proxy.HEADER_API_KEY, assignPerRequestKey());
            }
        }
    }

    private void processAdminToolsResult(McpSchema.ListToolsResult result) {
        ObjectNode responseBody = ProxyUtil.MAPPER.createObjectNode();
        ArrayNode toolsArray = responseBody.putArray("tools");
        for (McpSchema.Tool tool : result.tools()) {
            toolsArray.add(ProxyUtil.MAPPER.valueToTree(tool));
        }
        if (result.nextCursor() != null) {
            responseBody.put("nextCursor", result.nextCursor());
        }
        finalizeRequest();
        context.respond(HttpStatus.OK, responseBody);
    }

    private void processUserToolsResult(List<McpSchema.Tool> tools) {
        List<String> allowedTools = getAllowedTools(context.getDeployment());
        ObjectNode responseBody = ProxyUtil.MAPPER.createObjectNode();
        ArrayNode toolsArray = responseBody.putArray("tools");
        for (McpSchema.Tool tool : tools) {
            if (allowedTools.isEmpty() || allowedTools.contains(tool.name())) {
                toolsArray.add(ProxyUtil.MAPPER.valueToTree(tool));
            }
        }
        finalizeRequest();
        context.respond(HttpStatus.OK, responseBody);
    }

    private List<String> getAllowedTools(Deployment deployment) {
        if (deployment instanceof ToolSet toolSet) {
            return toolSet.getAllowedTools();
        } else if (deployment instanceof Application application) {
            return application.getMcp().getAllowedTools();
        }
        throw new IllegalArgumentException("Unsupported deployment type: " + deployment.getName());
    }

    private String getUpstreamEndpoint(Deployment deployment) {
        if (deployment instanceof Application application) {
            return application.getMcp().getEndpoint();
        }
        return deployment.getEndpoint();
    }

    private String assignPerRequestKey() {
        ApiKeyData proxyApiKeyData = new ApiKeyData();
        context.setProxyApiKeyData(proxyApiKeyData);
        ApiKeyData.initFromContext(proxyApiKeyData, context);
        apiKeyStore.assignPerRequestApiKey(proxyApiKeyData);
        return proxyApiKeyData.getPerRequestKey();
    }

    private void handleError(Throwable error) {
        switch (error) {
            case PermissionDeniedException ignored ->
                    context.respond(HttpStatus.FORBIDDEN, error.getMessage());
            case HttpException httpException -> context.respond(httpException);
            case ResourceNotFoundException ignored ->
                    context.respond(HttpStatus.NOT_FOUND, error.getMessage());
            case null, default -> {
                String errorMsg = "Error occurred on fetching tools from toolset: %s".formatted(toolSetId);
                context.respond(HttpStatus.INTERNAL_SERVER_ERROR, errorMsg);
                log.error(errorMsg, error);
            }
        }
    }

    private void finalizeRequest() {
        ApiKeyData proxyApiKeyData = context.getProxyApiKeyData();
        if (proxyApiKeyData != null) {
            apiKeyStore.invalidatePerRequestApiKey(proxyApiKeyData)
                    .onSuccess(invalidated -> {
                        if (!invalidated) {
                            log.warn("Per request is not removed: {}", proxyApiKeyData.getPerRequestKey());
                        }
                    }).onFailure(error -> log.error("error occurred on invalidating per-request key", error));
        }
    }
}