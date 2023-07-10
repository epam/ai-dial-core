package com.epam.deltix.dial.proxy;

import com.epam.deltix.dial.proxy.config.Config;
import com.epam.deltix.dial.proxy.config.ConfigStore;
import com.epam.deltix.dial.proxy.config.Key;
import com.epam.deltix.dial.proxy.controller.Controller;
import com.epam.deltix.dial.proxy.controller.ControllerSelector;
import com.epam.deltix.dial.proxy.endpoint.EndpointBalancer;
import com.epam.deltix.dial.proxy.limiter.RateLimiter;
import com.epam.deltix.dial.proxy.log.LogStore;
import com.epam.deltix.dial.proxy.util.HttpStatus;
import com.epam.deltix.dial.proxy.util.ProxyUtil;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.Objects;

@Slf4j
@Getter
@RequiredArgsConstructor
public class Proxy implements Handler<HttpServerRequest> {

    public static final String HEADER_API_KEY = "API-KEY";
    public static final String HEADER_USER = "X-USER";
    public static final String HEADER_JOB_TITLE = "X-JOB-TITLE";
    public static final String HEADER_CORRELATION_ID = "X-CORRELATION-ID";
    public static final String HEADER_UPSTREAM_ENDPOINT = "X-UPSTREAM-ENDPOINT";
    public static final String HEADER_UPSTREAM_KEY = "X-UPSTREAM-KEY";
    public static final String HEADER_UPSTREAM_ATTEMPTS = "X-UPSTREAM-ATTEMPTS";
    public static final String HEADER_CONTENT_TYPE_APPLICATION_JSON = "application/json";

    public static final int REQUEST_BODY_MAX_SIZE = 16 * 1024 * 1024;

    private final HttpClient client;
    private final ConfigStore configStore;
    private final LogStore logStore;
    private final RateLimiter rateLimiter;
    private final EndpointBalancer endpointBalancer;
    private final RSAPublicKey publicKey;

    @Override
    public void handle(HttpServerRequest request) {
        try {
            Future<?> future = handleRequest(request);
            Objects.requireNonNull(future);
        } catch (Throwable e) {
            log.warn("Can't handle request: {}", e.getMessage());
            respond(request, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Called when proxy received the request headers from a client.
     */
    private Future<?> handleRequest(HttpServerRequest request) throws Exception {
        if (request.version() != HttpVersion.HTTP_1_1) {
            return respond(request, HttpStatus.HTTP_VERSION_NOT_SUPPORTED);
        }

        if (request.method() != HttpMethod.GET && request.method() != HttpMethod.POST) {
            return respond(request, HttpStatus.METHOD_NOT_ALLOWED);
        }

        // not only the case, Content-Length can be missing when Transfer-Encoding: chunked
        if (ProxyUtil.contentLength(request, 1024) > REQUEST_BODY_MAX_SIZE) {
            return respond(request, HttpStatus.REQUEST_ENTITY_TOO_LARGE, "Request body is too large");
        }

        String apiKey = request.headers().get(HEADER_API_KEY);
        if (apiKey == null) {
            return respond(request, HttpStatus.UNAUTHORIZED, "Missing API-KEY header");
        }

        Config config = configStore.load();
        Key key = config.getKeys().get(apiKey);

        if (key == null) {
            return respond(request, HttpStatus.UNAUTHORIZED, "Unknown api key");
        }


        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null && key.isUserAuth()) {
            return respond(request, HttpStatus.UNAUTHORIZED, "Missing Authorization header");
        }

        List<String> userRoles;
        try {
            userRoles = ProxyUtil.extractUserRolesFromAuthHeader(authorization, publicKey);
        } catch (Throwable e) {
            return respond(request, HttpStatus.UNAUTHORIZED, "Bad Authorization header");
        }

        ProxyContext context = new ProxyContext(config, request, key, userRoles);
        Controller controller = ControllerSelector.select(this, context);

        return controller.handle();
    }

    private Future<?> respond(HttpServerRequest request, HttpStatus status) {
        return request.response().setStatusCode(status.getCode()).end();
    }

    private Future<?> respond(HttpServerRequest request, HttpStatus status, String body) {
        return request.response().setStatusCode(status.getCode()).end(body);
    }
}
