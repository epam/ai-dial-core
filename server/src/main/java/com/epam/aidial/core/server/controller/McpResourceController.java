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
import com.epam.aidial.core.server.data.ErrorData;
import com.epam.aidial.core.server.limiter.RateLimitResult;
import com.epam.aidial.core.server.limiter.RateLimiter;
import com.epam.aidial.core.server.mcp.McpClientUtils;
import com.epam.aidial.core.server.mcp.McpHttpClientBuilder;
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
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.util.UrlUtil;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.spec.McpSchema;
import io.vertx.core.Future;
import io.vertx.core.http.HttpHeaders;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static io.vertx.core.http.HttpHeaders.RETRY_AFTER;

@Slf4j
public class McpResourceController implements Controller {

    // sandbox without allow-same-origin: the widget runs in a null opaque origin and
    // cannot make credentialed requests to the DIAL API, preventing same-origin XSS.
    // frame-ancestors 'self': prevents third-party sites from framing this endpoint.
    private static final String WIDGET_CSP =
            "frame-ancestors 'self'; sandbox allow-scripts; default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src https: data:";

    // Allowlist of MIME types that may be returned as widget content (lowercase, no params).
    // Restricts the upstream-controlled mimeType field to prevent header injection
    // and avoid serving unexpected content types (e.g. application/octet-stream).
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "text/html",
            "text/plain",
            "text/css",
            "application/json",
            "image/svg+xml",
            "image/png",
            "image/jpeg",
            "image/gif",
            "image/webp"
    );

    // Charset values safe to forward verbatim in the Content-Type response header.
    // Exotic or locale-specific charsets from untrusted upstream responses are dropped.
    // utf-8 ~99 %, iso-8859-1 ~1 %, us-ascii is a UTF-8 subset — together cover the real web.
    private static final Set<String> ALLOWED_CHARSETS = Set.of("utf-8", "us-ascii", "iso-8859-1");

    private final Proxy proxy;
    private final ProxyContext context;
    private final String applicationId;
    private final DeploymentService deploymentService;
    private final ConsentService consentService;
    private final ApplicationSchemaService applicationSchemaService;
    private final AsyncTaskExecutor taskExecutor;
    private final ApiKeyStore apiKeyStore;
    private final RateLimiter rateLimiter;
    private final McpUpstreamAuthInjector authInjector;
    private final McpHttpClientBuilder mcpHttpClientBuilder;

    public McpResourceController(Proxy proxy, ProxyContext context, String applicationId) {
        this.proxy = proxy;
        this.context = context;
        this.applicationId = applicationId;
        this.deploymentService = proxy.getDeploymentService();
        this.consentService = proxy.getConsentService();
        this.applicationSchemaService = proxy.getApplicationSchemaService();
        this.taskExecutor = proxy.getTaskExecutor();
        this.apiKeyStore = proxy.getApiKeyStore();
        this.rateLimiter = proxy.getRateLimiter();
        this.authInjector = new McpUpstreamAuthInjector(proxy);
        this.mcpHttpClientBuilder = proxy.getMcpHttpClientBuilder();
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
        if (!resourceUri.startsWith("ui://")) {
            return context.respond(HttpStatus.BAD_REQUEST, "Resource URI must use the 'ui://' scheme");
        }

        return taskExecutor.submit(() -> {
            Deployment deployment = deploymentService.findDeployment(context, applicationId);
            consentService.verifyUserConsent(context, deployment);
            context.setDeployment(deployment);
            if (deployment instanceof Application app) {
                if (app.hasApplicationTypeSchemaId()) {
                    app.setMcp(applicationSchemaService.getMcp(app));
                }
                if (app.getMcp() == null) {
                    throw new IllegalArgumentException("Application doesn't support MCP protocol: " + applicationId);
                }
            } else if (!(deployment instanceof ToolSet)) {
                throw new ResourceNotFoundException("Application or ToolSet is not found: " + applicationId);
            }
            return deployment;
        }).compose(deployment -> rateLimiter.limit(context, deployment)
                .compose(rateLimitResult -> {
                    if (rateLimitResult.status() == HttpStatus.OK) {
                        return fetchResource(deployment, resourceUri);
                    }
                    handleRateLimitHit(rateLimitResult);
                    return Future.succeededFuture();
                }))
                .otherwise(error -> {
                    handleError(error);
                    return null;
                })
                .onComplete(ignored -> finalizeRequest());
    }

    private Future<?> fetchResource(Deployment deployment, String resourceUri) {
        return taskExecutor.submit(() -> {
            Map<String, String> authHeaders = new LinkedHashMap<>();
            String endpoint;
            if (deployment instanceof Application app) {
                authInjector.inject(authHeaders::put, app, context);
                endpoint = app.getMcp().getEndpoint();
            } else {
                ToolSet toolSet = (ToolSet) deployment;
                CredentialsLocator credentialsLocator = CredentialsLocatorFactory.fromAnyUrl(
                        UrlUtil.encodePath(applicationId), context, ResourceTypes.TOOL_SET);
                authInjector.inject(authHeaders::put, toolSet, context, credentialsLocator);
                endpoint = toolSet.getEndpoint();
            }
            try {
                McpClientUtils.withSyncClient(
                        endpoint,
                        Duration.ofMillis(proxy.getClientOptions().getIdleTimeout()),
                        mcpHttpClientBuilder.httpClientBuilder(),
                        builder -> authHeaders.forEach(builder::header),
                        client -> {
                            McpSchema.ReadResourceResult result = client.readResource(
                                    new McpSchema.ReadResourceRequest(resourceUri));
                            sendResourceResponse(result);
                            return null;
                        });
            } catch (Exception e) {
                McpHttpClientTransportAuthorizationException authError =
                        ExceptionUtils.throwableOfType(e, McpHttpClientTransportAuthorizationException.class);
                if (authError != null) {
                    HttpStatus status = HttpStatus.fromStatusCode(
                            authError.getResponseInfo().statusCode(), HttpStatus.UNAUTHORIZED);
                    throw new HttpException(status, "MCP server returned " + authError.getResponseInfo().statusCode());
                }
                log.warn("Failed to fetch MCP resource '{}' for deployment '{}'", resourceUri, applicationId, e);
                throw new HttpException(HttpStatus.BAD_GATEWAY, "Failed to fetch MCP resource");
            }
            return null;
        });
    }

    void sendResourceResponse(McpSchema.ReadResourceResult result) {
        List<McpSchema.ResourceContents> contents = result.contents();
        if (contents == null || contents.isEmpty()) {
            context.respond(HttpStatus.BAD_GATEWAY, "Empty resource contents");
            return;
        }
        McpSchema.ResourceContents first = contents.get(0);
        String mimeType = first.mimeType();
        if (mimeType == null || mimeType.isBlank()) {
            context.respond(HttpStatus.BAD_GATEWAY, "Resource missing mimeType");
            return;
        }
        Optional<String> contentType = resolveContentType(mimeType);
        if (contentType.isEmpty()) {
            context.respond(HttpStatus.BAD_GATEWAY, "Unsupported resource mimeType: " + mimeType);
            return;
        }
        if (!(first instanceof McpSchema.TextResourceContents textContents)) {
            context.respond(HttpStatus.BAD_GATEWAY, "Unsupported resource contents type: " + first.getClass().getSimpleName());
            return;
        }
        context.getResponse()
                .putHeader(HttpHeaders.CONTENT_TYPE, contentType.get())
                .putHeader("Content-Security-Policy", WIDGET_CSP)
                .putHeader("X-Content-Type-Options", "nosniff")
                .end(textContents.text());
    }

    // Returns the normalised Content-Type header value for the given upstream mimeType,
    // or empty if the base type is not on the allowlist.
    // Normalises case (RFC 2045 §5.1) and strips whitespace. Forwards the charset parameter
    // only when its value is on the allowlist; all other params are dropped to prevent
    // header injection via upstream-controlled content.
    static Optional<String> resolveContentType(String mimeType) {
        String stripped = mimeType.strip().toLowerCase(Locale.ROOT);
        int semicolonIdx = stripped.indexOf(';');
        String baseMimeType = semicolonIdx >= 0 ? stripped.substring(0, semicolonIdx).strip() : stripped;
        if (!ALLOWED_MIME_TYPES.contains(baseMimeType)) {
            return Optional.empty();
        }
        if (semicolonIdx >= 0) {
            // split(";") on a literal delimiter is linear — no backtracking.
            // strip() on each token handles OWS around ";" (RFC 9110 allows it).
            // First charset= param wins; duplicates are ignored.
            for (String param : stripped.substring(semicolonIdx + 1).split(";")) {
                String p = param.strip();
                if (p.startsWith("charset=")) {
                    String cs = p.substring("charset=".length()).strip();
                    // Strip surrounding double quotes per RFC 9110 §5.6.6 quoted-string.
                    // Only enclosing quotes are removed; single quotes are not special in HTTP.
                    if (cs.length() >= 2 && cs.startsWith("\"") && cs.endsWith("\"")) {
                        cs = cs.substring(1, cs.length() - 1);
                    }
                    return Optional.of(ALLOWED_CHARSETS.contains(cs)
                            ? baseMimeType + ";charset=" + cs
                            : baseMimeType);
                }
            }
        }
        return Optional.of(baseMimeType);
    }

    private void handleRateLimitHit(RateLimitResult result) {
        ErrorData rateLimitError = new ErrorData();
        rateLimitError.getError().setCode(String.valueOf(result.status().getCode()));
        rateLimitError.getError().setMessage(result.errorMessage());
        rateLimitError.getError().setDisplayMessage(result.displayErrorMessage());
        String errorMessage = ProxyUtil.convertToString(rateLimitError);
        HttpException httpException;
        if (result.replyAfterSeconds() >= 0) {
            httpException = new HttpException(result.status(), errorMessage,
                    Map.of(RETRY_AFTER.toString(), Long.toString(result.replyAfterSeconds())));
        } else {
            httpException = new HttpException(result.status(), errorMessage);
        }
        context.respond(httpException);
        log.warn("Rate limit hit for MCP resource request: {}", result.errorMessage());
    }

    private void handleError(Throwable error) {
        switch (error) {
            case PermissionDeniedException ignored ->
                    context.respond(HttpStatus.FORBIDDEN, error.getMessage());
            case ResourceNotFoundException ignored ->
                    context.respond(HttpStatus.NOT_FOUND, error.getMessage());
            case IllegalArgumentException ignored ->
                    context.respond(HttpStatus.BAD_REQUEST, error.getMessage());
            case HttpException httpException -> context.respond(httpException);
            case null, default -> {
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
