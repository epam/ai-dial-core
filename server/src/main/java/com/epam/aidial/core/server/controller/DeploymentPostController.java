package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Features;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.OpenApiDescriptions;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.ErrorData;
import com.epam.aidial.core.server.function.request.ChatCompletionRequest;
import com.epam.aidial.core.server.function.request.RequestObject;
import com.epam.aidial.core.server.limiter.RateLimitResult;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.util.DeploymentEndpointUtil;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.google.common.annotations.VisibleForTesting;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Strings;

import java.util.List;

import static com.epam.aidial.core.server.Proxy.HEADER_CACHE_POLICY;
import static com.epam.aidial.core.server.Proxy.HEADER_UPSTREAM_ID;

@Slf4j
public class DeploymentPostController extends BaseChatCompletionController {

    public DeploymentPostController(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @ApiOperation(
            method = "POST",
            path = "/openai/deployments/{deployment_name}/completions",
            operationId = "createCompletion",
            tags = {"LLM"},
            parameters = {
                    @ApiParameter(name = "deployment_name", in = ParameterIn.PATH, required = true,
                            description = OpenApiDescriptions.DEPLOYMENT_NAME),
                    @ApiParameter(name = "api-version", in = ParameterIn.QUERY, required = true,
                            description = OpenApiDescriptions.API_VERSION, example = "2024-10-21"),
                    @ApiParameter(name = "Content-Type", in = ParameterIn.HEADER, required = true,
                            description = "Must be application/json", schema = String.class),
                    @ApiParameter(name = HEADER_CACHE_POLICY, in = ParameterIn.HEADER,
                            description = OpenApiDescriptions.CACHE_POLICY,
                            allowableValues = {"availability-priority", "cache-priority"}),
                    @ApiParameter(name = HEADER_UPSTREAM_ID, in = ParameterIn.HEADER,
                            description = OpenApiDescriptions.UPSTREAM_ID)
            },
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(schemaRef = "CreateChatCompletionResponse"), contentTypes = {"application/json"}),
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(schemaRef = "CreateChatCompletionStreamResponse"), contentTypes = {"text/event-stream"}),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 415),
                    @ApiResponse(code = 429, description = "Rate limit exceeded", body = @ApiSchema(implementation = ErrorData.class)),
                    @ApiResponse(code = 500),
                    @ApiResponse(code = 502, description = "Bad Gateway - failed to connect to upstream server", body = @ApiSchema(implementation = ErrorData.class)),
                    @ApiResponse(code = 503)
            })
    @ApiOperation(
            method = "POST",
            path = "/openai/deployments/{deployment_name}/chat/completions",
            operationId = "sendChatCompletionRequest",
            tags = {"LLM"},
            requestBody = @ApiSchema(schemaRef = "ChatCompletionRequest"),
            parameters = {
                    @ApiParameter(name = "deployment_name", in = ParameterIn.PATH, required = true,
                            description = OpenApiDescriptions.DEPLOYMENT_NAME),
                    @ApiParameter(name = "api-version", in = ParameterIn.QUERY, required = true,
                            description = OpenApiDescriptions.API_VERSION, example = "2024-10-21"),
                    @ApiParameter(name = "Content-Type", in = ParameterIn.HEADER, required = true,
                            description = "Must be application/json"),
                    @ApiParameter(name = HEADER_CACHE_POLICY, in = ParameterIn.HEADER,
                            description = OpenApiDescriptions.CACHE_POLICY,
                            allowableValues = {"availability-priority", "cache-priority"}),
                    @ApiParameter(name = HEADER_UPSTREAM_ID, in = ParameterIn.HEADER,
                            description = OpenApiDescriptions.UPSTREAM_ID)
            },
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(schemaRef = "CreateChatCompletionResponse")),
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(schemaRef = "CreateChatCompletionStreamResponse"), contentTypes = {"text/event-stream"}),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 415),
                    @ApiResponse(code = 429, description = "Rate limit exceeded", body = @ApiSchema(implementation = ErrorData.class)),
                    @ApiResponse(code = 500),
                    @ApiResponse(code = 502, description = "Bad Gateway - failed to connect to upstream server", body = @ApiSchema(implementation = ErrorData.class)),
                    @ApiResponse(code = 503)
            })
    @ApiOperation(
            method = "POST",
            path = "/openai/deployments/{deployment_name}/embeddings",
            operationId = "createEmbedding",
            tags = {"LLM"},
            requestBody = @ApiSchema(schemaRef = "EmbeddingsRequest"),
            parameters = {
                    @ApiParameter(name = "deployment_name", in = ParameterIn.PATH, required = true,
                            description = OpenApiDescriptions.DEPLOYMENT_NAME),
                    @ApiParameter(name = "api-version", in = ParameterIn.QUERY, required = true,
                            description = OpenApiDescriptions.API_VERSION, example = "2023-12-01-preview"),
                    @ApiParameter(name = "Content-Type", in = ParameterIn.HEADER, required = true,
                            description = "Must be application/json")
            },
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(schemaRef = "EmbeddingResponse")),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 415),
                    @ApiResponse(code = 429, description = "Rate limit exceeded", body = @ApiSchema(implementation = ErrorData.class)),
                    @ApiResponse(code = 500),
                    @ApiResponse(code = 502, description = "Bad Gateway - failed to connect to upstream server", body = @ApiSchema(implementation = ErrorData.class)),
                    @ApiResponse(code = 503)
            })
    public Future<?> handle(String deploymentId) {
        String contentType = context.getRequest().getHeader(HttpHeaders.CONTENT_TYPE);
        if (!Strings.CI.contains(contentType, Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON)) {
            return respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only application/json is supported");
        }
        // handle a special deployment `interceptor`
        if ("interceptor".equals(deploymentId)) {
            // move to next interceptor
            int nextIndex = context.getApiKeyData().getInterceptorIndex() + 1;
            return handleInterceptor(nextIndex);
        }
        return handleDeployment(deploymentId);
    }

    private Future<?> handleDeployment(String deploymentId) {
        return proxy.getTaskExecutor().submit(() -> proxy.getDeploymentService().findDeployment(context, deploymentId))
                .compose(dep -> proxy.getTaskExecutor().submit(() -> {
                    proxy.getConsentService().verifyUserConsent(context, dep, requestedInterface());
                    return dep;
                }))
                .map(dep -> {
                    Features features = dep.resolveFeatures(requestedInterface());
                    boolean isPerRequestKey = context.getApiKeyData().getPerRequestKey() != null;
                    if (features != null && Boolean.FALSE.equals(features.getAccessibleByPerRequestKey()) && isPerRequestKey) {
                        throw new PermissionDeniedException(String.format("Deployment %s is not accessible by %s", deploymentId, context.getApiKeyData().getSourceDeployment()));
                    }

                    if (dep instanceof Application app) {
                        dep = proxy.getApplicationSchemaService().modifyEndpointsForCustomApplication(app);
                    }

                    if (DeploymentEndpointUtil.resolveServingEndpoint(dep, requestedInterface(),
                            context.getConfig().getTranslators()) == null) {
                        throw new HttpException(HttpStatus.SERVICE_UNAVAILABLE, "");
                    }

                    context.setTraceOperation("Send request to %s deployment".formatted(dep.getName()));
                    context.setDeployment(dep);
                    List<String> interceptors = proxy.getDeploymentService().getInterceptors(context, dep);
                    context.setInterceptors(interceptors);
                    return dep;
                })
                .compose(dep -> {
                    if (dep instanceof Model && !context.hasNextInterceptor()) {
                        return checkLimits(dep);
                    } else {
                        return Future.succeededFuture(RateLimitResult.SUCCESS);
                    }
                })
                .compose(rateLimitResult -> {
                    Future<?> future;
                    if (rateLimitResult.status() == HttpStatus.OK) {
                        if (context.hasNextInterceptor()) {
                            context.setInitialDeployment(deploymentId);
                            future = handleInterceptor(0);
                        } else {
                            future = handleRateLimitSuccess();
                        }
                    } else {
                        handleRateLimitHit(deploymentId, rateLimitResult);
                        future = Future.succeededFuture();
                    }
                    return future;
                })
                .otherwise(error -> {
                    handleRequestError(deploymentId, error);
                    return null;
                });
    }

    private Future<?> handleInterceptor(int interceptorIndex) {
        List<String> interceptors = context.getInterceptors();
        if (interceptorIndex < interceptors.size()) {
            return new ChatCompletionInterceptorController(proxy, context, interceptorIndex, requestedInterface()).handle();
        } else { // all interceptors are completed we should call the initial deployment
            return handleDeployment(context.getApiKeyData().getInitialDeployment());
        }
    }

    private void handleRequestError(String deploymentId, Throwable error) {
        if (error instanceof PermissionDeniedException) {
            respond(HttpStatus.FORBIDDEN, error.getMessage());
            log.warn("Forbidden deployment {}", deploymentId);
        } else if (error instanceof ResourceNotFoundException) {
            respond(HttpStatus.NOT_FOUND, error.getMessage());
            log.warn("Deployment not found {}", deploymentId, error);
        } else if (error instanceof HttpException e) {
            respond(e.getStatus(), e.getMessage());
            log.warn("Deployment error {}", deploymentId, error);
        } else {
            respond(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to process deployment: " + deploymentId);
            log.error("Failed to handle deployment {}", deploymentId, error);
        }
    }

    private Future<?> handleRateLimitSuccess() {
        log.info("Received request from client. Deployment: {}. Headers: {}",
                context.getDeployment().getName(),
                context.getRequest().headers().size());

        setupProxyApiKeyData(new ApiKeyData());
        return proxy.getTokenStatsTracker().startSpan(context).map(ignore -> {
            context.getRequest().body()
                    .onSuccess(body -> proxy.getTaskExecutor().submit(() -> {
                        handleRequestBody(body);
                        return null;
                    }).onFailure(error -> handleRequestError(context.getDeployment().getName(), error)))
                    .onFailure(this::handleRequestBodyError);
            return null;
        });
    }

    private void setupProxyApiKeyData(ApiKeyData proxyApiKeyData) {
        context.setProxyApiKeyData(proxyApiKeyData);
        ApiKeyData.initFromContext(proxyApiKeyData, context);
    }

    private void handleRateLimitHit(String deploymentId, RateLimitResult result) {
        try {
            result.throwIfError();
        } catch (HttpException e) {
            respond(e);
            log.warn("Rate limit error {}. Deployment: {}", result.errorMessage(), deploymentId);
        }
    }

    /**
     * The interface the request path targets. Read from the path rather than from a named group in
     * {@code RouteTemplate.POST_DEPLOYMENT}: named groups become placeholders in the server span name,
     * which would stop telling the three actions apart.
     */
    @Override
    protected InterfaceType requestedInterface() {
        return context.getRequest().path().endsWith("/embeddings")
                ? InterfaceType.OPENAI_EMBEDDINGS
                : InterfaceType.OPENAI_CHAT_COMPLETIONS;
    }

    @VisibleForTesting
    void handleRequestBody(Buffer requestBody) {
        Deployment deployment = context.getDeployment();
        log.info("Received body from client. Deployment: {}. Length: {}",
                deployment.getName(), requestBody.length());

        context.setRequestBody(requestBody);
        context.setRequestBodyTimestamp(System.currentTimeMillis());

        try {
            RequestObject request = new ChatCompletionRequest(ProxyUtil.parseObject(requestBody));
            processChatCompletionsRequestBody(request);
        } catch (Throwable e) {
            if (e instanceof HttpException httpException) {
                respond(httpException.getStatus(), httpException.getMessage());
            } else {
                respond(HttpStatus.BAD_REQUEST);
            }
            log.warn("Can't process JSON request body. Error:", e);
        }
    }

    /**
     * Whether the current request/response is on the chat-completions shape, as opposed to
     * {@code /completions} or {@code /embeddings} which this controller also serves.
     */
    @Override
    protected boolean isChatCompletionsPath() {
        String uri = context.getRequest().uri();
        int queryIndex = uri.indexOf('?');
        String path = queryIndex < 0 ? uri : uri.substring(0, queryIndex);
        return path.endsWith("/chat/completions");
    }
}