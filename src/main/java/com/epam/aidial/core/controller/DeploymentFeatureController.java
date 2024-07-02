package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.service.CustomApplicationService;
import com.epam.aidial.core.service.PermissionDeniedException;
import com.epam.aidial.core.service.ResourceNotFoundException;
import com.epam.aidial.core.util.BufferingReadStream;
import com.epam.aidial.core.util.HttpStatus;
import com.epam.aidial.core.util.ProxyUtil;
import io.vertx.core.Future;
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
    private final CustomApplicationService applicationService;

    public DeploymentFeatureController(Proxy proxy, ProxyContext context) {
        this.proxy = proxy;
        this.context = context;
        this.applicationService = proxy.getCustomApplicationService();
    }

    public Future<?> handle(String deploymentId, Function<Deployment, String> endpointGetter, boolean requireEndpoint) {
        Deployment deployment = context.getConfig().selectDeployment(deploymentId);

        Future<Deployment> deploymentFuture;
        if (deployment != null) {
            if (!DeploymentController.hasAccessByUserRoles(context, deployment)) {
                deploymentFuture = Future.failedFuture(new PermissionDeniedException("Forbidden deployment: " + deploymentId));
            } else {
                deploymentFuture = Future.succeededFuture(deployment);
            }
        } else {
            deploymentFuture = proxy.getVertx().executeBlocking(() ->
                    applicationService.getCustomApplication(deploymentId, context), false);
        }

        deploymentFuture.map(dep -> {
            String endpoint = endpointGetter.apply(dep);
            context.setDeployment(dep);
            context.getRequest().body()
                    .onSuccess(requestBody -> this.handleRequestBody(endpoint, requireEndpoint, requestBody))
                    .onFailure(this::handleRequestBodyError);
            return dep;
        }).otherwise(error -> {
            handleRequestError(deploymentId, error);
            return null;
        });

        return Future.succeededFuture();
    }

    @SneakyThrows
    private void handleRequestBody(String endpoint, boolean requireEndpoint, Buffer requestBody) {
        context.setRequestBody(requestBody);

        if (endpoint == null) {
            if (requireEndpoint) {
                context.respond(HttpStatus.FORBIDDEN, "Forbidden deployment");
            } else {
                context.respond(HttpStatus.OK);
                proxy.getLogStore().save(context);
            }
            return;
        }

        RequestOptions options = new RequestOptions()
                .setAbsoluteURI(new URL(endpoint))
                .setMethod(context.getRequest().method());

        proxy.getClient().request(options)
                .onSuccess(this::handleProxyRequest)
                .onFailure(this::handleProxyConnectionError);
    }

    private void handleRequestError(String deploymentId, Throwable error) {
        if (error instanceof PermissionDeniedException) {
            log.error("Forbidden deployment {}. Key: {}. User sub: {}", deploymentId, context.getProject(), context.getUserSub());
            context.respond(HttpStatus.FORBIDDEN, error.getMessage());
        } else if (error instanceof ResourceNotFoundException) {
            log.error("Deployment not found {}", deploymentId, error);
            context.respond(HttpStatus.NOT_FOUND, error.getMessage());
        } else {
            log.error("Failed to handle deployment {}", deploymentId, error);
            context.respond(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to process deployment: " + deploymentId);
        }
    }

    /**
     * Called when proxy connected to the origin.
     */
    private void handleProxyRequest(HttpClientRequest proxyRequest) {
        log.info("Connected to origin: {}", proxyRequest.connection().remoteAddress());

        HttpServerRequest request = context.getRequest();
        context.setProxyRequest(proxyRequest);

        ProxyUtil.copyHeaders(request.headers(), proxyRequest.headers());

        Buffer proxyRequestBody = context.getRequestBody();
        proxyRequest.putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(proxyRequestBody.length()));

        proxyRequest.send(proxyRequestBody)
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
    }

    /**
     * Called when proxy failed to receive request body from the client.
     */
    private void handleRequestBodyError(Throwable error) {
        log.warn("Failed to receive client body: {}", error.getMessage());
        context.respond(HttpStatus.UNPROCESSABLE_ENTITY, "Failed to receive body");
    }

    /**
     * Called when proxy failed to connect to the origin.
     */
    private void handleProxyConnectionError(Throwable error) {
        log.warn("Can't connect to origin: {}", error.getMessage());
        context.respond(HttpStatus.BAD_GATEWAY, "connection error to origin");
    }

    /**
     * Called when proxy failed to send request to the origin.
     */
    private void handleProxyRequestError(Throwable error) {
        log.warn("Can't send request to origin: {}", error.getMessage());
        context.respond(HttpStatus.BAD_GATEWAY, "deployment responded with error");
    }

    /**
     * Called when proxy failed to send response to the client.
     */
    private void handleResponseError(Throwable error) {
        log.warn("Can't send response to client: {}", error.getMessage());
        context.getProxyRequest().reset(); // drop connection to stop origin response
        context.getResponse().reset();     // drop connection, so that partial client response won't seem complete
    }
}
