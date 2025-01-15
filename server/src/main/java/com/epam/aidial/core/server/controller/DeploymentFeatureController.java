package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.service.ResourceNotFoundException;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.stream.BufferingReadStream;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.RequestOptions;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.net.URL;
import java.util.function.Function;

@Slf4j
public class DeploymentFeatureController {

    private final Proxy proxy;
    private final ProxyContext context;

    public DeploymentFeatureController(Proxy proxy, ProxyContext context) {
        this.proxy = proxy;
        this.context = context;
    }

    public Future<?> handle(String deploymentId, Function<Deployment, String> endpointGetter, boolean requireEndpoint) {
        // make sure request.body() called before request.resume()
        return DeploymentController.selectDeployment(context, deploymentId, false, true).map(dep -> {
            String endpoint = endpointGetter.apply(dep);
            context.setDeployment(dep);
            context.getRequest().body()
                    .onSuccess(requestBody -> handleRequestBody(endpoint, requireEndpoint, requestBody))
                    .onFailure(this::handleRequestBodyError);
            return dep;
        }).otherwise(error -> {
            handleRequestError(deploymentId, error);
            return null;
        });
    }

    @SneakyThrows
    private void handleRequestBody(String endpoint, boolean requireEndpoint, Buffer requestBody) {
        context.setRequestBody(requestBody);

        if (endpoint == null) {
            if (requireEndpoint) {
                respond(HttpStatus.FORBIDDEN, "Forbidden deployment");
            } else {
                respond(HttpStatus.OK);
                proxy.getLogStore().save(context);
            }
            return;
        }

        ApiKeyData proxyApiKeyData = new ApiKeyData();
        setupProxyApiKeyData(proxyApiKeyData);

        proxy.getVertx().executeBlocking(() -> {
            proxy.getApiKeyStore().assignPerRequestApiKey(proxyApiKeyData);
            return null;
        }, false)
                .onSuccess(ignore -> sendRequest(endpoint)).onFailure(this::handleError);

    }

    private void handleError(Throwable error) {
        log.error("Error occurred while processing request", error);
        respond(HttpStatus.INTERNAL_SERVER_ERROR, error.getMessage());
    }

    @SneakyThrows
    private void sendRequest(String endpoint) {
        RequestOptions options = new RequestOptions()
                .setAbsoluteURI(new URL(endpoint))
                .setMethod(context.getRequest().method());

        proxy.getClient().request(options)
                .onSuccess(this::handleProxyRequest)
                .onFailure(this::handleProxyConnectionError);
    }

    private void setupProxyApiKeyData(ApiKeyData proxyApiKeyData) {
        context.setProxyApiKeyData(proxyApiKeyData);
        ApiKeyData.initFromContext(proxyApiKeyData, context);
    }

    private void handleRequestError(String deploymentId, Throwable error) {
        if (error instanceof PermissionDeniedException) {
            log.error("Forbidden deployment {}. Project: {}. User sub: {}", deploymentId, context.getProject(), context.getUserSub());
            respond(HttpStatus.FORBIDDEN, error.getMessage());
        } else if (error instanceof ResourceNotFoundException) {
            log.error("Deployment not found {}", deploymentId, error);
            respond(HttpStatus.NOT_FOUND, error.getMessage());
        } else {
            log.error("Failed to handle deployment {}", deploymentId, error);
            respond(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to process deployment: " + deploymentId);
        }
    }

    /**
     * Called when proxy connected to the origin.
     */
    void handleProxyRequest(HttpClientRequest proxyRequest) {
        log.info("Connected to origin. Trace: {}. Span: {}. Project: {}. Deployment: {}. Address: {}",
                context.getTraceId(), context.getSpanId(),
                context.getProject(), context.getDeployment().getName(),
                proxyRequest.connection().remoteAddress());

        HttpServerRequest request = context.getRequest();
        context.setProxyRequest(proxyRequest);
        context.setProxyConnectTimestamp(System.currentTimeMillis());

        Deployment deployment = context.getDeployment();
        MultiMap excludeHeaders = MultiMap.caseInsensitiveMultiMap();
        if (!deployment.isForwardAuthToken()) {
            excludeHeaders.add(HttpHeaders.AUTHORIZATION, "whatever");
        }

        ProxyUtil.copyHeaders(request.headers(), proxyRequest.headers(), excludeHeaders);

        ApiKeyData proxyApiKeyData = context.getProxyApiKeyData();
        proxyRequest.headers().add(Proxy.HEADER_API_KEY, proxyApiKeyData.getPerRequestKey());

        Buffer requestBody = context.getRequestBody();
        proxyRequest.putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(requestBody.length()));
        context.getRequestHeaders().forEach(proxyRequest::putHeader);

        proxyRequest.send(requestBody)
                .onSuccess(this::handleProxyResponse)
                .onFailure(this::handleProxyRequestError);
    }

