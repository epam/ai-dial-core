package com.epam.aidial.core;

import com.epam.aidial.core.config.ApiKeyData;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.ConfigStore;
import com.epam.aidial.core.controller.Controller;
import com.epam.aidial.core.controller.ControllerSelector;
import com.epam.aidial.core.limiter.RateLimiter;
import com.epam.aidial.core.log.LogStore;
import com.epam.aidial.core.security.AccessService;
import com.epam.aidial.core.security.AccessTokenValidator;
import com.epam.aidial.core.security.ApiKeyStore;
import com.epam.aidial.core.security.EncryptionService;
import com.epam.aidial.core.security.ExtractedClaims;
import com.epam.aidial.core.service.CustomApplicationService;
import com.epam.aidial.core.service.HeartbeatService;
import com.epam.aidial.core.service.InvitationService;
import com.epam.aidial.core.service.LockService;
import com.epam.aidial.core.service.NotificationService;
import com.epam.aidial.core.service.PublicationService;
import com.epam.aidial.core.service.ResourceOperationService;
import com.epam.aidial.core.service.ResourceService;
import com.epam.aidial.core.service.RuleService;
import com.epam.aidial.core.service.ShareService;
import com.epam.aidial.core.storage.BlobStorage;
import com.epam.aidial.core.token.TokenStatsTracker;
import com.epam.aidial.core.upstream.UpstreamRouteProvider;
import com.epam.aidial.core.util.HttpException;
import com.epam.aidial.core.util.HttpStatus;
import com.epam.aidial.core.util.ProxyUtil;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static com.epam.aidial.core.security.AccessTokenValidator.extractTokenFromHeader;

@Slf4j
@Getter
@RequiredArgsConstructor
public class Proxy implements Handler<HttpServerRequest> {

    public static final String HEALTH_CHECK_PATH = "/health";
    public static final String VERSION_PATH = "/version";

    public static final String HEADER_API_KEY = "API-KEY";
    public static final String HEADER_JOB_TITLE = "X-JOB-TITLE";
    public static final String HEADER_CONVERSATION_ID = "X-CONVERSATION-ID";
    public static final String HEADER_UPSTREAM_ENDPOINT = "X-UPSTREAM-ENDPOINT";
    public static final String HEADER_UPSTREAM_KEY = "X-UPSTREAM-KEY";
    public static final String HEADER_UPSTREAM_EXTRA_DATA = "X-UPSTREAM-EXTRA-DATA";
    public static final String HEADER_UPSTREAM_ATTEMPTS = "X-UPSTREAM-ATTEMPTS";
    public static final String HEADER_CONTENT_TYPE_APPLICATION_JSON = "application/json";

    public static final int REQUEST_BODY_MAX_SIZE_BYTES = 16 * 1024 * 1024;
    public static final int FILES_REQUEST_BODY_MAX_SIZE_BYTES = 512 * 1024 * 1024;

