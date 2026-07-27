package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiOperations;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.OpenApiDescriptions;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.service.ApplicationSchemaService;
import com.epam.aidial.core.server.service.ConsentService;
import com.epam.aidial.core.server.service.DeploymentService;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.util.CredentialsLocatorFactory;
import com.epam.aidial.core.server.util.McpUpstreamAuthInjector;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.util.UrlUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
public class McpResourceController implements Controller {

    // sandbox without allow-same-origin: the widget runs in a null opaque origin and
    // cannot make credentialed requests to the DIAL API, preventing same-origin XSS.
    private static final String WIDGET_CSP =
            "sandbox allow-scripts; default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src https: data:";

    private final ProxyContext context;
    private final String applicationId;
    private final DeploymentService deploymentService;
    private final ConsentService consentService;
    private final ApplicationSchemaService applicationSchemaService;
    private final AsyncTaskExecutor taskExecutor;
    private final HttpClient httpClient;
    private final ApiKeyStore apiKeyStore;
    private final McpUpstreamAuthInjector authInjector;

    public McpResourceController(Proxy proxy, ProxyContext context, String applicationId) {
        this.context = context;
        this.applicationId = applicationId;
        this.deploymentService = proxy.getDeploymentService();
        this.consentService = proxy.getConsentService();
        this.applicationSchemaService = proxy.getApplicationSchemaService();
        this.taskExecutor = proxy.getTaskExecutor();
        this.httpClient = proxy.getClient();
        this.apiKeyStore = proxy.getApiKeyStore();
        this.authInjector = new McpUpstreamAuthInjector(proxy);
    }

    @Override
    @ApiOperations({
            @ApiOperation(
                    method = "GET",
                    path = "/v1/deployments/{deployment_name}/mcp/resources",
                    operationId = "getApplicationMcpResources",
                    tags = {"Deployments", "MCP"},
                    parameters = {
                            @ApiParameter(name = "deployment_name", in = ParameterIn.PATH, required = true,
                                    description = OpenApiDescriptions.DEPLOYMENT_IDENTIFIER),
                            @ApiParameter(name = "uri", in = ParameterIn.QUERY, required = true,
                                    description = "URI of the MCP resource to retrieve")
                    },
                    responses = {
                            @ApiResponse(code = 200, description = "HTML widget content"),
                            @ApiResponse(code = 400),
                            @ApiResponse(code = 403),
                            @ApiResponse(code = 404),
                            @ApiResponse(code = 502),
                            @ApiResponse(code = 500)
                    })
    })
    public Future<?> handle() {
        String resourceUri = context.getRequest().getParam("uri");
        if (resourceUri == null || resourceUri.isBlank()) {
            return context.respond(HttpStatus.BAD_REQUEST, "Missing 'uri' query parameter");
        }

        return taskExecutor.submit(() -> {
            Deployment deployment = deploymentService.findDeployment(context, applicationId);
            consentService.verifyUserConsent(context, deployment);
            context.setDeployment(deployment);
            Map<String, String> authHeaders = new LinkedHashMap<>();
            if (deployment instanceof Application app) {
                Application.Mcp mcp;
                if (app.hasApplicationTypeSchemaId()) {
                    mcp = applicationSchemaService.getMcp(app);
                    app.setMcp(mcp);
                } else {
                    mcp = app.getMcp();
                }
                if (mcp == null) {
                    throw new IllegalArgumentException("Application doesn't support MCP protocol: " + applicationId);
                }
                authInjector.inject(authHeaders::put, app, context);
            } else if (deployment instanceof ToolSet toolSet) {
                CredentialsLocator credentialsLocator = CredentialsLocatorFactory.fromAnyUrl(
                        UrlUtil.encodePath(applicationId), context, ResourceTypes.TOOL_SET);
                authInjector.inject(authHeaders::put, toolSet, context, credentialsLocator);
            } else {
                throw new ResourceNotFoundException("Application or ToolSet is not found: " + applicationId);
            }
            return new FetchContext(deployment, authHeaders);
        }).compose(fc -> fetchResource(fc, resourceUri))
                .otherwise(error -> {
                    handleError(error);
                    return null;
                })
                .onComplete(ignored -> finalizeRequest());
    }

    private record FetchContext(Deployment deployment, Map<String, String> authHeaders) {}

    private Future<?> fetchResource(FetchContext fc, String resourceUri) {
        ObjectNode params = ProxyUtil.MAPPER.createObjectNode();
        params.put("uri", resourceUri);
        ObjectNode body = ProxyUtil.MAPPER.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", "resources/read");
        body.set("params", params);
        Buffer requestBody = Buffer.buffer(body.toString());

        String endpoint = fc.deployment() instanceof Application app
                ? app.getMcp().getEndpoint()
                : fc.deployment().getEndpoint();

        RequestOptions options = new RequestOptions()
                .setAbsoluteURI(endpoint)
                .setMethod(HttpMethod.POST)
                .setConnectTimeout(context.getProxy().getClientOptions().getConnectTimeout())
                .setIdleTimeout(context.getProxy().getClientOptions().getIdleTimeout());

        return httpClient.request(options)
                .compose(req -> {
                    req.putHeader(HttpHeaders.CONTENT_TYPE, "application/json");
                    req.putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(requestBody.length()));
                    fc.authHeaders().forEach(req::putHeader);
                    return req.send(requestBody);
                })
                .compose(resp -> resp.body()
                        .compose(buf -> handleResourceBody(resp.statusCode(), buf)));
    }

    private Future<?> handleResourceBody(int status, Buffer buf) {
        if (status != 200) {
            return context.respond(HttpStatus.BAD_GATEWAY, "MCP server returned " + status);
        }
        try {
            JsonNode json = ProxyUtil.MAPPER.readTree(buf.getBytes());
            JsonNode contents = json.path("result").path("contents");
            if (!contents.isArray() || contents.isEmpty()) {
                return context.respond(HttpStatus.BAD_GATEWAY, "Empty resource contents");
            }
            JsonNode first = contents.get(0);
            JsonNode mimeTypeNode = first.path("mimeType");
            if (mimeTypeNode.isMissingNode() || mimeTypeNode.isNull()) {
                return context.respond(HttpStatus.BAD_GATEWAY, "Resource missing mimeType");
            }
            String mimeType = mimeTypeNode.asText();
            String text = first.path("text").asText("");
            return context.getResponse()
                    .putHeader(HttpHeaders.CONTENT_TYPE, mimeType)
                    .putHeader("Content-Security-Policy", WIDGET_CSP)
                    .putHeader("X-Content-Type-Options", "nosniff")
                    .end(text);
        } catch (Exception e) {
            log.warn("Failed to parse MCP resource response", e);
            return context.respond(HttpStatus.BAD_GATEWAY, "Invalid resource response");
        }
    }

    private void handleError(Throwable error) {
        switch (error) {
            case PermissionDeniedException ignored ->
                    context.respond(HttpStatus.FORBIDDEN, error.getMessage());
            case ResourceNotFoundException ignored ->
                    context.respond(HttpStatus.NOT_FOUND, error.getMessage());
            case IllegalArgumentException ignored ->
                    context.respond(HttpStatus.BAD_REQUEST, error.getMessage());
            default -> {
                log.error("Error handling MCP resource request for {}", applicationId, error);
                context.respond(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to handle MCP resource request");
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
