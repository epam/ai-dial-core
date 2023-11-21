package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.Assistant;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.util.BufferingReadStream;
import com.epam.aidial.core.util.HttpStatus;
import com.epam.aidial.core.util.ProxyUtil;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.RequestOptions;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.net.URL;

import static com.epam.aidial.core.controller.DeploymentPostController.ASSISTANT;

@Slf4j
@RequiredArgsConstructor
public class RateResponseController {

    private final Proxy proxy;
    private final ProxyContext context;

    public void handle(String deploymentId) {
        Deployment deployment = getDeployment(deploymentId);

        if (deployment == null || !DeploymentController.hasAccessByUserRoles(context, deployment)) {
            context.respond(HttpStatus.FORBIDDEN, "Forbidden deployment");
            return;
        }

        if (deployment.getRateEndpoint() == null) {
            context.respond(HttpStatus.OK);
            proxy.getLogStore().save(context);
        } else {
            context.setDeployment(deployment);
            context.getRequest().body()
                    .onSuccess(this::handleRequestBody)
                    .onFailure(this::handleRequestBodyError);
        }
    }

    private Deployment getDeployment(String id) {
        Config config = context.getConfig();
        Deployment deployment = config.getApplications().get(id);
        if (deployment != null) {
            return deployment;
        }
        deployment = config.getModels().get(id);
        if (deployment != null) {
            return deployment;
        }
        if (ASSISTANT.equals(id)) {
            Assistant assistant = new Assistant();
            assistant.setName(ASSISTANT);
            assistant.setRateEndpoint(config.getAssistant().getRateEndpoint());
            return assistant;
        }
        return null;
    }

    @SneakyThrows
    private void sendRequest() {
        RequestOptions options = new RequestOptions()
                .setAbsoluteURI(new URL(context.getDeployment().getRateEndpoint()))
                .setMethod(context.getRequest().method());

        proxy.getClient().request(options)
                .onSuccess(this::handleProxyRequest)
                .onFailure(this::handleProxyConnectionError);
    }

    private void handleRequestBody(Buffer requestBody) {
        context.setRequestBody(requestBody);
        sendRequest();
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

        if (proxyResponse.statusCode() == HttpStatus.TOO_MANY_REQUESTS.getCode()) {
            sendRequest(); // try next
            return;
        }

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
        sendRequest(); // try next
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
