package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.InterfacePathMapping;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.openapi.annotations.ApiExtension;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiOperations;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.ErrorData;
import com.epam.aidial.core.server.data.ResponseMapping;
import com.epam.aidial.core.server.function.CollectResponsesApiOutputAttachmentsFn;
import com.epam.aidial.core.server.function.EncryptedContentWrapFn;
import com.epam.aidial.core.server.function.ExtractTerminalResponseFn;
import com.epam.aidial.core.server.function.ReplaceResponseIdFn;
import com.epam.aidial.core.server.service.ResponsesApiClient;
import com.epam.aidial.core.server.tracing.GenAiTraceAttributes;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.server.util.DeploymentEndpointUtil;
import com.epam.aidial.core.server.util.EncryptedContentAffinityUtil;
import com.epam.aidial.core.server.util.JsonUtil;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.stream.BufferingReadStream;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Strings;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class ResponseItemController implements Controller {

    private final Proxy proxy;
    private final ProxyContext context;
    private final String dialResponseId;
    private final Operation operation;

    @Override
    @ApiOperations({
            @ApiOperation(
                    method = "GET",
                    path = "/openai/v1/responses/{response_id}",
                    operationId = "getResponseItem",
                    tags = {"Responses API"},
                    parameters = {
                            @ApiParameter(name = "response_id", in = ParameterIn.PATH, required = true,
                                    description = "The ID of the response to retrieve")
                    },
                    responses = {
                            @ApiResponse(code = 200, description = "Success", body = @ApiSchema(schemaRef = "ProxyResponse")),
                            @ApiResponse(code = 200, description = "Success", body = @ApiSchema(schemaRef = "ProxyResponse"), contentTypes = {"text/event-stream"}),
                            @ApiResponse(code = 403),
                            @ApiResponse(code = 404),
                            @ApiResponse(code = 500),
                            @ApiResponse(code = 503)
                    },
                    extensions = {
                            @ApiExtension(name = "x-preview", value = "true")
                    }
            ),
            @ApiOperation(
                    method = "POST",
                    path = "/openai/v1/responses/{response_id}/cancel",
                    operationId = "cancelResponseItem",
                    tags = {"Responses API"},
                    parameters = {
                            @ApiParameter(name = "response_id", in = ParameterIn.PATH, required = true,
                                    description = "The ID of the response to cancel")
                    },
                    responses = {
                            @ApiResponse(code = 200, description = "Success", body = @ApiSchema(schemaRef = "ProxyResponse")),
                            @ApiResponse(code = 403),
                            @ApiResponse(code = 404),
                            @ApiResponse(code = 500),
                            @ApiResponse(code = 503)
                    },
                    extensions = {
                            @ApiExtension(name = "x-preview", value = "true")
                    }
            ),
            @ApiOperation(
                    method = "DELETE",
                    path = "/openai/v1/responses/{response_id}",
                    operationId = "deleteResponseItem",
                    tags = {"Responses API"},
                    parameters = {
                            @ApiParameter(name = "response_id", in = ParameterIn.PATH, required = true,
                                    description = "The ID of the response to delete")
                    },
                    responses = {
                            @ApiResponse(code = 200, description = "Success", body = @ApiSchema(schemaRef = "ProxyResponse")),
                            @ApiResponse(code = 403),
                            @ApiResponse(code = 404),
                            @ApiResponse(code = 500),
                            @ApiResponse(code = 503)
                    },
                    extensions = {
                            @ApiExtension(name = "x-preview", value = "true")
                    }
            )
    })
    public Future<?> handle() {
        return proxy.getTaskExecutor().submit(this::loadMapping)
                .compose(this::checkNotDeletingActive)
                .compose(this::dispatch)
                .eventually(this::finalizeRequest)
                .onFailure(error -> {
                    if (!context.getResponse().ended()) {
                        context.respond(error, "Failed to process response operation");
                    }
                });
    }

    private Future<Void> finalizeRequest() {
        ApiKeyData proxyApiKeyData = context.getProxyApiKeyData();
        if (proxyApiKeyData == null) {
            return Future.succeededFuture();
        }
        return proxy.getApiKeyStore().invalidatePerRequestApiKey(proxyApiKeyData)
                .onSuccess(invalidated -> {
                    if (!invalidated) {
                        log.warn("Per request is not removed: {}", proxyApiKeyData.getPerRequestKey());
                    }
                })
                .onFailure(error -> log.error("error occurred on invalidating per-request key", error))
                .mapEmpty();
    }

    private Future<ResponseMapping> checkNotDeletingActive(ResponseMapping mapping) {
        if (operation != Operation.DELETE) {
            return Future.succeededFuture(mapping);
        }
        return proxy.getBackgroundJobService().isJobActive(dialResponseId)
                .compose(active -> active
                        ? Future.failedFuture(new HttpException(HttpStatus.CONFLICT, "Cannot delete response while background job is in progress"))
                        : Future.succeededFuture(mapping));
    }

    private ResponseMapping loadMapping() {
        ResponseMapping mapping = proxy.getResponseMappingService().getMapping(dialResponseId);
        if (mapping == null) {
            throw notFoundException(dialResponseId);
        }
        String currentBucket = BucketBuilder.buildInitiatorBucket(context);
        if (!currentBucket.equals(mapping.getInitiatorBucket())) {
            throw new HttpException(HttpStatus.FORBIDDEN, "Access denied");
        }
        return mapping;
    }

    private Future<Void> dispatch(ResponseMapping mapping) {
        Deployment deployment = proxy.getDeploymentService().findDeployment(context, mapping.getDeploymentName());
        if (DeploymentEndpointUtil.resolveServingEndpoint(deployment, InterfaceType.OPENAI_RESPONSES,
                context.getConfig().getTranslators()) == null) {
            return context.respond(HttpStatus.SERVICE_UNAVAILABLE, "Deployment for response_id does not support Responses API")
                    .mapEmpty();
        }
        context.setDeployment(deployment);

        ApiKeyData apiKeyData = context.getApiKeyData();
        if (apiKeyData.isInterceptor()) {
            context.setInitialDeployment(apiKeyData.getInitialDeployment());
            context.setInterceptors(apiKeyData.getInterceptors());
            int nextIndex = apiKeyData.getInterceptorIndex() + 1;
            if (nextIndex < apiKeyData.getInterceptors().size()) {
                return handleInterceptor(nextIndex);
            }
        } else {
            context.setInterceptors(proxy.getDeploymentService().getInterceptors(context, deployment));
            if (context.hasNextInterceptor()) {
                context.setInitialDeployment(deployment.getName());
                return handleInterceptor(0);
            }
        }

        return forwardToUpstream(mapping, deployment);
    }

    private Future<Void> handleInterceptor(int interceptorIndex) {
        return new ResponsesInterceptorController(proxy, context, dialResponseId, operation.pathMapping, interceptorIndex).handle().mapEmpty();
    }

    private Future<Void> forwardToUpstream(ResponseMapping mapping, Deployment deployment) {
        UpstreamRoute upstreamRoute = proxy.getUpstreamRouteProvider()
                .get(deployment,
                        null,
                        dep -> DeploymentEndpointUtil.resolveServingEndpoint(dep, InterfaceType.OPENAI_RESPONSES,
                                context.getConfig().getTranslators()),
                        mapping.getUpstreamKey());
        Upstream upstream = upstreamRoute.next();

        String targetUrl = DeploymentEndpointUtil.resolveResponseItemUri(deployment,
                context.getConfig().getTranslators(), operation.pathMapping, mapping.getUpstreamResponseId(),
                context.getRequest().query());

        ApiKeyData proxyApiKeyData = new ApiKeyData();
        ApiKeyData.initFromContext(proxyApiKeyData, context);
        context.setProxyApiKeyData(proxyApiKeyData);
        proxy.getApiKeyStore().assignPerRequestApiKey(proxyApiKeyData);

        return proxy.getResponsesApiClient().send(targetUrl, operation.method, upstream, proxyApiKeyData.getPerRequestKey())
                .compose(response -> {
                    context.setProxyResponse(response);
                    String contentType = response.getHeader(HttpHeaders.CONTENT_TYPE);
                    if (operation == Operation.GET
                            && Strings.CI.contains(contentType, Proxy.HEADER_CONTENT_TYPE_TEXT_EVENT_STREAM)) {
                        return collectAndForwardStreaming(response, mapping);
                    }
                    return collectAndForward(response, mapping);
                });
    }

    private Future<Void> collectAndForward(HttpClientResponse proxyResponse, ResponseMapping mapping) {
        return proxyResponse.body()
                .compose(body -> {
                    if (proxyResponse.statusCode() != 200) {
                        return sendResponse(proxyResponse, body, null);
                    }
                    return proxy.getTaskExecutor()
                            .submit(() -> rewriteId(body, mapping))
                            .compose(rewrite -> {
                                Buffer rewritten = rewrite.body();
                                ObjectNode tree = rewrite.tree();
                                if (operation == Operation.DELETE) {
                                    return proxy.getTaskExecutor().submit(() -> {
                                        proxy.getResponseMappingService().deleteMapping(dialResponseId);
                                        return null;
                                    }).compose(ignored -> sendResponse(proxyResponse, rewritten, tree));
                                }
                                if (operation == Operation.GET && tree != null) {
                                    ResponsesApiClient.TerminalResult terminalResult = tryParseTerminalResult(tree, rewritten);
                                    if (terminalResult != null) {
                                        proxy.getBackgroundJobService()
                                                .tryComplete(dialResponseId, mapping, terminalResult)
                                                .onFailure(e -> log.warn("Failed to complete background job on GET {}", dialResponseId, e));
                                    }
                                }
                                return sendResponse(proxyResponse, rewritten, tree);
                            });
                });
    }

    /**
     * @param tree {@code body}'s parsed tree (with the id already rewritten), or null when the body was
     *             empty or couldn't be parsed - {@code body} is the original bytes in that case.
     */
    private Future<Void> sendResponse(HttpClientResponse proxyResponse, Buffer body, ObjectNode tree) {
        HttpServerResponse serverResponse = context.getResponse();
        serverResponse.setStatusCode(proxyResponse.statusCode());
        if (operation == Operation.GET) {
            // after setStatusCode: the status fallback reads the client-facing code, still 200 by default before it
            if (tree != null) {
                GenAiTraceAttributes.setFetchResponseAttributes(context, tree, dialResponseId);
            } else {
                GenAiTraceAttributes.setFetchResponseAttributes(context, body, dialResponseId);
            }
        }
        String contentType = proxyResponse.getHeader(HttpHeaders.CONTENT_TYPE);
        if (contentType != null) {
            serverResponse.putHeader(HttpHeaders.CONTENT_TYPE, contentType);
        }
        serverResponse.putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(body.length()));
        return serverResponse.end(body).mapEmpty();
    }

    /**
     * @param tree the (possibly id-rewritten) tree, or null when {@code body} is empty or not a JSON object.
     */
    private record RewriteResult(Buffer body, ObjectNode tree) {
    }

    private RewriteResult rewriteId(Buffer body, ResponseMapping mapping) {
        if (body.length() == 0) {
            return new RewriteResult(body, null);
        }
        JsonNode parsed = JsonUtil.tryParse(body.getBytes());
        if (!(parsed instanceof ObjectNode object)) {
            return new RewriteResult(body, null);
        }
        if (EncryptedContentAffinityUtil.hasConfiguredUpstreams(context.getDeployment())) {
            EncryptedContentAffinityUtil.wrapOutputArray(object.path("output"), mapping.getUpstreamKey());
        }
        JsonNode idNode = object.path("id");
        if (idNode.isTextual() && mapping.getUpstreamResponseId().equals(idNode.asText())) {
            object.put("id", dialResponseId);
        }
        return new RewriteResult(Buffer.buffer(JsonUtil.serialize(object)), object);
    }

    /**
     * @param tree the already-parsed (and id-rewritten) body, read for {@code status}/{@code usage} rather
     *             than reparsing {@code body}; {@code body} itself is only ever a pass-through payload here.
     */
    private ResponsesApiClient.TerminalResult tryParseTerminalResult(ObjectNode tree, Buffer body) {
        try {
            if (!ResponsesApiClient.isTerminalStatus(tree)) {
                return null;
            }
            return new ResponsesApiClient.TerminalResult(body, ResponsesApiClient.extractUsage(tree));
        } catch (Exception e) {
            log.warn("Failed to extract terminal result for background job {} on GET", dialResponseId, e);
            return null;
        }
    }

    private Future<Void> collectAndForwardStreaming(HttpClientResponse proxyResponse, ResponseMapping mapping) {
        CollectResponsesApiOutputAttachmentsFn attachmentsFn = new CollectResponsesApiOutputAttachmentsFn(proxy, context);
        ReplaceResponseIdFn replaceIdFn = new ReplaceResponseIdFn(proxy, context, dialResponseId, mapping.getUpstreamResponseId());
        EncryptedContentWrapFn wrapFn = new EncryptedContentWrapFn(proxy, context, mapping.getUpstreamKey());
        ExtractTerminalResponseFn extractFn = new ExtractTerminalResponseFn(proxy, context);
        BufferingReadStream responseStream = new BufferingReadStream(
                proxyResponse,
                ProxyUtil.contentLength(proxyResponse, 1024),
                new ResponsesSseListener(List.of(wrapFn, attachmentsFn, replaceIdFn, extractFn)));

        HttpServerResponse response = context.getResponse();
        ProxyUtil.handleChunkedResponse(response, proxyResponse);

        return responseStream.pipe()
                .endOnFailure(false)
                .endOnSuccess(false)
                .to(response)
                .onSuccess(ignored -> {
                    // GET only, by the branch that got here: the buffered bytes are the raw upstream frames,
                    // so the id has to come from us. The terminal frame extractFn already kept (if the run
                    // completed) lets tracing consume it directly instead of rescanning the whole buffered
                    // stream - or even reparsing a string built from it - a second time. Null for a run that
                    // failed or was cancelled, so tracing falls back to scanning the raw frames itself.
                    JsonNode assembledTree = extractFn.getAssembledStreamingResponseTree();
                    if (assembledTree != null) {
                        GenAiTraceAttributes.setFetchResponseAttributes(context, assembledTree, dialResponseId);
                    } else {
                        GenAiTraceAttributes.setFetchResponseAttributes(context, responseStream.getContent(), dialResponseId);
                    }
                    responseStream.end(response);
                })
                .onFailure(error -> {
                    response.reset();
                    log.warn("Can't send streaming response to client. Error:", error);
                })
                .mapEmpty();
    }

    @SneakyThrows
    private static HttpException notFoundException(String dialResponseId) {
        ErrorData response = new ErrorData();
        String errorMessage = "Response with id '%s' not found.".formatted(dialResponseId);
        response.getError().setMessage(errorMessage);
        response.getError().setDisplayMessage(errorMessage);
        response.getError().setType("invalid_request_error");
        return new HttpException(HttpStatus.NOT_FOUND, ProxyUtil.MAPPER.writeValueAsString(response));
    }

    @RequiredArgsConstructor
    public enum Operation {
        GET(HttpMethod.GET, InterfacePathMapping.GET_OPENAI_RESPONSES_BY_ID),
        CANCEL(HttpMethod.POST, InterfacePathMapping.POST_OPENAI_RESPONSES_CANCEL),
        DELETE(HttpMethod.DELETE, InterfacePathMapping.DELETE_OPENAI_RESPONSES_BY_ID);

        private final HttpMethod method;
        private final InterfacePathMapping pathMapping;
    }
}
