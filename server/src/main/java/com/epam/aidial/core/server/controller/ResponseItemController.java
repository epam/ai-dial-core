package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Deployment;
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
import com.epam.aidial.core.server.data.ErrorData;
import com.epam.aidial.core.server.data.ResponseMapping;
import com.epam.aidial.core.server.function.CollectResponsesApiOutputAttachmentsFn;
import com.epam.aidial.core.server.function.ReplaceResponseIdFn;
import com.epam.aidial.core.server.service.ResponsesApiClient;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.epam.aidial.core.server.util.BucketBuilder;
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

    private static final String OPENAI_RESPONSES_BASE_PATH = "/openai/v1/responses";

    private final Proxy proxy;
    private final ProxyContext context;
    private final Operation operation;

    /**
     * New flow: base_url + /openai/v1/responses. Legacy flow: the verbatim responsesEndpoint.
     */
    private static String responsesBase(Deployment deployment) {
        String baseUrl = deployment.getInterfaceBaseUrl(InterfaceType.OPENAI_RESPONSES);
        return baseUrl != null ? baseUrl + OPENAI_RESPONSES_BASE_PATH : deployment.getResponsesEndpoint();
    }

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
                .compose(this::checkNotActive)
                .compose(this::forwardToUpstream)
                .onFailure(error -> {
                    if (!context.getResponse().ended()) {
                        context.respond(error, "Failed to process response operation");
                    }
                });
    }

    private Future<ResponseMapping> checkNotActive(ResponseMapping mapping) {
        if (operation != Operation.DELETE) {
            return Future.succeededFuture(mapping);
        }
        return proxy.getBackgroundJobService().isJobActive(context.getDialResponseId())
                .compose(active -> active
                        ? Future.failedFuture(new HttpException(HttpStatus.CONFLICT, "Cannot delete response while background job is in progress"))
                        : Future.succeededFuture(mapping));
    }

    private ResponseMapping loadMapping() {
        ResponseMapping mapping = proxy.getResponseMappingService().getMapping(context.getDialResponseId());
        if (mapping == null) {
            throw notFoundException(context.getDialResponseId());
        }
        String currentBucket = BucketBuilder.buildInitiatorBucket(context);
        if (!currentBucket.equals(mapping.getInitiatorBucket())) {
            throw new HttpException(HttpStatus.FORBIDDEN, "Access denied");
        }
        return mapping;
    }

    private Future<Void> forwardToUpstream(ResponseMapping mapping) {
        Deployment deployment = proxy.getDeploymentService().findDeployment(context, mapping.getDeploymentName());
        if (!deployment.supportsInterface(InterfaceType.OPENAI_RESPONSES)) {
            return context.respond(HttpStatus.SERVICE_UNAVAILABLE, "Deployment for response_id does not support Responses API")
                    .mapEmpty();
        }
        UpstreamRoute upstreamRoute = proxy.getUpstreamRouteProvider()
                .get(deployment,
                        null,
                        dep -> dep.resolveEndpoint(InterfaceType.OPENAI_RESPONSES),
                        mapping.getUpstreamKey()
                );
        Upstream upstream = upstreamRoute.next();

        String query = context.getRequest().query();
        String targetUrl = responsesBase(deployment) + "/" + mapping.getUpstreamResponseId() + operation.suffix
                + (query != null ? "?" + query : "");

        return proxy.getResponsesApiClient().send(targetUrl, operation.method, upstream)
                .compose(response -> {
                    String contentType = response.getHeader(HttpHeaders.CONTENT_TYPE);
                    if (operation == Operation.GET
                            && Strings.CI.contains(contentType, Proxy.HEADER_CONTENT_TYPE_TEXT_EVENT_STREAM)) {
                        return collectAndForwardStreaming(response, mapping.getUpstreamResponseId());
                    }
                    return collectAndForward(response, mapping);
                });
    }

    private Future<Void> collectAndForward(HttpClientResponse proxyResponse, ResponseMapping mapping) {
        return proxyResponse.body()
                .compose(body -> {
                    if (proxyResponse.statusCode() != 200) {
                        return sendResponse(proxyResponse, body);
                    }
                    return proxy.getTaskExecutor()
                            .submit(() -> rewriteId(body, mapping.getUpstreamResponseId()))
                            .compose(rewritten -> {
                                if (operation == Operation.DELETE) {
                                    return proxy.getTaskExecutor().submit(() -> {
                                        proxy.getResponseMappingService().deleteMapping(context.getDialResponseId());
                                        return null;
                                    }).compose(ignored -> sendResponse(proxyResponse, rewritten));
                                }
                                if (operation == Operation.GET) {
                                    ResponsesApiClient.TerminalResult terminalResult = tryParseTerminalResult(rewritten);
                                    proxy.getBackgroundJobService()
                                            .tryCompleteOnGet(context.getDialResponseId(), mapping, terminalResult)
                                            .onFailure(e -> log.warn("Failed to complete background job on GET {}", context.getDialResponseId(), e));
                                }
                                return sendResponse(proxyResponse, rewritten);
                            });
                });
    }

    private Future<Void> sendResponse(HttpClientResponse proxyResponse, Buffer body) {
        HttpServerResponse serverResponse = context.getResponse();
        serverResponse.setStatusCode(proxyResponse.statusCode());
        String contentType = proxyResponse.getHeader(HttpHeaders.CONTENT_TYPE);
        if (contentType != null) {
            serverResponse.putHeader(HttpHeaders.CONTENT_TYPE, contentType);
        }
        serverResponse.putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(body.length()));
        return serverResponse.end(body).mapEmpty();
    }

    private Buffer rewriteId(Buffer body, String upstreamResponseId) {
        if (body.length() == 0) {
            return body;
        }
        JsonNode tree = JsonUtil.tryParse(body.getBytes());
        if (!(tree instanceof ObjectNode object)) {
            return body;
        }
        JsonNode idNode = object.path("id");
        if (idNode.isTextual() && upstreamResponseId.equals(idNode.asText())) {
            object.put("id", context.getDialResponseId());
        }
        return Buffer.buffer(JsonUtil.serialize(object));
    }

    private ResponsesApiClient.TerminalResult tryParseTerminalResult(Buffer body) {
        try {
            return ResponsesApiClient.parseTerminalBody(body);
        } catch (Exception e) {
            log.warn("Failed to extract terminal result for background job {} on GET", context.getDialResponseId(), e);
            return null;
        }
    }

    private Future<Void> collectAndForwardStreaming(HttpClientResponse proxyResponse, String upstreamResponseId) {
        CollectResponsesApiOutputAttachmentsFn attachmentsFn = new CollectResponsesApiOutputAttachmentsFn(proxy, context);
        ReplaceResponseIdFn replaceIdFn = new ReplaceResponseIdFn(proxy, context, upstreamResponseId);
        BufferingReadStream responseStream = new BufferingReadStream(
                proxyResponse,
                ProxyUtil.contentLength(proxyResponse, 1024),
                new ResponsesSseListener(List.of(attachmentsFn, replaceIdFn)));

        HttpServerResponse response = context.getResponse();
        ProxyUtil.handleChunkedResponse(response, proxyResponse);

        return responseStream.pipe()
                .endOnFailure(false)
                .endOnSuccess(false)
                .to(response)
                .onSuccess(ignored -> responseStream.end(response))
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
        GET(HttpMethod.GET, ""),
        CANCEL(HttpMethod.POST, "/cancel"),
        DELETE(HttpMethod.DELETE, "");

        private final HttpMethod method;
        private final String suffix;
    }
}