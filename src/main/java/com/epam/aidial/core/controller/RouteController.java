package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.upstream.RouteEndpointProvider;
import com.epam.aidial.core.upstream.UpstreamProvider;
import com.epam.aidial.core.upstream.UpstreamRoute;
import com.epam.aidial.core.util.BufferingReadStream;
import com.epam.aidial.core.util.HttpStatus;
import com.epam.aidial.core.util.ProxyUtil;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.RequestOptions;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.net.URL;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@RequiredArgsConstructor
public class RouteController implements Controller {

    private final Proxy proxy;
    private final ProxyContext context;

    @Override
    public Future<?> handle() {
        Route route = selectRoute();
        if (route == null) {
            log.warn("RouteController can't find a route to proceed the request: {}", getRequestUri());
            context.respond(HttpStatus.BAD_GATEWAY, "No route");
            return Future.succeededFuture();
        }

        Route.Response response = route.getResponse();
        if (response == null) {
            UpstreamProvider upstreamProvider = new RouteEndpointProvider(route);
            UpstreamRoute upstreamRoute = proxy.getUpstreamBalancer().balance(upstreamProvider);

            if (!upstreamRoute.hasNext()) {
                log.warn("RouteController can't find a upstream route to proceed the request: {}", getRequestUri());
                context.respond(HttpStatus.BAD_GATEWAY, "No route");
                return Future.succeededFuture();
            }

            context.setUpstreamRoute(upstreamRoute);
        } else {
            context.getResponse().setStatusCode(response.getStatus());
            context.setResponseBody(Buffer.buffer(response.getBody()));
        }

        context.getRequest().body()
                .onSuccess(this::handleRequestBody)
                .onFailure(this::handleRequestBodyError);
        return Future.succeededFuture();
    }

    String getRequestUri() {
        HttpServerRequest request = context.getRequest();
        return request.uri();
    }

    @SneakyThrows
    private Future<?> sendRequest() {
        UpstreamRoute route = context.getUpstreamRoute();
        HttpServerRequest request = context.getRequest();

        if (!route.hasNext()) {
            log.warn("RouteController can't find a upstream route to proceed the request: {}", getRequestUri());
            return context.respond(HttpStatus.BAD_GATEWAY, "No route");
        }

        Upstream upstream = route.next();
        RequestOptions options = new RequestOptions()
                .setAbsoluteURI(new URL(upstream.getEndpoint()))
                .setMethod(request.method());

        return proxy.getClient().request(options)
                .onSuccess(this::handleProxyRequest)
                .onFailure(this::handleProxyConnectionError);
    }

    private void handleRequestBody(Buffer requestBody) {
        context.setRequestBody(requestBody);

        if (context.getResponseBody() == null) {
            sendRequest();
        } else {
            context.getResponse().send(context.getResponseBody());
            proxy.getLogStore().save(context);
        }
    }

    /**
     * Called when proxy connected to the origin.
     */
    private void handleProxyRequest(HttpClientRequest proxyRequest) {
        log.info("Connected to origin: {}", proxyRequest.connection().remoteAddress());

        HttpServerRequest request = context.getRequest();
        context.setProxyRequest(proxyRequest);

        Upstream upstream = context.getUpstreamRoute().get();
        ProxyUtil.copyHeaders(request.headers(), proxyRequest.headers());
        proxyRequest.putHeader(Proxy.HEADER_API_KEY, upstream.getKey());

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
        sendRequest(); // try next
    }

    /**
     * Called when proxy failed to send response to the client.
     */
    private void handleResponseError(Throwable error) {
        log.warn("Can't send response to client: {}", error.getMessage());
        context.getProxyRequest().reset(); // drop connection to stop origin response
        context.getResponse().reset();     // drop connection, so that partial client response won't seem complete
    }

    private Route selectRoute() {
        Config config = context.getConfig();
        HttpServerRequest request = context.getRequest();
        String uri = request.uri();

        for (Route route : config.getRoutes().values()) {
            List<Pattern> paths = route.getPaths();
            Set<HttpMethod> methods = route.getMethods();

            if (!methods.isEmpty() && !methods.contains(request.method())) {
                continue;
            }

            if (paths.isEmpty()) {
                return route;
            }

            for (Pattern path : route.getPaths()) {
                if (path.matcher(uri).matches()) {
                    return route;
                }
            }
        }

        return null;
    }
}