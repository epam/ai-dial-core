package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Features;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.openapi.annotations.ResponseProfile;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.ResponseMapping;
import com.epam.aidial.core.server.function.BaseRequestFunction;
import com.epam.aidial.core.server.function.CollectDeploymentsFn;
import com.epam.aidial.core.server.function.CollectRequestApplicationFilesFn;
import com.epam.aidial.core.server.function.CollectRequestChatCompletionAttachmentsFn;
import com.epam.aidial.core.server.function.CollectResponsesApiOutputAttachmentsFn;
import com.epam.aidial.core.server.function.ReplaceResponseIdFn;
import com.epam.aidial.core.server.function.enhancement.ApplyDefaultDeploymentSettingsFn;
import com.epam.aidial.core.server.function.enhancement.EnhanceModelRequestFn;
import com.epam.aidial.core.server.function.request.RequestObject;
import com.epam.aidial.core.server.function.request.ResponsesApiRequest;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.server.util.JsonUtil;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResponseIdUtil;
import com.epam.aidial.core.server.vertx.stream.BufferingReadStream;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Strings;

import java.io.IOException;
import java.util.List;

@Slf4j
public class ResponsesController extends BaseDeploymentPostController {

    private final List<BaseRequestFunction<RequestObject>> enhancementFunctions;

    public ResponsesController(Proxy proxy, ProxyContext context) {
        super(proxy, context);
        this.enhancementFunctions = List.of(
                new CollectRequestChatCompletionAttachmentsFn(proxy, context),
                new ApplyDefaultDeploymentSettingsFn(proxy, context),
                new EnhanceModelRequestFn(proxy, context),
                new CollectRequestApplicationFilesFn(proxy, context),
                new CollectDeploymentsFn(proxy, context));
    }

    @ApiOperation(
            method = "POST",
            path = "/openai/v1/responses",
            operationId = "createResponse",
            requestBody = ResponsesApiRequest.class,
            tags = {"LLM"},
            parameters = {
                    @ApiParameter(name = "Content-Type", in = ParameterIn.HEADER, required = true,
                            description = "Must be application/json")
            },
            responses = {
                    @ApiResponse(code = 200, description = "Success")
            },
            responseProfile = ResponseProfile.RESPONSES_API
    )
    public Future<?> handle() {
        String contentType = context.getRequest().getHeader(HttpHeaders.CONTENT_TYPE);
        if (!Strings.CI.contains(contentType, Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON)) {
            return respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only application/json is supported");
        }
        context.getRequest().body()
                .map(ResponsesController::parseBody)
                .compose(request -> {
                    String model = request.getModel();
                    return proxy.getTaskExecutor().submit(() -> setupDeployment(model))
                            .compose(ignore -> verifyLimit())
                            .compose(ignore -> proxy.getTokenStatsTracker().startSpan(context)
                                    .map(ignored -> handleRequestBody(request)))
                            .otherwise(error -> handleRequestError(model, error));
                })
                .onFailure(this::handleRequestBodyError);

        return Future.succeededFuture();
    }

    private Void setupDeployment(String model) {
        Deployment deployment = proxy.getDeploymentService().findDeployment(context, model);
        proxy.getConsentService().verifyUserConsent(context, deployment);

        Features features = deployment.getFeatures();
        boolean isPerRequestKey = context.getApiKeyData().getPerRequestKey() != null;
        if (features != null && Boolean.FALSE.equals(features.getAccessibleByPerRequestKey()) && isPerRequestKey) {
            throw new PermissionDeniedException(String.format("Deployment %s is not accessible by %s", model, context.getApiKeyData().getSourceDeployment()));
        }

        if (deployment instanceof Application application) {
            deployment = proxy.getApplicationSchemaService().modifyEndpointsForCustomApplication(application);
        }

        if (deployment.getResponsesEndpoint() == null) {
            throw new HttpException(HttpStatus.SERVICE_UNAVAILABLE, "");
        }

        context.setTraceOperation("Send request to %s deployment".formatted(deployment.getName()));
        context.setDeployment(deployment);

        return null;
    }