    private static final Set<HttpMethod> ALLOWED_HTTP_METHODS = Set.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE);

    private final Vertx vertx;
    private final HttpClient client;
    private final ConfigStore configStore;
    private final LogStore logStore;
    private final RateLimiter rateLimiter;
    private final UpstreamRouteProvider upstreamRouteProvider;
    private final AccessTokenValidator tokenValidator;
    private final BlobStorage storage;
    private final EncryptionService encryptionService;
    private final ApiKeyStore apiKeyStore;
    private final TokenStatsTracker tokenStatsTracker;
    private final ResourceService resourceService;
    private final InvitationService invitationService;
    private final ShareService shareService;
    private final PublicationService publicationService;
    private final AccessService accessService;
    private final LockService lockService;
    private final ResourceOperationService resourceOperationService;
    private final RuleService ruleService;
    private final NotificationService notificationService;
    private final CustomApplicationService customApplicationService;
    private final HeartbeatService heartbeatService;
    private final String version;

    @Override
    public void handle(HttpServerRequest request) {
        try {
            handleRequest(request);
        } catch (Throwable e) {
            handleError(e, request);
        }
    }

    private void handleError(Throwable error, HttpServerRequest request) {
        if (!request.response().ended()) {
            HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
            String message = null;

            if (error instanceof HttpException e) {
                status = e.getStatus();
                message = e.getMessage();
            } else {
                log.error("Can't handle request", error);
            }

            respond(request, status, message);
        }
    }

    /**
     * Called when proxy received the request headers from a client.
     */
    private void handleRequest(HttpServerRequest request) {
        enableCors(request);

        if (request.version() != HttpVersion.HTTP_1_1) {
            respond(request, HttpStatus.HTTP_VERSION_NOT_SUPPORTED);
            return;
        }

        HttpMethod requestMethod = request.method();
        if (requestMethod == HttpMethod.OPTIONS) {
            // Allow OPTIONS request caching by browser
            request.response().putHeader(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "86400");
            respond(request, HttpStatus.OK);
            return;
        }

        if (!ALLOWED_HTTP_METHODS.contains(requestMethod)) {
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

        if (request.method() == HttpMethod.GET && path.equals(VERSION_PATH)) {
            respond(request, HttpStatus.OK, version);
            return;
        }

        Config config = configStore.load();
        SpanContext spanContext = Span.current().getSpanContext();
        String traceId = spanContext.getTraceId();
        String spanId = spanContext.getSpanId();

        request.pause();
        Future<AuthorizationResult> authorizationResultFuture = authorizeRequest(request);
        authorizationResultFuture.compose(result -> processAuthorizationResult(result.extractedClaims, config, request, result.apiKeyData, traceId, spanId))
                .onFailure(error -> handleError(error, request))
                .onComplete(ignore -> request.resume());
    }

    /**
     * The method authorizes HTTP request by user access token or (and) API key.
     * <p>
     *     There are four possible use cases:
     *     <ol>
     *     <li>Both API key and access token are missed</li>
     *     <li>Both API key and access token are provided</li>
     *     <li>Just API key is provided</li>
     *     <li>Just access token is provided</li>
     *     </ol>
     *     The 2nd use case has two sub-cases:
     *     <ol>
     *     <li>API key is equal to access token. The credentials could be either access token or API key</li>
     *     <li>API key is not equal to access token. It could be per-request API key sent by an interceptor</li>
     *     </ol>
     * </p>
     *
     * @param request HTTP request
     * @return the future of {@link AuthorizationResult}
     */
    private Future<AuthorizationResult> authorizeRequest(HttpServerRequest request) {
        String apiKey = request.headers().get(HEADER_API_KEY);
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        log.debug("Authorization header: {}", authorization);

        if (apiKey == null && authorization == null) {
            return Future.failedFuture(new HttpException(HttpStatus.UNAUTHORIZED, "At least API-KEY or Authorization header must be provided"));
        }

        if (apiKey != null && authorization == null) {
            return apiKeyStore.getApiKeyData(apiKey)
                    .map(apiKeyData -> new AuthorizationResult(apiKeyData, null));
        }

        if (apiKey == null) {
            return tokenValidator.extractClaims(authorization)
                    .compose(extractedClaims -> Future.succeededFuture(new AuthorizationResult(new ApiKeyData(), extractedClaims)),
                            error -> Future.failedFuture(new HttpException(HttpStatus.UNAUTHORIZED, "Bad Authorization header")));
        }

        if (apiKey.equals(extractTokenFromHeader(authorization))) {
            // we don't know exactly what kind of credentials a client provided to us.
            // we try if it's access token the first and then API key
            return tokenValidator.extractClaims(authorization)
                    .compose(claims -> Future.succeededFuture(new AuthorizationResult(new ApiKeyData(), claims)),
                            error -> apiKeyStore.getApiKeyData(apiKey).map(apiKeyData -> new AuthorizationResult(apiKeyData, null)));
        }
        // interceptor case
        return apiKeyStore.getApiKeyData(apiKey)
                .compose(apiKeyData -> {
                    if (apiKeyData.isInterceptor()) {
                        return Future.succeededFuture(new AuthorizationResult(apiKeyData, null));
                    } else {
                        return Future.failedFuture(new HttpException(HttpStatus.BAD_REQUEST, "Either API-KEY or Authorization header must be provided but not both"));
                    }
                });

    }

    private static void enableCors(HttpServerRequest request) {
        HttpServerResponse response = request.response();
        response.putHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*");

        String requestMethod = request.getHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD);
        if (requestMethod != null) {
            response.putHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, requestMethod);
        }
        String requestHeaders = request.getHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS);
        if (requestHeaders != null) {
            response.putHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, requestHeaders);
        }
    }

    private record AuthorizationResult(ApiKeyData apiKeyData, ExtractedClaims extractedClaims) {

    }

    @SneakyThrows
    private Future<?> processAuthorizationResult(ExtractedClaims extractedClaims, Config config,
                                                 HttpServerRequest request, ApiKeyData apiKeyData, String traceId, String spanId) {
        Future<?> future;
        try {
            ProxyContext context = new ProxyContext(config, request, apiKeyData, extractedClaims, traceId, spanId);
            Controller controller = ControllerSelector.select(this, context);
            future = controller.handle();
        } catch (Exception t) {
            future = Future.failedFuture(t);
        }
        return future;
    }

    private void respond(HttpServerRequest request, HttpStatus status) {
        respond(request, status, null);
    }

    private void respond(HttpServerRequest request, HttpStatus status, String body) {
        request.response().setStatusCode(status.getCode()).end(body == null ? "" : body);
    }
}
