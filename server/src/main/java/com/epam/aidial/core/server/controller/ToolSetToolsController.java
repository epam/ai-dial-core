package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.credentials.data.credentials.AuthorizationHeader;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;
import com.epam.aidial.core.credentials.service.AuthorizationHeaderProvider;
import com.epam.aidial.core.credentials.service.ResourceCredentialsService;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.service.ApplicationSchemaService;
import com.epam.aidial.core.server.service.DeploymentService;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.sse.SseEvent;
import com.epam.aidial.core.server.sse.SseEventListener;
import com.epam.aidial.core.server.sse.SseParser;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.epam.aidial.core.server.upstream.UpstreamRouteProvider;
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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.mutable.MutableObject;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.epam.aidial.core.server.Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON;
import static com.epam.aidial.core.server.Proxy.HEADER_CONTENT_TYPE_TEXT_EVENT_STREAM;

@Slf4j
public class ToolSetToolsController implements Controller {

    private final String toolSetId;
    private final boolean filterAllowed;
    private final CredentialsLocator credentialsLocator;
    private final Proxy proxy;
    private final ProxyContext context;
    private final AsyncTaskExecutor taskExecutor;
    private final DeploymentService deploymentService;
    private final HttpClient httpClient;
    private final AuthorizationHeaderProvider authorizationHeaderProvider;
    private final UpstreamRouteProvider upstreamRouteProvider;
    private final ApiKeyStore apiKeyStore;
    private final AccessService accessService;
    private final ResourceCredentialsService resourceCredentialsService;
    private final ApplicationSchemaService applicationSchemaService;

    public ToolSetToolsController(Proxy proxy, ProxyContext context, String toolSetId, boolean filterAllowed) {
        this.proxy = proxy;
        this.context = context;
        this.toolSetId = toolSetId;
        this.filterAllowed = filterAllowed;
        this.taskExecutor = proxy.getTaskExecutor();
        this.deploymentService = proxy.getDeploymentService();
        this.httpClient = proxy.getClient();
        this.authorizationHeaderProvider = proxy.getAuthorizationHeaderProvider();
        this.upstreamRouteProvider = proxy.getUpstreamRouteProvider();
        this.apiKeyStore = proxy.getApiKeyStore();
        this.accessService = proxy.getAccessService();
        this.resourceCredentialsService = proxy.getResourceCredentialsService();
        this.applicationSchemaService = proxy.getApplicationSchemaService();
        this.credentialsLocator = CredentialsLocatorFactory.fromAnyUrl(
                UrlUtil.encodePath(toolSetId), context, ResourceTypes.TOOL_SET);
    }

    @Override
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
            if (!canRetry(upstreamRoute)) {
                return null;
            }
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
        RequestOptions options = new RequestOptions()
                .setAbsoluteURI(upstream.getEndpoint())
                .setMethod(HttpMethod.POST)
                .setTraceOperation("Fetch tools from %s toolset".formatted(deployment.getName()))
                .setConnectTimeout(proxy.getClientOptions().getConnectTimeout())
                .setIdleTimeout(proxy.getClientOptions().getIdleTimeout());

        httpClient.request(options).onSuccess(proxyRequest -> taskExecutor.submit(() -> {
            handleProxyRequest(proxyRequest);
            return null;
        }).onFailure(error -> {
            log.warn("Failed to handle request to MCP server", error);
            respond(new HttpException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to handle request to MCP server"));
        })).onFailure(this::handleProxyConnectionError);
    }

    private void handleProxyRequest(HttpClientRequest proxyRequest) {
        Deployment deployment = context.getDeployment();

        injectToolsetCredentials(proxyRequest, deployment);

        proxyRequest.putHeader(HttpHeaders.CONTENT_TYPE, HEADER_CONTENT_TYPE_APPLICATION_JSON);
        proxyRequest.putHeader(HttpHeaders.ACCEPT, List.of(HEADER_CONTENT_TYPE_APPLICATION_JSON, HEADER_CONTENT_TYPE_TEXT_EVENT_STREAM));

        ObjectNode requestBody = ProxyUtil.MAPPER.createObjectNode();
        requestBody.put("jsonrpc", "2.0");
        requestBody.put("method", "tools/list");
        requestBody.put("id", 1);
        Buffer proxyRequestBody = Buffer.buffer(requestBody.toString());

        proxyRequest.putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(proxyRequestBody.length()));