    /**
     * Called when proxy received the response headers from the origin.
     */
    private void handleProxyResponse(HttpClientResponse proxyResponse) {
        log.info("Received response header from origin: status={}, headers={}", proxyResponse.statusCode(),
                proxyResponse.headers().size());

        BufferingReadStream proxyResponseStream = new BufferingReadStream(proxyResponse,
                ProxyUtil.contentLength(proxyResponse, 1024));

        context.setProxyResponse(proxyResponse);
        context.setResponseStream(proxyResponseStream);

        HttpServerResponse response = context.getResponse();
        response.setChunked(true);
        response.setStatusCode(proxyResponse.statusCode());
        ProxyUtil.copyHeaders(proxyResponse.headers(), response.headers());

        proxyResponseStream.pipe()
                .endOnFailure(false)
                .to(response)
                .onSuccess(ignored -> handleResponse())
                .onFailure(this::handleResponseError);
    }

    /**
     * Called when proxy sent response from the origin to the client.
     */
    private void handleResponse() {
        Buffer proxyResponseBody = context.getResponseStream().getContent();
        context.setResponseBody(proxyResponseBody);
        proxy.getLogStore().save(context);
        finalizeRequest();
    }

    /**
     * Called when proxy failed to receive request body from the client.
     */
    private void handleRequestBodyError(Throwable error) {
        log.warn("Failed to receive client body: {}", error.getMessage());
        respond(HttpStatus.UNPROCESSABLE_ENTITY, "Failed to receive body");
    }

    /**
     * Called when proxy failed to connect to the origin.
     */
    private void handleProxyConnectionError(Throwable error) {
        log.warn("Can't connect to origin: {}", error.getMessage());
        respond(HttpStatus.BAD_GATEWAY, "connection error to origin");
    }

    /**
     * Called when proxy failed to send request to the origin.
     */
    private void handleProxyRequestError(Throwable error) {
        log.warn("Can't send request to origin: {}", error.getMessage());
        respond(HttpStatus.BAD_GATEWAY, "deployment responded with error");
    }

    /**
     * Called when proxy failed to send response to the client.
     */
    private void handleResponseError(Throwable error) {
        log.warn("Can't send response to client: {}", error.getMessage());
        context.getProxyRequest().reset(); // drop connection to stop origin response
        context.getResponse().reset();     // drop connection, so that partial client response won't seem complete
        finalizeRequest();
    }

    private void respond(HttpStatus status, String errorMessage) {
        finalizeRequest();
        context.respond(status, errorMessage);
    }

    private void respond(HttpStatus status) {
        finalizeRequest();
        context.respond(status);
    }

    private void finalizeRequest() {
        ApiKeyData proxyApiKeyData = context.getProxyApiKeyData();
        if (proxyApiKeyData != null) {
            proxy.getApiKeyStore().invalidatePerRequestApiKey(proxyApiKeyData)
                    .onSuccess(invalidated -> {
                        if (!invalidated) {
                            log.warn("Per request is not removed: {}", proxyApiKeyData.getPerRequestKey());
                        }
                    }).onFailure(error -> log.error("error occurred on invalidating per-request key", error));
        }
    }
}
