package com.epam.aidial.core;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.ConfigStore;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.controller.Controller;
import com.epam.aidial.core.controller.ControllerSelector;
import com.epam.aidial.core.limiter.RateLimiter;
import com.epam.aidial.core.log.LogStore;
import com.epam.aidial.core.security.AccessTokenValidator;
import com.epam.aidial.core.security.ExtractedClaims;
import com.epam.aidial.core.storage.BlobStorage;
import com.epam.aidial.core.upstream.UpstreamBalancer;
import com.epam.aidial.core.util.HttpStatus;
import com.epam.aidial.core.util.ProxyUtil;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
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

import static com.epam.aidial.core.security.AccessTokenValidator.extractTokenFromHeader;

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

    public static final int REQUEST_BODY_MAX_SIZE_BYTES = 16 * 1024 * 1024;
    public static final int FILES_REQUEST_BODY_MAX_SIZE_BYTES = 512 * 1024 * 1024;

    private final Vertx vertx;
    private final HttpClient client;
    private final ConfigStore configStore;
    private final LogStore logStore;
    private final RateLimiter rateLimiter;
    private final UpstreamBalancer upstreamBalancer;
    private final AccessTokenValidator tokenValidator;
    private final BlobStorage storage;

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
    private void handleRequest(HttpServerRequest request) {
        if (request.version() != HttpVersion.HTTP_1_1) {
            respond(request, HttpStatus.HTTP_VERSION_NOT_SUPPORTED);
            return;
        }

        HttpMethod requestMethod = request.method();
        if (requestMethod != HttpMethod.GET && requestMethod != HttpMethod.POST && requestMethod != HttpMethod.DELETE) {
            respond(request, HttpStatus.METHOD_NOT_ALLOWED);
            return;
        }

        String contentType = request.getHeader(HttpHeaders.CONTENT_TYPE);
        int contentLength = ProxyUtil.contentLength(request, 1024);
        if (contentType != null && contentType.startsWith("multipart/form-data")) {
            if (contentLength > FILES_REQUEST_BODY_MAX_SIZE_BYTES) {
                respond(request, HttpStatus.REQUEST_ENTITY_TOO_LARGE, "Request body is too large");
                return;
            }
        } else {
            // not only the case, Content-Length can be missing when Transfer-Encoding: chunked
            if (contentLength > REQUEST_BODY_MAX_SIZE_BYTES) {
                respond(request, HttpStatus.REQUEST_ENTITY_TOO_LARGE, "Request body is too large");
                return;
            }
        }

        String path = URLDecoder.decode(request.path(), StandardCharsets.UTF_8);
        if (request.method() == HttpMethod.GET && path.equals(HEALTH_CHECK_PATH)) {
            respond(request, HttpStatus.OK);
            return;
        }

        Config config = configStore.load();
        String apiKey = request.headers().get(HEADER_API_KEY);
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        log.debug("Authorization header: {}", authorization);
        Key key;
        if (apiKey == null && authorization == null) {
            respond(request, HttpStatus.UNAUTHORIZED, "At least API-KEY or Authorization header must be provided");
            return;
        } else if (apiKey != null && authorization != null && !apiKey.equals(extractTokenFromHeader(authorization))) {
            respond(request, HttpStatus.BAD_REQUEST, "Either API-KEY or Authorization header must be provided but not both");
            return;
        } else if (apiKey != null) {
            key = config.getKeys().get(apiKey);
            // Special case handling. OpenAI client sends both API key and Auth headers even if a caller sets just API Key only
            // Auth header is set to the same value as API Key header
            // ignore auth header in this case
            authorization = null;
            if (key == null) {
                respond(request, HttpStatus.UNAUTHORIZED, "Unknown api key");
                return;
            }
        } else {
            key = null;
        }

        request.pause();
        Future<ExtractedClaims> extractedClaims = tokenValidator.extractClaims(authorization);

        extractedClaims.onComplete(result -> {
            try {
                if (result.succeeded()) {
                    onExtractClaimsSuccess(result.result(), config, request, key);
                } else {
                    onExtractClaimsFailure(result.cause(), request);
                }
            } catch (Throwable e) {
                handleError(e, request);
            } finally {
                request.resume();
            }
        });
    }

    private void onExtractClaimsFailure(Throwable error, HttpServerRequest request) {
        log.error("Can't extract claims from authorization header", error);
        respond(request, HttpStatus.UNAUTHORIZED, "Bad Authorization header");
    }

    private void onExtractClaimsSuccess(ExtractedClaims extractedClaims, Config config,
                                        HttpServerRequest request, Key key) throws Exception {
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