    private Future<Void> verifyLimit() {
        return proxy.getRateLimiter().limit(context, context.getDeployment())
                .map(rateLimit -> {
                    rateLimit.throwIfError();
                    return null;
                });
    }

    private static ResponsesApiRequest parseBody(Buffer body) {
        log.info("Received body from client. Length: {}", body.length());
        try {
            ObjectNode tree = ProxyUtil.parseObject(body);
            if (tree.has("previous_response_id")) {
                throw new HttpException(HttpStatus.BAD_REQUEST, "previous_response_id is not supported");
            }
            if (tree.has("conversation")) {
                throw new HttpException(HttpStatus.BAD_REQUEST, "conversation is not supported");
            }
            return new ResponsesApiRequest(tree);
        } catch (IOException e) {
            throw new HttpException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
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

    @SneakyThrows
    private Void handleRequestBody(RequestObject request) {
        ApiKeyData proxyApiKeyData = new ApiKeyData();
        context.setProxyApiKeyData(proxyApiKeyData);
        ApiKeyData.initFromContext(proxyApiKeyData, context);

        context.setStreamingRequest(request.isStreaming());
        context.setStoreResponse(request.isStore());
        ProxyUtil.processChain(request, enhancementFunctions);
        // Enhancement functions update the api key, and it should be saved after that
        proxy.getApiKeyStore().assignPerRequestApiKey(proxyApiKeyData);

        context.setRequestBody(Buffer.buffer(request.serialize()));

        Deployment deployment = context.getDeployment();
        String upstreamId = context.getRequest().headers().get(Proxy.HEADER_UPSTREAM_ID);
        UpstreamRoute upstreamRoute = proxy.getUpstreamRouteProvider()
                .get(deployment, context.getCacheBreakpointContext(), upstreamId);

        context.setRequestBodyTimestamp(System.currentTimeMillis());
        context.setUpstreamRoute(upstreamRoute);
        sendRequest();

        return null;
    }

    private void sendRequest() {
        if (nextUpstream()) {
            Upstream upstream = context.getUpstreamRoute().get();
            if (upstream.getId() == null || upstream.getId().isBlank()) {
                respond(HttpStatus.SERVICE_UNAVAILABLE, "Upstream is missing required id");
                return;
            }
            createProxyRequest(Deployment::getResponsesEndpoint)
                    .onSuccess(this::handleProxyRequest)
                    .onFailure(this::handleProxyConnectionError);
        }
    }

    private void handleProxyRequest(HttpClientRequest proxyRequest) {
        log.info("Connected to origin. Deployment: {}. Address: {}",
                context.getDeployment().getName(),
                proxyRequest.connection().remoteAddress());

        context.setProxyRequest(proxyRequest);
        context.setProxyConnectTimestamp(System.currentTimeMillis());

        sendProxyRequest(proxyRequest, Upstream::getResponsesEndpoint)
                .onSuccess(this::handleProxyResponse)
                .onFailure(this::handleProxyResponseError);
    }

    private void handleProxyResponse(HttpClientResponse proxyResponse) {
        UpstreamRoute upstreamRoute = context.getUpstreamRoute();
        Upstream currentUpstream = upstreamRoute.get();
        log.info("Received header from origin. Deployment: {}. Endpoint: {}. Upstream: {}. Status: {}. Headers: {}. Upstream.extraData: {}",
                context.getDeployment().getName(),
                context.getDeployment().getResponsesEndpoint(), currentUpstream == null ? "N/A" : currentUpstream.getResponsesEndpoint(),
                proxyResponse.statusCode(), proxyResponse.headers().size(), currentUpstream == null ? "N/A" : currentUpstream.getExtraData());

        int responseStatusCode = proxyResponse.statusCode();
        if (isRetriableError(responseStatusCode)) {
            upstreamRoute.fail(proxyResponse);
            sendRequest(); // try next
            return;
        }

        if (responseStatusCode == 200) {
            upstreamRoute.succeed(proxyResponse, context.getDeployment());
        } else if (!HttpStatus.fromStatusCode(responseStatusCode).is4xx()) {
            // mark the upstream as failed
            // and the next time we will select another one
            upstreamRoute.fail(proxyResponse);
        }

        context.setProxyResponse(proxyResponse);
        context.setProxyResponseTimestamp(System.currentTimeMillis());

        if (!context.isStreamingRequest()) {
            // Read the entire response to replace the response ID with the DIAL ID
            proxyResponse.body()
                    .compose(body -> handleNonStreamingResponse(proxyResponse, body))
                    .onFailure(this::handleProxyConnectionError);
            return;
        }

        BufferingReadStream responseStream = createResponseStream(proxyResponse, () -> {
            CollectResponsesApiOutputAttachmentsFn attachmentsFn = new CollectResponsesApiOutputAttachmentsFn(proxy, context);
            ReplaceResponseIdFn replaceIdFn = new ReplaceResponseIdFn(proxy, context);
            return new ResponsesSseListener(List.of(attachmentsFn, replaceIdFn));
        });

        HttpServerResponse response = context.getResponse();
        ProxyUtil.handleChunkedResponse(response, proxyResponse);
        response.putHeader(Proxy.HEADER_UPSTREAM_ATTEMPTS, Integer.toString(upstreamRoute.getAttemptCount()));

        responseStream.pipe()
                .endOnFailure(false)
                .endOnSuccess(false)
                .to(response)
                .onSuccess(ignored -> handleResponse(responseStream))
                .onFailure(error -> handleResponseError(error, responseStream));
    }

    private Future<Void> handleNonStreamingResponse(HttpClientResponse proxyResponse, Buffer body) {
        return rewriteResponseId(proxyResponse, body)
                .compose(rewritten -> {
                    context.setResponseBody(rewritten);
                    context.setResponseBodyTimestamp(System.currentTimeMillis());
                    HttpServerResponse response = context.getResponse();
                    ProxyUtil.handleChunkedResponse(response, proxyResponse);
                    response.setChunked(false);
                    response.putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(rewritten.length()));
                    response.putHeader(Proxy.HEADER_UPSTREAM_ATTEMPTS, Integer.toString(context.getUpstreamRoute().getAttemptCount()));
                    return response.end(rewritten);
                })
                .compose(ignore -> collectTokenUsage(context.getResponseBody()))
                .transform(result -> {
                    if (result.failed()) {
                        log.warn("Failed to collect token usage", result.cause());
                    }
                    return collectResponseAttachments(context.getResponseBody(), new CollectResponsesApiOutputAttachmentsFn(proxy, context));
                })
                .onComplete(future -> {
                    if (future.failed()) {
                        log.warn("Failed to collect attachments from response", future.cause());
                    }
                    completeProxyResponse();
                })
                .mapEmpty();
    }

    private Future<Buffer> rewriteResponseId(HttpClientResponse proxyResponse, Buffer body) {
        if (proxyResponse.statusCode() != 200) {
            return Future.succeededFuture(body);
        }
        JsonNode tree = JsonUtil.tryParse(body.getBytes());
        if (tree.isObject() && tree instanceof ObjectNode object) {
            JsonNode idNode = object.path("id");
            if (!idNode.isNull()) {
                String upstreamId = idNode.asText();
                if (!context.isStoreResponse()) {
                    String dialId = ResponseIdUtil.createResponseId(context.getDeployment().getName(), proxy.getGenerator().get());
                    object.put("id", dialId);
                    return Future.succeededFuture(Buffer.buffer(JsonUtil.serialize(object)));
                }
                Upstream upstream = context.getUpstreamRoute().get();
                ResponseMapping mapping = ResponseMapping.builder()
                        .upstreamResponseId(upstreamId)
                        .upstreamKey(upstream.getId())
                        .deploymentName(context.getDeployment().getName())
                        .initiatorBucket(BucketBuilder.buildInitiatorBucket(context))
                        .build();
                return rewriteId(proxy, context, mapping)
                        .map(dialId -> {
                            object.put("id", dialId);
                            return Buffer.buffer(JsonUtil.serialize(object));
                        });
            }
        }

        return Future.succeededFuture(body);
    }

    private void handleResponse(BufferingReadStream responseStream) {
        Buffer responseBody = responseStream.getContent();
        context.setResponseBody(responseBody);
        context.setResponseBodyTimestamp(System.currentTimeMillis());
        Future<TokenUsage> tokenUsageFuture = collectTokenUsage(responseBody);

        tokenUsageFuture.onComplete(result -> {
            if (result.failed()) {
                log.warn("Failed to collect attachments from response", result.cause());
            }
            HttpServerResponse response = context.getResponse();
            responseStream.end(response);
            completeProxyResponse();
        });
    }

    private void completeProxyResponse() {
        proxy.getLogStore().save(context);
        Upstream currentUpstream = context.getUpstreamRoute().get();
        log.info("Sent response to client. Deployment: {}. Endpoint: {}. Upstream: {}. Length: {}."
                        + " Timing: {} (body={}, connect={}, header={}, body={}). Tokens: {}. Upstream.extraData: {}",
                context.getDeployment().getName(),
                context.getDeployment().getEndpoint(),
                currentUpstream == null ? "N/A" : currentUpstream.getEndpoint(),
                context.getResponseBody().length(),
                context.getResponseBodyTimestamp() - context.getRequestTimestamp(),
                context.getRequestBodyTimestamp() - context.getRequestTimestamp(),
                context.getProxyConnectTimestamp() - context.getRequestBodyTimestamp(),
                context.getProxyResponseTimestamp() - context.getProxyConnectTimestamp(),
                context.getResponseBodyTimestamp() - context.getProxyResponseTimestamp(),
                context.getTokenUsage() == null ? "N/A" : context.getTokenUsage(),
                currentUpstream == null ? "N/A" : currentUpstream.getExtraData());

        finalizeRequest();
    }

    private void handleProxyResponseError(Throwable error) {
        UpstreamRoute upstreamRoute = context.getUpstreamRoute();
        // for 5xx errors we use exponential backoff strategy, so passing retryAfterSeconds parameter makes no sense
        upstreamRoute.fail(HttpStatus.BAD_GATEWAY);
        log.warn("Proxy failed to receive response header from origin. Deployment: {}. Address: {}. Error:",
                context.getDeployment().getName(),
                context.getProxyRequest().connection().remoteAddress(),
                error);

        sendRequest(); // try next
    }

    private void handleResponseError(Throwable error, BufferingReadStream responseStream) {
        context.getResponse().reset();     // drop connection, so that partial client response won't seem complete
        log.warn("Can't send response to client. Error:", error);
        Deployment deployment = context.getDeployment();
        if (deployment instanceof Model) {
            // make sure we collect token usage in case if client accidentally closed the connection
            responseStream.endStreamFuture()
                    .onFailure(ignore -> {
                        context.getProxyRequest().reset(); // drop connection to stop origin response
                    })
                    .compose(ignore -> {
                        Buffer responseBody = responseStream.getContent();
                        context.setResponseBody(responseBody);
                        context.setResponseBodyTimestamp(System.currentTimeMillis());
                        return collectTokenUsage(responseBody);
                    })
                    .onSuccess(ignored -> proxy.getLogStore().save(context))
                    .onComplete(ignored -> finalizeRequest());
        } else {
            // drop connection to stop application responding
            context.getProxyRequest().reset();
        }
    }

    private static Future<String> rewriteId(Proxy proxy, ProxyContext context, ResponseMapping mapping) {
        return proxy.getTaskExecutor()
                .submit(() -> proxy.getResponseMappingService().saveMapping(context, mapping));
    }

}