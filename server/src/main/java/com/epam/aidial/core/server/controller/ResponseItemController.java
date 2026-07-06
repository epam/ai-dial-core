package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ResponseMapping;
import com.epam.aidial.core.server.function.CollectResponsesApiOutputAttachmentsFn;
import com.epam.aidial.core.server.function.ReplaceResponseIdFn;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.server.util.JsonUtil;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.UpstreamExtraDataMerger;
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
import io.vertx.core.http.RequestOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Strings;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class ResponseItemController implements Controller {

    private static final String OPENAI_RESPONSES_BASE_PATH = "/openai/v1/responses";

    private final Proxy proxy;
    private final ProxyContext context;
    private final String dialResponseId;
    private final Operation operation;

    /**
     * New flow: base_url + /openai/v1/responses. Legacy flow: the verbatim responsesEndpoint.
     */
    private static String responsesBase(Deployment deployment) {
        String baseUrl = deployment.getInterfaceBaseUrl(InterfaceType.OPENAI_RESPONSES);
        return baseUrl != null ? baseUrl + OPENAI_RESPONSES_BASE_PATH : deployment.getResponsesEndpoint();
    }

    @Override
    public Future<?> handle() {
        return proxy.getTaskExecutor().submit(this::loadMapping)
                .compose(this::forwardToUpstream)
                .onFailure(error -> {
                    if (!context.getResponse().ended()) {
                        context.respond(error, "Failed to process response operation");
                    }
                });
    }

    private ResponseMapping loadMapping() {
        ResponseMapping mapping = proxy.getResponseMappingService().getMapping(dialResponseId);
        if (mapping == null) {
            throw notFoundException();
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
        RequestOptions options = new RequestOptions()
                .setAbsoluteURI(targetUrl)
                .setMethod(operation.method)
                .setConnectTimeout(proxy.getClientOptions().getConnectTimeout())
                .setIdleTimeout(proxy.getClientOptions().getIdleTimeout());

        return proxy.getClient().request(options)
                .compose(request -> {
                    request.putHeader(Proxy.HEADER_UPSTREAM_KEY, upstream.getKey())
                            .putHeader(Proxy.HEADER_UPSTREAM_ENDPOINT, upstream.getResponsesEndpoint())
                            .putHeader(Proxy.HEADER_UPSTREAM_EXTRA_DATA, UpstreamExtraDataMerger.merge(upstream));

                    return request.send();
                })
                .compose(response -> {
                    String contentType = response.getHeader(HttpHeaders.CONTENT_TYPE);
                    if (operation == Operation.GET
                            && Strings.CI.contains(contentType, Proxy.HEADER_CONTENT_TYPE_TEXT_EVENT_STREAM)) {
                        return collectAndForwardStreaming(response, mapping.getUpstreamResponseId());
                    }
                    return collectAndForward(response, mapping.getUpstreamResponseId());
                });
    }

    private Future<Void> collectAndForward(HttpClientResponse proxyResponse, String upstreamResponseId) {
        return proxyResponse.body()
                .compose(body -> rewriteId(body, upstreamResponseId))
                .compose(rewritten -> {
                    if (operation.shouldDeleteMapping(proxyResponse.statusCode())) {
                        return proxy.getTaskExecutor().submit(() -> {
                            proxy.getResponseMappingService().deleteMapping(dialResponseId);
                            return rewritten;
                        });
                    }
                    return Future.succeededFuture(rewritten);
                })
                .compose(rewritten -> {
                    context.getResponse().setStatusCode(proxyResponse.statusCode());
                    String contentType = proxyResponse.getHeader(HttpHeaders.CONTENT_TYPE);
                    if (contentType != null) {
                        context.getResponse().putHeader(HttpHeaders.CONTENT_TYPE, contentType);
                    }
                    context.getResponse().putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(rewritten.length()));
                    return context.getResponse().end(rewritten);
                })
                .mapEmpty();
    }

    private Future<Buffer> rewriteId(Buffer body, String upstreamResponseId) {
        if (body.length() == 0) {
            return Future.succeededFuture(body);
        }
        return proxy.getTaskExecutor().submit(() -> {
            JsonNode tree = JsonUtil.tryParse(body.getBytes());
            if (tree.isObject() && tree instanceof ObjectNode object) {
                JsonNode idNode = object.path("id");
                if (!idNode.isNull() && upstreamResponseId.equals(idNode.asText())) {
                    object.put("id", dialResponseId);
                }
                return Buffer.buffer(JsonUtil.serialize(object));
            }

            return body;
        });
    }

    private Future<Void> collectAndForwardStreaming(HttpClientResponse proxyResponse, String upstreamResponseId) {
        CollectResponsesApiOutputAttachmentsFn attachmentsFn = new CollectResponsesApiOutputAttachmentsFn(proxy, context);
        ReplaceResponseIdFn replaceIdFn = new ReplaceResponseIdFn(proxy, context, dialResponseId, upstreamResponseId);
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

    private static HttpException notFoundException() {
        return new HttpException(
                HttpStatus.NOT_FOUND,
                "{\"error\":{\"message\":\"Unknown or expired response_id\","
                        + "\"type\":\"invalid_request_error\",\"param\":\"response_id\",\"code\":null}}");
    }

    public enum Operation {
        GET(HttpMethod.GET, "", false),
        CANCEL(HttpMethod.POST, "/cancel", false),
        DELETE(HttpMethod.DELETE, "", true);

        private final HttpMethod method;
        private final String suffix;
        private final boolean deleteMappingOnSuccess;

        Operation(HttpMethod method, String suffix, boolean deleteMappingOnSuccess) {
            this.method = method;
            this.suffix = suffix;
            this.deleteMappingOnSuccess = deleteMappingOnSuccess;
        }

        private boolean shouldDeleteMapping(int status) {
            return deleteMappingOnSuccess && status == 200;
        }
    }
}
