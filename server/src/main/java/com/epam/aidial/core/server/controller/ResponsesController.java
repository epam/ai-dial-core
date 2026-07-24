package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Features;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.ErrorData;
import com.epam.aidial.core.server.data.ResponseMapping;
import com.epam.aidial.core.server.function.BaseRequestFunction;
import com.epam.aidial.core.server.function.CollectDeploymentsFn;
import com.epam.aidial.core.server.function.CollectRequestApplicationFilesFn;
import com.epam.aidial.core.server.function.CollectRequestChatCompletionAttachmentsFn;
import com.epam.aidial.core.server.function.CollectResponsesApiOutputAttachmentsFn;
import com.epam.aidial.core.server.function.ExtractTerminalResponseFn;
import com.epam.aidial.core.server.function.ReplaceResponseIdFn;
import com.epam.aidial.core.server.function.enhancement.ApplyDefaultDeploymentSettingsFn;
import com.epam.aidial.core.server.function.enhancement.EnhanceModelRequestFn;
import com.epam.aidial.core.server.function.request.RequestObject;
import com.epam.aidial.core.server.function.request.ResponsesApiRequest;
import com.epam.aidial.core.server.log.AnalyticsLogContext;
import com.epam.aidial.core.server.service.PermissionDeniedException;
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
import org.apache.commons.lang3.tuple.Pair;

