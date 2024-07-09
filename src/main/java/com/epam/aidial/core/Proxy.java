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
import com.epam.aidial.core.upstream.UpstreamBalancer;
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
    private final UpstreamBalancer upstreamBalancer;
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
        log.error("Can't handle request", error);
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
        String apiKey = request.headers().get(HEADER_API_KEY);
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        SpanContext spanContext = Span.current().getSpanContext();
        String traceId = spanContext.getTraceId();
        String spanId = spanContext.getSpanId();
        log.debug("Authorization header: {}", authorization);
        Future<AuthorizationResult> authorizationResultFuture;

        request.pause();
        if (apiKey == null && authorization == null) {
            respond(request, HttpStatus.UNAUTHORIZED, "At least API-KEY or Authorization header must be provided");
            return;
        } else if (apiKey != null && authorization != null && !apiKey.equals(extractTokenFromHeader(authorization))) {
            respond(request, HttpStatus.BAD_REQUEST, "Either API-KEY or Authorization header must be provided but not both");
            return;
        } else if (apiKey != null) {
            authorizationResultFuture = apiKeyStore.getApiKeyData(apiKey)
                    .onFailure(error -> onGettingApiKeyDataFailure(error, request))
                    .compose(apiKeyData -> {
                        if (apiKeyData == null) {
                            String errorMessage = "Unknown api key";
                            respond(request, HttpStatus.UNAUTHORIZED, errorMessage);
                            return Future.failedFuture(errorMessage);
                        }
                        return Future.succeededFuture(new AuthorizationResult(apiKeyData, null));
                    });
        } else {
            authorizationResultFuture = tokenValidator.extractClaims(authorization)
                    .onFailure(error -> onExtractClaimsFailure(error, request))
                    .map(extractedClaims -> new AuthorizationResult(new ApiKeyData(), extractedClaims));
        }

        authorizationResultFuture.compose(result -> processAuthorizationResult(result.extractedClaims, config, request, result.apiKeyData, traceId, spanId))
                .onComplete(ignore -> request.resume());
    }

    private record AuthorizationResult(ApiKeyData apiKeyData, ExtractedClaims extractedClaims) {

    }

    private void onExtractClaimsFailure(Throwable error, HttpServerRequest request) {
        log.error("Can't extract claims from authorization header", error);
        respond(request, HttpStatus.UNAUTHORIZED, "Bad Authorization header");
    }

    private void onGettingApiKeyDataFailure(Throwable error, HttpServerRequest request) {
        log.error("Can't find data associated with API key", error);
        respond(request, HttpStatus.UNAUTHORIZED, "Bad Authorization header");
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
        return future.onFailure(error -> handleError(error, request));
    }

    private void respond(HttpServerRequest request, HttpStatus status) {
        request.response().setStatusCode(status.getCode()).end();
    }

    private void respond(HttpServerRequest request, HttpStatus status, String body) {
        request.response().setStatusCode(status.getCode()).end(body);
    }
}
