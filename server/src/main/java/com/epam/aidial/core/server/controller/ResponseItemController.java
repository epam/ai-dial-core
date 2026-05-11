package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ResponseMapping;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class ResponseItemController implements Controller {

    private final Proxy proxy;
    private final ProxyContext context;
    private final String dialResponseId;
    private final Operation operation;

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
        ResponseMapping mapping = proxy.getResponseMappingService().getMapping(context, dialResponseId);
        if (mapping == null) {
            throw notFoundException();
        }
        return mapping;
    }

    private Future<Void> forwardToUpstream(ResponseMapping mapping) {
        Deployment deployment = proxy.getDeploymentService().findDeployment(context, mapping.getDeploymentName());
        if (deployment.getResponsesEndpoint() == null) {
            return context.respond(HttpStatus.SERVICE_UNAVAILABLE, "Deployment for response_id does not support Responses API")
                    .mapEmpty();
        }
        UpstreamRoute upstreamRoute = proxy.getUpstreamRouteProvider().get(deployment, null);
        Upstream upstream = upstreamRoute.get(mapping.getUpstreamKey());
        if (upstream == null) {
            return context.respond(HttpStatus.SERVICE_UNAVAILABLE, "Upstream for response_id is no longer available")
                    .mapEmpty();
        }

        String targetUrl = deployment.getResponsesEndpoint() + "/" + mapping.getUpstreamResponseId() + operation.suffix;
        RequestOptions options = new RequestOptions()
                .setAbsoluteURI(targetUrl)
                .setMethod(operation.method)
                .setConnectTimeout(proxy.getClientOptions().getConnectTimeout())
                .setIdleTimeout(proxy.getClientOptions().getIdleTimeout());

        return proxy.getClient().request(options)
                .compose(request -> {
                    request.putHeader(Proxy.HEADER_UPSTREAM_KEY, upstream.getKey())
                            .putHeader(Proxy.HEADER_UPSTREAM_ENDPOINT, upstream.getResponsesEndpoint())
                            .putHeader(Proxy.HEADER_UPSTREAM_EXTRA_DATA, upstream.getExtraData());

                    return request.send();
                })
                .compose(response -> collectAndForward(response, mapping.getUpstreamResponseId()));
    }

    private Future<Void> collectAndForward(HttpClientResponse proxyResponse, String upstreamResponseId) {
        return proxyResponse.body()
                .compose(body -> rewriteId(body, upstreamResponseId))
                .compose(rewritten -> {
                    if (operation.shouldDeleteMapping(proxyResponse.statusCode())) {
                        return proxy.getTaskExecutor().submit(() -> {
                            proxy.getResponseMappingService().deleteMapping(context, dialResponseId);
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
            try {
                JsonNode root = ProxyUtil.MAPPER.readTree(body.getBytes());
                if (!root.isObject()) {
                    return body;
                }
                ObjectNode object = (ObjectNode) root;
                JsonNode idNode = object.get("id");
                if (idNode != null && !idNode.isNull() && upstreamResponseId.equals(idNode.asText())) {
                    object.put("id", dialResponseId);
                }
                return Buffer.buffer(ProxyUtil.MAPPER.writeValueAsBytes(object));
            } catch (Exception e) {
                log.warn("Failed to rewrite id in response", e);
                return body;
            }
        });
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