import java.io.IOException;
import java.time.Duration;
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
            requestBody = @ApiSchema(implementation = ResponsesApiRequest.class),
            tags = {"LLM"},
            parameters = {
                    @ApiParameter(name = "Content-Type", in = ParameterIn.HEADER, required = true,
                            description = "Must be application/json")
            },
            responses = {
                    @ApiResponse(code = 200, description = "Success"),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 415),
                    @ApiResponse(code = 429, description = "Rate limit exceeded", body = @ApiSchema(implementation = ErrorData.class)),
                    @ApiResponse(code = 500),
                    @ApiResponse(code = 502, description = "Bad Gateway - failed to connect to upstream server", body = @ApiSchema(implementation = ErrorData.class)),
                    @ApiResponse(code = 503)
            }
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
        boolean isPerRequestKey = !context.isOriginalRequest();
        if (features != null && Boolean.FALSE.equals(features.getAccessibleByPerRequestKey()) && isPerRequestKey) {
            throw new PermissionDeniedException(String.format("Deployment %s is not accessible by %s", model, context.getApiKeyData().getSourceDeployment()));
        }

        if (deployment instanceof Application application) {
            deployment = proxy.getApplicationSchemaService().modifyEndpointsForCustomApplication(application);
        }

        if (!deployment.supportsInterface(InterfaceType.OPENAI_RESPONSES)) {
            throw new HttpException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "OpenAI responses not supported for this deployment type"
            );
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
        context.setBackgroundJob(request.isBackground());
        ProxyUtil.processChain(request, enhancementFunctions);
        applyInterfaceDeploymentNameOverride(request, InterfaceType.OPENAI_RESPONSES);
        // Enhancement functions update the api key, and it should be saved after that
        if (request.isBackground()) {
            Duration jobTtl = Duration.ofMillis(proxy.getBackgroundJobService().getJobTtlMs());
            proxy.getApiKeyStore().assignPerRequestApiKey(proxyApiKeyData, jobTtl);
        } else {
            proxy.getApiKeyStore().assignPerRequestApiKey(proxyApiKeyData);
        }

        context.setRequestBody(Buffer.buffer(request.serialize()));

        Deployment deployment = context.getDeployment();
        String upstreamId = context.getRequest().headers().get(Proxy.HEADER_UPSTREAM_ID);
        UpstreamRoute upstreamRoute = proxy.getUpstreamRouteProvider()
                .get(deployment, context.getCacheBreakpointContext(),
                        dep -> dep.resolveEndpoint(InterfaceType.OPENAI_RESPONSES), upstreamId);

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
            createProxyRequest(InterfaceType.OPENAI_RESPONSES)
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

        ExtractTerminalResponseFn extractFn = new ExtractTerminalResponseFn(proxy, context);
        ReplaceResponseIdFn replaceIdFn = new ReplaceResponseIdFn(proxy, context);
        BufferingReadStream responseStream = createResponseStream(proxyResponse, () -> {
            CollectResponsesApiOutputAttachmentsFn attachmentsFn = new CollectResponsesApiOutputAttachmentsFn(proxy, context);
            return new ResponsesSseListener(List.of(attachmentsFn, replaceIdFn, extractFn));
        });

        HttpServerResponse response = context.getResponse();
        ProxyUtil.handleChunkedResponse(response, proxyResponse);
        response.putHeader(Proxy.HEADER_UPSTREAM_ATTEMPTS, Integer.toString(upstreamRoute.getAttemptCount()));

        responseStream.pipe()
                .endOnFailure(false)
                .endOnSuccess(false)
                .to(response)
                .onSuccess(ignored -> handleStreamingResponse(responseStream, replaceIdFn.getDialId(), extractFn.getAssembledStreamingResponse()))
                .onFailure(error -> handleResponseError(error, responseStream));
    }

    private Future<Void> handleNonStreamingResponse(HttpClientResponse proxyResponse, Buffer body) {
        return rewriteResponseId(proxyResponse, body)
                .compose(pair -> {
                    String dialId = pair.getKey();
                    Buffer rewritten = pair.getValue();
                    context.setResponseBody(rewritten);
                    context.setResponseBodyTimestamp(System.currentTimeMillis());
                    HttpServerResponse response = context.getResponse();
                    ProxyUtil.copyResponse(response, proxyResponse);
                    response.setChunked(false);
                    response.putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(rewritten.length()));
                    response.putHeader(Proxy.HEADER_UPSTREAM_ATTEMPTS, Integer.toString(context.getUpstreamRoute().getAttemptCount()));

                    if (context.isBackgroundJob() && dialId != null) {
                        return proxy.getBackgroundJobService().saveJob(dialId, context)
                                .onComplete(result -> {
                                    if (result.failed()) {
                                        log.warn("Failed to save background job record", result.cause());
                                    }
                                    response.end(rewritten);
                                });
                    } else {
                        return collectTokenUsage(rewritten)
                                .transform(result -> {
                                    if (result.failed()) {
                                        log.warn("Failed to collect token usage", result.cause());
                                    }
                                    return collectResponseAttachments(rewritten, new CollectResponsesApiOutputAttachmentsFn(proxy, context));
                                })
                                .onComplete(result -> {
                                    if (result.failed()) {
                                        log.warn("Failed to collect attachments from response", result.cause());
                                    }
                                    response.end(rewritten);
                                    completeProxyResponse(null);
                                });
                    }
                });
    }

    private Future<Pair<String, Buffer>> rewriteResponseId(HttpClientResponse proxyResponse, Buffer body) {
        if (proxyResponse.statusCode() != 200) {
            return Future.succeededFuture(Pair.of(null, body));
        }
        JsonNode tree = JsonUtil.tryParse(body.getBytes());
        if (!tree.isObject() || !(tree instanceof ObjectNode object)) {
            log.warn("Response body is not a JSON object, skipping rewrite. Deployment: {}. Endpoint: {}",
                    context.getDeployment().getName(),
                    context.getDeployment().getResponsesEndpoint());
            return Future.succeededFuture(Pair.of(null, body));
        }
        JsonNode idNode = object.path("id");
        if (!idNode.isTextual()) {
            log.info("Response body doesn't contain 'id' field, skipping rewrite. Deployment: {}. Endpoint: {}",
                    context.getDeployment().getName(),
                    context.getDeployment().getResponsesEndpoint());
            return Future.succeededFuture(Pair.of(null, body));
        }

        String upstreamId = idNode.asText();
        if (!context.isStoreResponse()) {
            String dialId = ResponseIdUtil.createResponseId(context.getDeployment().getName(), proxy.getGenerator().get());
            object.put("id", dialId);
            return Future.succeededFuture(Pair.of(dialId, Buffer.buffer(JsonUtil.serialize(object))));
        }
        Upstream upstream = context.getUpstreamRoute().get();
        ResponseMapping mapping = ResponseMapping.builder()
                .upstreamResponseId(upstreamId)
                .upstreamKey(upstream.getId())
                .deploymentName(context.getDeployment().getName())
                .initiatorBucket(BucketBuilder.buildInitiatorBucket(context))
                .build();
        return proxy.getTaskExecutor()
                .submit(() -> proxy.getResponseMappingService().saveMapping(context, mapping))
                .map(dialId -> {
                    object.put("id", dialId);
                    return Pair.of(dialId, Buffer.buffer(JsonUtil.serialize(object)));
                });
    }

    private void handleStreamingResponse(BufferingReadStream responseStream, String dialId, String assembledStreamingResponse) {
        Buffer responseBody = responseStream.getContent();
        context.setResponseBody(responseBody);
        context.setResponseBodyTimestamp(System.currentTimeMillis());

        Future<Void> completionFuture;
        if (context.isBackgroundJob() && dialId != null) {
            completionFuture = proxy.getBackgroundJobService().deleteJob(dialId)
                    .compose(deleted -> deleted ? collectTokenUsage(responseBody) : Future.succeededFuture());
        } else {
            completionFuture = collectTokenUsage(responseBody);
        }

        completionFuture.onComplete(result -> {
            if (result.failed()) {
                log.warn("Failed to collect token usage", result.cause());
            }
            responseStream.end(context.getResponse());
            completeProxyResponse(assembledStreamingResponse);
        });
    }

    private void completeProxyResponse(String assembledStreamingResponse) {
        proxy.getLogStore().save(AnalyticsLogContext.from(context, assembledStreamingResponse));
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
}