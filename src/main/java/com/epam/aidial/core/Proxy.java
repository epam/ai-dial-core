package com.epam.aidial.core;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.ConfigStore;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.UserAuth;
import com.epam.aidial.core.controller.Controller;
import com.epam.aidial.core.controller.ControllerSelector;
import com.epam.aidial.core.limiter.RateLimiter;
import com.epam.aidial.core.log.LogStore;
import com.epam.aidial.core.security.ExtractedClaims;
import com.epam.aidial.core.security.IdentityProvider;
import com.epam.aidial.core.upstream.UpstreamBalancer;
import com.epam.aidial.core.util.HttpStatus;
import com.epam.aidial.core.util.ProxyUtil;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpVersion;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@Slf4j
@Getter
@RequiredArgsConstructor
public class Proxy implements Handler<HttpServerRequest> {

    public static final String HEALTH_CHECK_PATH = "/health";

    public static final String HEADER_API_KEY = "API-KEY";
    public static final String HEADER_JOB_TITLE = "X-JOB-TITLE";
    public static final String HEADER_CONVERSATION_ID = "X-CONVERSATION-ID";
    public static final String HEADER_UPSTREAM_ENDPOINT = "X-UPSTREAM-ENDPOINT";
    public static final String HEADER_UPSTREAM_KEY = "X-UPSTREAM-KEY";
    public static final String HEADER_UPSTREAM_ATTEMPTS = "X-UPSTREAM-ATTEMPTS";
    public static final String HEADER_CONTENT_TYPE_APPLICATION_JSON = "application/json";

    public static final int REQUEST_BODY_MAX_SIZE = 16 * 1024 * 1024;

    private final HttpClient client;
    private final ConfigStore configStore;
    private final LogStore logStore;
    private final RateLimiter rateLimiter;
    private final UpstreamBalancer upstreamBalancer;
    private final IdentityProvider identityProvider;

    @Override
    public void handle(HttpServerRequest request) {
        try {
            handleRequest(request);
        } catch (Throwable e) {
            handleError(e, request);
        }
    }

    private void handleError(Throwable error, HttpServerRequest request) {
        log.warn("Can't handle request: {}", error.getMessage());
        respond(request, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * Called when proxy received the request headers from a client.
     */
    private void handleRequest(HttpServerRequest request) throws Exception {
        if (request.version() != HttpVersion.HTTP_1_1) {
            respond(request, HttpStatus.HTTP_VERSION_NOT_SUPPORTED);
            return;
        }

        if (request.method() != HttpMethod.GET && request.method() != HttpMethod.POST) {
            respond(request, HttpStatus.METHOD_NOT_ALLOWED);
            return;
        }

        // not only the case, Content-Length can be missing when Transfer-Encoding: chunked
        if (ProxyUtil.contentLength(request, 1024) > REQUEST_BODY_MAX_SIZE) {
            respond(request, HttpStatus.REQUEST_ENTITY_TOO_LARGE, "Request body is too large");
            return;
        }

        String path = URLDecoder.decode(request.path(), StandardCharsets.UTF_8);
        if (request.method() == HttpMethod.GET && path.equals(HEALTH_CHECK_PATH)) {
            respond(request, HttpStatus.OK);
            return;
        }

        String apiKey = request.headers().get(HEADER_API_KEY);
        if (apiKey == null) {
            respond(request, HttpStatus.UNAUTHORIZED, "Missing API-KEY header");
            return;
        }

        Config config = configStore.load();
        Key key = config.getKeys().get(apiKey);

        if (key == null) {
            respond(request, HttpStatus.UNAUTHORIZED, "Unknown api key");
            return;
        }

        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        log.debug("Authorization header: {}", authorization);
        if (authorization == null && key.getUserAuth() == UserAuth.ENABLED) {
            respond(request, HttpStatus.UNAUTHORIZED, "Missing Authorization header");
            return;
        }

        request.pause();
        Future<ExtractedClaims> extractedClaims;
        if (authorization != null) {
            try {
                final boolean isJwtMustBeValidated = key.getUserAuth() != UserAuth.DISABLED;
                extractedClaims = identityProvider.extractClaims(authorization, isJwtMustBeValidated);
            } catch (Throwable e) {
                onExtractClaimsFailure(e, config, request, key);
                return;
            }
        } else {
            extractedClaims = Future.succeededFuture();
        }

        extractedClaims.onComplete(result -> {
            try {
                if (result.succeeded()) {
                    onExtractClaimsSuccess(result.result(), config, request, key);
                } else {
                    onExtractClaimsFailure(result.cause(), config, request, key);
                }
            } catch (Throwable e) {
                handleError(e, request);
            } finally {
                request.resume();
            }
        });
    }

    private void onExtractClaimsFailure(Throwable error, Config config, HttpServerRequest request, Key key) throws Exception {
        if (key.getUserAuth() == UserAuth.ENABLED) {
            log.error("Can't extract claims from authorization header", error);
            respond(request, HttpStatus.UNAUTHORIZED, "Bad Authorization header");
        } else {
            log.info("Can't extract claims from authorization header");
            // if token is invalid set user roles to empty list
            onExtractClaimsSuccess(IdentityProvider.CLAIMS_WITH_EMPTY_ROLES, config, request, key);
        }
    }

    private void onExtractClaimsSuccess(ExtractedClaims extractedClaims, Config config, HttpServerRequest request, Key key) throws Exception {
        ProxyContext context = new ProxyContext(config, request, key, extractedClaims);
        Controller controller = ControllerSelector.select(this, context);
        controller.handle();
    }

    private void respond(HttpServerRequest request, HttpStatus status) {
        request.response().setStatusCode(status.getCode()).end();
    }

    private void respond(HttpServerRequest request, HttpStatus status, String body) {
        request.response().setStatusCode(status.getCode()).end(body);
    }
}
