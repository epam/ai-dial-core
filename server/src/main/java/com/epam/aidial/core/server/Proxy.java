package com.epam.aidial.core.server;

import com.epam.aidial.core.credentials.service.AuthorizationHeaderProvider;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsEncryptionService;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsService;
import com.epam.aidial.core.credentials.service.ResourceCredentialsService;
import com.epam.aidial.core.server.config.ConfigStore;
import com.epam.aidial.core.server.controller.Controller;
import com.epam.aidial.core.server.controller.ControllerSelector;
import com.epam.aidial.core.server.controller.ControllerTemplate;
import com.epam.aidial.core.server.controller.HealthCheckController;
import com.epam.aidial.core.server.controller.WellKnownResourceMetadataController;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.ApiKeyValidation;
import com.epam.aidial.core.server.data.RouteTemplate;
import com.epam.aidial.core.server.limiter.RateLimiter;
import com.epam.aidial.core.server.log.LogStore;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.AccessTokenValidator;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.security.ExtractedClaims;
import com.epam.aidial.core.server.service.ApplicationSchemaService;
import com.epam.aidial.core.server.service.ApplicationService;
import com.epam.aidial.core.server.service.BackgroundJobService;
import com.epam.aidial.core.server.service.CatalogSchemaService;
import com.epam.aidial.core.server.service.ConsentService;
import com.epam.aidial.core.server.service.DeploymentService;
import com.epam.aidial.core.server.service.ExternalServiceService;
import com.epam.aidial.core.server.service.HeartbeatService;
import com.epam.aidial.core.server.service.InvitationService;
import com.epam.aidial.core.server.service.McpHttpClientBuilderService;
import com.epam.aidial.core.server.service.NotificationService;
import com.epam.aidial.core.server.service.PerRequestPermissionService;
import com.epam.aidial.core.server.service.PublicationService;
import com.epam.aidial.core.server.service.ResourceOperationService;
import com.epam.aidial.core.server.service.ResponseMappingService;
import com.epam.aidial.core.server.service.ResponsesApiClient;
import com.epam.aidial.core.server.service.RuleService;
import com.epam.aidial.core.server.service.SecuredResourceService;
import com.epam.aidial.core.server.service.ShareService;
import com.epam.aidial.core.server.service.ToolSetRepairService;
import com.epam.aidial.core.server.service.ToolSetService;
import com.epam.aidial.core.server.service.UpstreamCacheService;
import com.epam.aidial.core.server.service.UserExternalServiceService;
import com.epam.aidial.core.server.service.WellKnownResourceMetadataService;
import com.epam.aidial.core.server.service.clientchannel.ClientChannelService;
import com.epam.aidial.core.server.service.codeinterpreter.CodeInterpreterService;
import com.epam.aidial.core.server.service.resource.ComplexResourceService;
import com.epam.aidial.core.server.token.TokenStatsTracker;
import com.epam.aidial.core.server.upstream.UpstreamRouteProvider;
import com.epam.aidial.core.server.util.AuthSettingsResolver;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.WebSocketClient;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Strings;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

@Slf4j
@Getter
@RequiredArgsConstructor
public class Proxy implements Handler<HttpServerRequest> {

    public static final String HEALTH_CHECK_PATH = "/health";
    public static final String VERSION_PATH = "/version";

    public static final Pattern TOOLSET_PROXY_PATTERN = RouteTemplate.TOOL_SET_MCP_PROXY.getPattern();
    public static final Pattern TOOLSET_PROXY_METADATA_PATTERN = RouteTemplate.TOOL_SET_PROXY_METADATA.getPattern();
    public static final Pattern APPLICATION_MCP_PROXY_PATTERN = RouteTemplate.APPLICATION_MCP_PROXY.getPattern();
    public static final Pattern APPLICATION_MCP_PROXY_METADATA_PATTERN = RouteTemplate.APPLICATION_MCP_PROXY_METADATA.getPattern();

    // All new headers should start with X-DIAL- while existing may stay untouched