        proxyRequest.send(proxyRequestBody)
                .onSuccess(this::handleProxyResponse)
                .onFailure(this::handleProxyRequestError);
    }

    private void handleProxyRequestError(Throwable error) {
        log.warn("Can't send request to origin: {}", error.getMessage());
        UpstreamRoute upstreamRoute = context.getUpstreamRoute();
        // for 5xx errors we use exponential backoff strategy, so passing retryAfterSeconds parameter makes no sense
        upstreamRoute.fail(HttpStatus.BAD_GATEWAY);
        // get next upstream
        if (canRetry(upstreamRoute)) {
            fetchTools(); // try next
        }
    }

    private void handleProxyResponse(HttpClientResponse proxyResponse) {
        int statusCode = proxyResponse.statusCode();
        String contentType = proxyResponse.getHeader(HttpHeaders.CONTENT_TYPE);
        boolean isSse;
        if (Strings.CI.contains(contentType, HEADER_CONTENT_TYPE_APPLICATION_JSON)) {
            isSse = false;
        } else if (Strings.CI.contains(contentType, HEADER_CONTENT_TYPE_TEXT_EVENT_STREAM)) {
            isSse = true;
        } else {
            String errorMessage = "MCP server returns wrong content-type: " + contentType;
            log.warn(errorMessage);
            respond(new HttpException(HttpStatus.BAD_GATEWAY, errorMessage));
            return;
        }

        proxyResponse.body().map(responseBody -> {
            processToolsResponse(responseBody, statusCode, isSse);
            return null;
        }).onFailure(error -> {
            log.warn("Failed to receive response body from MCP server", error);
            respond(new HttpException(HttpStatus.BAD_GATEWAY, "Failed to receive response body from MCP server"));
        });
    }

    /**
     * Called when proxy failed to connect to the origin.
     */
    private void handleProxyConnectionError(Throwable error) {
        log.warn("Can't connect to origin: {}", error.getMessage());
        UpstreamRoute upstreamRoute = context.getUpstreamRoute();
        // for 5xx errors we use exponential backoff strategy, so passing retryAfterSeconds parameter makes no sense
        upstreamRoute.fail(HttpStatus.BAD_GATEWAY);
        // get next upstream
        if (canRetry(upstreamRoute)) {
            fetchTools(); // try next
        }
    }

    private boolean canRetry(UpstreamRoute route) {
        try {
            route.next();
        } catch (HttpException e) {
            respond(e);
            return false;
        }
        return true;
    }

    private void processToolsResponse(Buffer responseBody, int statusCode, boolean isSse) {
        try {
            if (statusCode != HttpStatus.OK.getCode()) {
                context.respond(statusCode, responseBody);
                finalizeRequest();
                return;
            }
            JsonNode tree = parseResponseBody(responseBody, isSse);
            if (!filterAllowed) {
                respond(tree);
                return;
            }
            JsonNode tools = tree.path("result").path("tools");
            if (!tools.isArray()) {
                log.warn("Response doesn't have valid path to tools");
                respond(new HttpException(HttpStatus.BAD_GATEWAY, "Response doesn't have valid path to tools"));
                return;
            }
            List<String> allowedTools = getAllowedTools(context.getDeployment());
            if (!allowedTools.isEmpty()) {
                for (Iterator<JsonNode> iter = tools.iterator(); iter.hasNext(); ) {
                    JsonNode tool = iter.next();
                    JsonNode name = tool.get("name");
                    if (name != null && !allowedTools.contains(name.asText())) {
                        iter.remove();
                    }
                }
            }
            respond(tree);
        } catch (Exception e) {
            log.error("Failed to parse tools/list response from MCP server", e);
            respond(new HttpException(HttpStatus.BAD_GATEWAY, "Failed to parse tools/list response from MCP server"));
        }
    }

    @SneakyThrows
    private JsonNode parseResponseBody(Buffer responseBody, boolean isSse) {
        JsonNode tree;
        if (isSse) {
            MutableObject<SseEvent> sseEventRef = new MutableObject<>();
            SseParser parser = new SseParser(512, new SseEventListener() {
                @Override
                public void onEvent(SseEvent event) {
                    if (sseEventRef.get() != null) {
                        log.warn("Multiple events in a single SSE response");
                        throw new RuntimeException("Multiple events in a single SSE response");
                    }
                    sseEventRef.setValue(event);
                }

                @Override
                public void onComment(String comment) {

                }

                @Override
                public void onComplete() {

                }
            });
            parser.parse(responseBody);
            parser.finish();
            tree = Optional.ofNullable(sseEventRef.get()).map(SseEvent::getData).map(json -> {
                try {
                    return ProxyUtil.MAPPER.readTree(json);
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            }).orElseThrow(() -> new RuntimeException("No SSE event in the response"));
        } else {
            tree = ProxyUtil.MAPPER.readTree(responseBody.getBytes());
        }
        return tree;
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

    private void injectToolsetCredentials(HttpClientRequest proxyRequest, Deployment deployment) {
        if (deployment instanceof ToolSet toolSet) {
            ResourceCredentials resourceCredentials = resourceCredentialsService.getRefreshedResourceCredentials(
                    credentialsLocator, toolSet.getAuthSettings(), context.getUserId()
            );
            if (resourceCredentials != null) {
                addAuthorizationHeader(proxyRequest, resourceCredentials);
            }
            if (toolSet.isForwardPerRequestKey()) {
                String perRequestKey = assignPerRequestKey();
                proxyRequest.putHeader(Proxy.HEADER_API_KEY, perRequestKey);
            }
        } else if (deployment instanceof Application application) {
            Application.Mcp mcp = application.getMcp();
            if (mcp.isForwardPerRequestKey()) {
                String perRequestKey = assignPerRequestKey();
                proxyRequest.putHeader(Proxy.HEADER_API_KEY, perRequestKey);
            }
        }
    }

    private void addAuthorizationHeader(HttpClientRequest proxyRequest, ResourceCredentials resourceCredentials) {
        AuthorizationHeader authorizationHeader = authorizationHeaderProvider.createAuthorizationHeader(resourceCredentials);
        if (authorizationHeader != null) {
            proxyRequest.putHeader(authorizationHeader.getHeaderName(), authorizationHeader.getHeaderValue());
        }
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

    private void respond(HttpException exception) {
        finalizeRequest();
        context.respond(exception);
    }

    private void respond(JsonNode response) {
        finalizeRequest();
        context.respond(HttpStatus.OK, response);
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
