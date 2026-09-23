package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Features;
import com.epam.aidial.core.config.InterfaceType;
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
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.util.DeploymentEndpointUtil;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Strings;

import java.io.IOException;

import static com.epam.aidial.core.server.Proxy.HEADER_CACHE_POLICY;
import static com.epam.aidial.core.server.Proxy.HEADER_UPSTREAM_ID;

/**
 * OpenAI-native {@code POST /openai/v1/chat/completions}: same behavior as
 * {@link DeploymentPostController}'s {@code /openai/deployments/{id}/chat/completions}, except the
 * target deployment is resolved from the request body's {@code model} field instead of the URL path.
 */
@Slf4j
public class ChatCompletionsController extends BaseChatCompletionController {

    public ChatCompletionsController(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @ApiOperation(
            method = "POST",
            path = "/openai/v1/chat/completions",
            operationId = "createChatCompletion",
            requestBody = @ApiSchema(schemaRef = "ChatCompletionRequest"),
            tags = {"LLM"},
            parameters = {
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
    public Future<?> handle() {
        String contentType = context.getRequest().getHeader(HttpHeaders.CONTENT_TYPE);
        if (!Strings.CI.contains(contentType, Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON)) {
            return respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only application/json is supported");
        }
        context.getRequest().body()
                .map(ChatCompletionsController::parseBody)
                .compose(this::dispatch)
                .onFailure(this::handleRequestBodyError);
        return Future.succeededFuture();
    }

    private static ChatCompletionRequest parseBody(Buffer body) {
        try {
            return new ChatCompletionRequest(ProxyUtil.parseObject(body));
        } catch (IOException e) {
            throw new HttpException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private Future<Void> dispatch(ChatCompletionRequest request) {
        ApiKeyData apiKeyData = context.getApiKeyData();
        String deploymentName;
        if (apiKeyData.isInterceptor()) {
            context.setInitialDeployment(apiKeyData.getInitialDeployment());
            context.setInterceptors(apiKeyData.getInterceptors());
            int nextIndex = apiKeyData.getInterceptorIndex() + 1;
            if (nextIndex < apiKeyData.getInterceptors().size()) {
                return handleInterceptor(nextIndex);
            }
            deploymentName = apiKeyData.getInitialDeployment();
        } else {
            deploymentName = request.getModel();
            if (deploymentName == null || deploymentName.isBlank()) {
                respond(HttpStatus.BAD_REQUEST, "model is required");
                return Future.succeededFuture();
            }
        }
        return proxy.getTaskExecutor().submit(() -> setupDeployment(deploymentName))
                .compose(ignore -> {
                    if (context.hasNextInterceptor()) {
                        context.setInitialDeployment(context.getDeployment().getName());
                        return handleInterceptor(0);
                    }
                    return proceedToDeployment(request, deploymentName);
                })
                .otherwise(error -> handleRequestError(deploymentName, error));
    }

    private Future<Void> proceedToDeployment(ChatCompletionRequest request, String deploymentName) {
        return verifyLimit()
                .compose(ignore -> proxy.getTokenStatsTracker().startSpan(context)
                        .map(ignored -> handleRequestBody(request)))
                .otherwise(error -> handleRequestError(deploymentName, error));
    }

    private Future<Void> handleInterceptor(int interceptorIndex) {
        return new ChatCompletionInterceptorController(proxy, context, interceptorIndex, requestedInterface())
                .handle().mapEmpty();
    }

    private Void setupDeployment(String model) {
        Deployment deployment = proxy.getDeploymentService().findDeployment(context, model);
        proxy.getConsentService().verifyUserConsent(context, deployment, InterfaceType.OPENAI_CHAT_COMPLETIONS);

        Features features = deployment.resolveFeatures(InterfaceType.OPENAI_CHAT_COMPLETIONS);
        boolean isPerRequestKey = !context.isOriginalRequest();
        if (features != null && Boolean.FALSE.equals(features.getAccessibleByPerRequestKey()) && isPerRequestKey) {
            throw new PermissionDeniedException(
                    String.format("Deployment %s is not accessible by %s", model, context.getApiKeyData().getSourceDeployment()));
        }

        if (deployment instanceof Application application) {
            deployment = proxy.getApplicationSchemaService().modifyEndpointsForCustomApplication(application);
        }

        if (DeploymentEndpointUtil.resolveServingEndpoint(deployment, InterfaceType.OPENAI_CHAT_COMPLETIONS,
                context.getConfig().getTranslators()) == null) {
            throw new HttpException(HttpStatus.SERVICE_UNAVAILABLE, "");
        }

        context.setTraceOperation("Send request to %s deployment".formatted(deployment.getName()));
        context.setDeployment(deployment);
        context.setInterceptors(proxy.getDeploymentService().getInterceptors(context, deployment));
        return null;
    }

    private Future<Void> verifyLimit() {
        return checkLimits(context.getDeployment())
                .map(rateLimit -> {
                    rateLimit.throwIfError();
                    return null;
                });
    }

    private Void handleRequestBody(ChatCompletionRequest request) {
        ApiKeyData proxyApiKeyData = new ApiKeyData();
        context.setProxyApiKeyData(proxyApiKeyData);
        ApiKeyData.initFromContext(proxyApiKeyData, context);

        processChatCompletionsRequestBody(request);
        return null;
    }

    private Void handleRequestError(String deploymentId, Throwable error) {
        if (error instanceof PermissionDeniedException) {
            respond(HttpStatus.FORBIDDEN, error.getMessage());
            log.warn("Forbidden deployment {}", deploymentId);
        } else if (error instanceof ResourceNotFoundException) {
            respond(HttpStatus.NOT_FOUND, error.getMessage());
            log.warn("Deployment not found {}", deploymentId, error);
        } else if (error instanceof HttpException httpException) {
            respond(httpException);
            log.warn("Deployment error {}", deploymentId, error);
        } else {
            respond(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to process deployment: " + deploymentId);
            log.error("Failed to handle deployment {}", deploymentId, error);
        }

        return null;
    }
}