    public static final String HEADER_API_KEY = "API-KEY";
    /**
     * Authentication header of the <a href="https://platform.claude.com/docs/en/api/overview#authentication">Anthropic API</a>.
     * Native Anthropic SDK clients pointed at {@code /anthropic/v1/messages} send the DIAL API key in this header
     * and cannot set {@link #HEADER_API_KEY} or {@code Authorization}. It is accepted as the DIAL key only when
     * neither of those headers is present (see {@link #extractApiKey}) and is stripped before forwarding upstream.
     */
    public static final String HEADER_X_API_KEY = "x-api-key";
    public static final String HEADER_JOB_TITLE = "X-JOB-TITLE";
    public static final String HEADER_CONVERSATION_ID = "X-CONVERSATION-ID";
    public static final String HEADER_UPSTREAM_ID = "X-UPSTREAM-ID";
    public static final String HEADER_UPSTREAM_ENDPOINT = "X-UPSTREAM-ENDPOINT";
    public static final String HEADER_UPSTREAM_KEY = "X-UPSTREAM-KEY";
    public static final String HEADER_UPSTREAM_EXTRA_DATA = "X-UPSTREAM-EXTRA-DATA";
    public static final String HEADER_UPSTREAM_ATTEMPTS = "X-UPSTREAM-ATTEMPTS";
    public static final String HEADER_CACHE_POLICY = "X-DIAL-CACHE-POLICY";
    public static final String HEADER_CACHE_BREAKPOINT_PATH = "X-DIAL-CACHE-BREAKPOINT-PATH";
    public static final String HEADER_CACHE_EXPIRE_AT = "X-DIAL-CACHE-EXPIRE-AT";
    public static final String HEADER_CACHE_EXTRA_METADATA = "X-DIAL-CACHE-EXTRA-METADATA";
    public static final String HEADER_CONTENT_TYPE_APPLICATION_JSON = "application/json";
    public static final String HEADER_CONTENT_TYPE_TEXT_EVENT_STREAM = "text/event-stream";
    public static final String HEADER_APPLICATION_PROPERTIES = "X-DIAL-APPLICATION-PROPERTIES";
    public static final String HEADER_APPLICATION_ID = "X-DIAL-APPLICATION-ID";
    public static final String HEADER_OVERRIDE_NAME = "X-DIAL-OVERRIDE-NAME";
    public static final String HEADER_CLIENT_CHANNEL_ID = "X-DIAL-CLIENT-CHANNEL-ID";
    public static final String HEADER_DEPLOYMENT_ID = "X-DIAL-DEPLOYMENT-ID";

