package com.epam.deltix.dial.proxy.controller;

import com.epam.deltix.dial.proxy.Proxy;
import com.epam.deltix.dial.proxy.ProxyContext;
import com.epam.deltix.dial.proxy.config.Config;
import com.epam.deltix.dial.proxy.config.Route;
import com.epam.deltix.dial.proxy.endpoint.EndpointProvider;
import com.epam.deltix.dial.proxy.endpoint.EndpointRoute;
import com.epam.deltix.dial.proxy.endpoint.RouteEndpointProvider;
import com.epam.deltix.dial.proxy.util.BufferingReadStream;
import com.epam.deltix.dial.proxy.util.HttpStatus;
import com.epam.deltix.dial.proxy.util.ProxyUtil;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
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
            return context.respond(HttpStatus.BAD_GATEWAY, "No route");
        }

        Route.Response response = route.getResponse();
        if (response == null) {
            EndpointProvider endpointProvider = new RouteEndpointProvider(route);
            EndpointRoute endpointRoute = proxy.getEndpointBalancer().balance(endpointProvider);

            if (!endpointRoute.hasNext()) {
                return context.respond(HttpStatus.BAD_GATEWAY, "No route");
            }

            context.setEndpointProvider(endpointProvider);
            context.setEndpointRoute(endpointRoute);
        } else {
            context.getResponse().setStatusCode(response.getStatus());
            context.setProxyResponseBody(Buffer.buffer(response.getBody()));
        }

        return context.getRequest().body()
                .onSuccess(this::handleRequestBody)
                .onFailure(this::handleRequestBodyError);
    }

    @SneakyThrows
    private Future<?> sendRequest() {
        EndpointRoute route = context.getEndpointRoute();
        HttpServerRequest request = context.getRequest();

        if (!route.hasNext()) {
            return context.respond(HttpStatus.BAD_GATEWAY, "No route");
        }

        String endpoint = route.next();
        RequestOptions options = new RequestOptions()
                .setAbsoluteURI(new URL(endpoint))
                .setURI(request.uri())
                .setMethod(request.method());

        return proxy.getClient().request(options)
                .onSuccess(this::handleProxyRequest)
                .onFailure(this::handleProxyConnectionError);
    }

    private void handleRequestBody(Buffer requestBody) {
        context.setProxyRequestBody(requestBody);

        if (context.getProxyResponseBody() == null) {
            sendRequest();
        } else {
            context.getResponse().send(context.getProxyResponseBody());
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

        EndpointProvider endpointProvider = context.getEndpointProvider();
        EndpointRoute endpoint = context.getEndpointRoute();

        ProxyUtil.copyHeaders(request.headers(), proxyRequest.headers());

        String endpointKey = endpointProvider.getEndpoints().get(endpoint.get());
        proxyRequest.headers().set(Proxy.HEADER_API_KEY, endpointKey);

        Buffer proxyRequestBody = context.getProxyRequestBody();
        proxyRequest.headers().set(HttpHeaders.CONTENT_LENGTH, Integer.toString(proxyRequestBody.length()));

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
        context.setProxyResponseStream(proxyResponseStream);

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
        Buffer proxyResponseBody = context.getProxyResponseStream().getContent();
        context.setProxyResponseBody(proxyResponseBody);
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