    public static final Set<HttpMethod> ALLOWED_HTTP_METHODS = Set.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT,
            HttpMethod.DELETE, HttpMethod.HEAD, HttpMethod.PATCH);

    private final Vertx vertx;
    private final HttpClientOptions clientOptions;
    private final ApiKeyValidation apiKeyValidation;
    private final HttpClient client;
    private final WebSocketClient webSocketClient;
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
    private final ApplicationService applicationService;
    private final ExternalServiceService externalServiceService;
    private final UserExternalServiceService userExternalServiceService;
    private final CodeInterpreterService codeInterpreterService;
    private final HeartbeatService heartbeatService;
    private final UpstreamCacheService upstreamCacheService;
    private final ConsentService consentService;
    private final DeploymentService deploymentService;
    private final HealthCheckController healthCheckController;
    private final WellKnownResourceMetadataService resourceMetadataService;
    private final WellKnownResourceMetadataController resourceMetadataController;
    private final ToolSetService toolSetService;
    private final SecuredResourceService securedResourceService;
    private final McpHttpClientBuilderService mcpHttpClientBuilderService;
    private final ToolSetRepairService toolSetRepairService;
    private final ApplicationSchemaService applicationSchemaService;
    private final CatalogSchemaService catalogSchemaService;
    private final AuthorizationHeaderProvider authorizationHeaderProvider;
    private final ResourceAuthSettingsService resourceAuthSettingsService;
    private final ResourceCredentialsService resourceCredentialsService;
    private final PerRequestPermissionService perRequestPermissionService;
    private final ResourceAuthSettingsEncryptionService resourceAuthSettingsEncryptionService;
    private final AuthSettingsResolver authSettingsResolver;
    private final ClientChannelService clientChannelService;
    private final AsyncTaskExecutor taskExecutor;
    private final String version;
    private final ResponseMappingService responseMappingService;
    private final ComplexResourceService complexResourceService;
    private final BackgroundJobService backgroundJobService;
    private final ResponsesApiClient responsesApiClient;
    // Generates the unique portion of a DIAL response ID
    private final Supplier<String> generator;

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
            HttpStatus status;
            String message;
            Map<String, String> headers;

            if (error instanceof HttpException e) {
                status = e.getStatus();
                message = e.getMessage();
                headers = e.getHeaders();
            } else {
                status = HttpStatus.INTERNAL_SERVER_ERROR;
                message = null;
                headers = Map.of();
            }

            respond(request, status, message, headers);

            if (!(error instanceof HttpException)) {
                log.error("Can't handle request", error);
            }
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
            if (contentLength > storage.getMaxUploadedFileSize()) {
                respond(request, HttpStatus.REQUEST_ENTITY_TOO_LARGE, "Request body is too large");
                return;
            }
        } else {
            // not only the case, Content-Length can be missing when Transfer-Encoding: chunked
            if (contentLength > resourceService.getMaxSize()) {
                respond(request, HttpStatus.REQUEST_ENTITY_TOO_LARGE, "Request body is too large");
                return;
            }
        }

        String path = URLDecoder.decode(request.path(), StandardCharsets.UTF_8);
        if (request.method() == HttpMethod.GET && path.equals(HEALTH_CHECK_PATH)) {
            healthCheckController.handle(request);
            return;
        }

        if (request.method() == HttpMethod.GET && path.equals(VERSION_PATH)) {
            respond(request, HttpStatus.OK, version);
            return;
        }

        if (request.method() == HttpMethod.GET && isMcpResourceMetadataPath(request.path())) {
            resourceMetadataController.handle(request);
            return;
        }

        SpanContext spanContext = Span.current().getSpanContext();
        String traceId = spanContext.getTraceId();
        String spanId = spanContext.getSpanId();
        String traceFlags = spanContext.getTraceFlags().asHex();

        request.pause();
        Future<AuthorizationResult> authorizationResultFuture = authorizeRequest(request);
        authorizationResultFuture.compose(result -> processAuthorizationResult(result.extractedClaims, request, result.apiKeyData, traceId, spanId, traceFlags))
                .onFailure(error -> handleError(error, request))
                .onComplete(ignore -> request.resume());
    }

    /**
     * The method authorizes HTTP request by user access token or (and) API key.
     * <p>
     * There are four possible use cases:
     *     <ol>
     *     <li>Both API key and access token are missed</li>
     *     <li>Both API key and access token are provided</li>
     *     <li>Just API key is provided</li>
     *     <li>Just access token is provided</li>
     *     </ol>
     *     The 1st use case has two sub-cases:
     *     <ol>
     *     <li>Path matches {@link Proxy#TOOLSET_PROXY_PATTERN} and request method is GET or POST.
     *     The response includes {@code WWW-Authenticate} header with resource metadata</li>
     *     <li>Other cases. The request is rejected with 401</li>
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
        String apiKey = extractApiKey(request);
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);

        String clientIpAddress = ProxyUtil.getClientIpAddress(request, apiKeyValidation.getProxyCount());
        if (apiKey == null && authorization == null) {
            Map<String, String> headers = Map.of();
            if ((request.method() == HttpMethod.GET || request.method() == HttpMethod.POST) && isMcpResourcePath(request.path())) {
                headers = resourceMetadataService.resolveResourceMetadataPath(request)
                        .map(path -> Map.of("WWW-Authenticate", "Bearer resource_metadata=\"" + path + "\""))
                        .orElse(Map.of());
            }
            return Future.failedFuture(new HttpException(HttpStatus.UNAUTHORIZED, "At least API-KEY or Authorization header must be provided", headers));
        }

        if (apiKey != null && authorization == null) {
            return apiKeyStore.getApiKeyData(apiKey, clientIpAddress)
                    .map(apiKeyData -> new AuthorizationResult(apiKeyData, null));
        }

        if (apiKey == null) {
            return tokenValidator.extractClaims(authorization)
                    .compose(extractedClaims -> Future.succeededFuture(new AuthorizationResult(new ApiKeyData(), extractedClaims)))
                    .transform(result -> {
                        if (result.failed()) {
                            if (!Strings.CI.startsWith(authorization, "bearer ")) {
                                return Future.failedFuture(new HttpException(HttpStatus.UNAUTHORIZED, "Bad Authorization header"));
                            }
                            // OpenAI's Responses API uses the Authorization header to send the API key.
                            String token = AccessTokenValidator.extractTokenFromHeader(authorization);
                            return apiKeyStore.getApiKeyData(token, clientIpAddress)
                                    .map(apiKeyData -> new AuthorizationResult(apiKeyData, null));
                        }
                        return Future.succeededFuture(result.result());
                    });
        }

        // see https://github.com/epam/ai-dial-core/issues/675
        if ("Bearer".equalsIgnoreCase(authorization.trim())) {
            return apiKeyStore.getApiKeyData(apiKey, clientIpAddress)
                    .map(apiKeyData -> new AuthorizationResult(apiKeyData, null));
        }

        if (apiKey.equals(AccessTokenValidator.extractTokenFromHeader(authorization))) {
            // we don't know exactly what kind of credentials a client provided to us.
            // we try if it's access token the first and then API key
            return tokenValidator.extractClaims(authorization)
                    .compose(claims -> Future.succeededFuture(new AuthorizationResult(new ApiKeyData(), claims)),
                            error -> apiKeyStore.getApiKeyData(apiKey, clientIpAddress)
                                    .map(apiKeyData -> new AuthorizationResult(apiKeyData, null)));
        }
        // assume authentication is done by per-request key
        return apiKeyStore.getApiKeyData(apiKey, clientIpAddress)
                .compose(apiKeyData -> {
                    // allow authentication by per-request key and accept authorization header
                    if (apiKeyData.getPerRequestKey() != null) {
                        return Future.succeededFuture(new AuthorizationResult(apiKeyData, null));
                    } else {
                        return Future.failedFuture(new HttpException(HttpStatus.BAD_REQUEST, "Provided API key is not per request key"));
                    }
                });

    }

    /**
     * Extracts the DIAL API key from the request. The key is taken from the {@link #HEADER_API_KEY} header;
     * when neither it nor {@code Authorization} is present, falls back to {@link #HEADER_X_API_KEY} — the header
     * native Anthropic SDK clients send their credentials in — so existing authentication flows are unaffected.
     *
     * @param request HTTP request
     * @return the API key or {@code null} if the request carries none
     */
    @Nullable
    private static String extractApiKey(HttpServerRequest request) {
        String apiKey = request.headers().get(HEADER_API_KEY);
        if (apiKey == null && request.getHeader(HttpHeaders.AUTHORIZATION) == null) {
            return request.headers().get(HEADER_X_API_KEY);
        }
        return apiKey;
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
    private Future<?> processAuthorizationResult(ExtractedClaims extractedClaims,
                                                 HttpServerRequest request, ApiKeyData apiKeyData,
                                                 String traceId, String spanId, String traceFlags) {
        // Clear context when the response is actually closed, not when controller completes
        request.response().closeHandler(v -> ContextManager.clearContext());
        Future<?> future;
        try {
            ProxyContext context = new ProxyContext(this, request, apiKeyData, extractedClaims, traceId, spanId, traceFlags);
            ContextManager.setProxyContext(context);
            ControllerTemplate controllerTemplate = ControllerSelector.select(request);
            Controller controller = controllerTemplate.build(this, context);
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

    private void respond(HttpServerRequest request, HttpStatus status, String body, Map<String, String> headers) {
        headers.forEach(request.response()::putHeader);
        respond(request, status, body);
    }

    private static boolean isMcpResourcePath(String path) {
        return TOOLSET_PROXY_PATTERN.matcher(path).matches()
                || APPLICATION_MCP_PROXY_PATTERN.matcher(path).matches();
    }

    private static boolean isMcpResourceMetadataPath(String path) {
        return TOOLSET_PROXY_METADATA_PATTERN.matcher(path).matches()
                || APPLICATION_MCP_PROXY_METADATA_PATTERN.matcher(path).matches();
    }
}
