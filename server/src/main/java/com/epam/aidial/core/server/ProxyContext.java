package com.epam.aidial.core.server;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.cache.CacheBreakpointContext;
import com.epam.aidial.core.server.security.ExtractedClaims;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.token.UsagePerModel;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.util.UrlUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.ServerWebSocket;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Getter
@Setter
public class ProxyContext {
    private static final Set<CharSequence> CORS_SAFE_LIST = Stream.of(
                    HttpHeaders.CACHE_CONTROL,
                    HttpHeaders.CONTENT_LANGUAGE,
                    HttpHeaders.CONTENT_LENGTH,
                    HttpHeaders.CONTENT_TYPE,
                    HttpHeaders.EXPIRES,
                    HttpHeaders.LAST_MODIFIED)
            .map(header -> header.toString().toLowerCase())
            .collect(Collectors.toUnmodifiableSet());

    private final Proxy proxy;
    // API key of root requester
    private final Key key;
    private final HttpServerRequest request;
    private final HttpServerResponse response;
    // OpenTelemetry trace ID
    private final String traceId;
    // API key data associated with the current request
    private final ApiKeyData apiKeyData;
    // OpenTelemetry span ID created by the Core
    private final String spanId;
    // OpenTelemetry parent span ID created by the Core
    private final String parentSpanId;
    // OpenTelemetry trace flags
    private final String traceFlags;
    // deployment name of the source(application/model) associated with the current request
    private final String sourceDeployment;
    private final String decodedSourceDeployment;

    private Deployment deployment;
    private String userId;
    private List<String> userRoles;
    private String userProject;
    private String userHash;
    private TokenUsage tokenUsage;
    private List<UsagePerModel> usagePerModel;
    private Route route;
    private UpstreamRoute upstreamRoute;
    private HttpClientRequest proxyRequest;
    private String proxyRequestUri;
    private HttpClientResponse proxyResponse;
    private Buffer requestBody;
    private Buffer responseBody;
    private long requestTimestamp;
    private long requestBodyTimestamp;
    private long proxyConnectTimestamp;
    private long proxyResponseTimestamp;
    private long responseBodyTimestamp;
    private ExtractedClaims extractedClaims;
    private ApiKeyData proxyApiKeyData;
    // deployment triggers interceptors
    private String initialDeployment;
    // List of interceptors copied from the deployment config, global interceptors and application type interceptors
    private List<String> interceptors;
    private boolean isStreamingRequest;
    private String traceOperation;
    // userName to be extracted from JWT or project name belongs to API key
    private String userDisplayName;
    private CacheBreakpointContext cacheBreakpointContext;
    private ServerWebSocket serverWebSocket;
    private boolean isStoreResponse;
    private boolean isBackgroundJob;

    public ProxyContext(Proxy proxy, HttpServerRequest request, ApiKeyData apiKeyData,
                        ExtractedClaims extractedClaims, String traceId, String spanId, String traceFlags) {
        this.proxy = proxy;
        this.apiKeyData = apiKeyData;
        this.request = request;
        this.response = request.response();
        this.requestTimestamp = System.currentTimeMillis();
        this.key = apiKeyData.getOriginalKey();
        if (apiKeyData.getPerRequestKey() != null) {
            initExtractedClaims(apiKeyData.getExtractedClaims(), apiKeyData.getOriginalKey());
            this.traceId = apiKeyData.getTraceId();
            this.parentSpanId = apiKeyData.getSpanId();
            this.sourceDeployment = apiKeyData.getSourceDeployment();
            this.decodedSourceDeployment = sourceDeployment != null ? UrlUtil.tryDecodePath(sourceDeployment) : null;
        } else {
            initExtractedClaims(extractedClaims, apiKeyData.getOriginalKey());
            this.traceId = traceId;
            this.parentSpanId = null;
            this.sourceDeployment = null;
            this.decodedSourceDeployment = null;
        }
        this.spanId = spanId;
        this.traceFlags = traceFlags;
    }

    private void initExtractedClaims(ExtractedClaims extractedClaims, Key originalKey) {
        this.extractedClaims = extractedClaims;
        if (extractedClaims != null) {
            this.userRoles = extractedClaims.userRoles();
            this.userHash = extractedClaims.userHash();
            this.userId = extractedClaims.userId();
            this.userProject = extractedClaims.project();
            this.userDisplayName = extractedClaims.userDisplayName();
        } else {
            this.userRoles = Objects.requireNonNull(originalKey, "API key must be provided if user claims are missed")
                    .getMergedRoles();
            this.userDisplayName = originalKey.getProject();
        }
    }

    public Future<?> respond(HttpStatus status) {
        return respond(status, null);
    }

    @SneakyThrows
    public Future<?> respond(HttpStatus status, Object object) {
        return respond(status, Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON, object);
    }

    @SneakyThrows
    public Future<?> respond(HttpStatus status, String contentType, Object object) {
        String json = ProxyUtil.MAPPER.writeValueAsString(object);
        response.putHeader(HttpHeaders.CONTENT_TYPE, contentType);
        return respond(status, json);
    }

    public Future<?> respond(HttpStatus status, String body) {
        return respond(status.getCode(), body == null ? null : Buffer.buffer(body));
    }

    public Future<?> respond(int status, Buffer body) {
        if (body == null) {
            body = Buffer.buffer();
        }

        response.setStatusCode(status).end(body);

        if (status < 200 || status >= 300) {
            log.warn("Responding with error. Body: {}", body);
        }

        return Future.succeededFuture();
    }

    public Future<?> respond(Throwable error, String fallbackError) {
        return error instanceof HttpException exception
                ? respond(exception)
                : respond(HttpStatus.INTERNAL_SERVER_ERROR, fallbackError);
    }

    public Future<?> respond(HttpException exception) {
        response.headers().addAll(exception.getHeaders());
        return respond(exception.getStatus(), exception.getMessage());
    }

    public Config getConfig() {
        return proxy.getConfigStore().get();
    }

    public String getProject() {
        if (userProject != null) {
            return userProject;
        }
        return key == null ? null : key.getProject();
    }

    /**
     * Returns an identifier of the request initiator: the JWT user id when present,
     * otherwise the API key project name. Mirrors the fallback used by
     * {@link com.epam.aidial.core.server.util.BucketBuilder#buildInitiatorBucket(ProxyContext)}
     * so features that track per-principal state work for both auth types.
     */
    public String getInitiatorId() {
        return userId != null ? userId : getProject();
    }

    public boolean isSecuredApiKey() {
        return key != null && key.isSecured();
    }

    /**
     * The whole claim payload as the identity provider returned it, or null for API key authentication.
     */
    public ObjectNode getUserClaims() {
        return extractedClaims == null ? null : extractedClaims.userClaims();
    }

    /**
     * How long the core worked on the request: from accepting it to having the full response body. Paths that never
     * produce a response body, e.g. a deployment without an endpoint, fall back to the current time. Both bounds are
     * wall-clock, so a backwards clock adjustment in between must not produce a negative duration.
     */
    public long calculateOperationDurationMs() {
        long end = responseBodyTimestamp == 0 ? System.currentTimeMillis() : responseBodyTimestamp;
        return Math.max(0, end - requestTimestamp);
    }

    public List<String> getExecutionPath() {
        return proxyApiKeyData == null ? null : proxyApiKeyData.getExecutionPath();
    }

    public boolean getBooleanRequestQueryParam(String name) {
        return Boolean.parseBoolean(request.getParam(name, "false"));
    }

    public List<String> getInterceptors() {
        return interceptors == null ? apiKeyData.getInterceptors() : interceptors;
    }

    public boolean hasNextInterceptor() {
        // initial call to the deployment or the interceptor calls another deployment
        String decodedName = UrlUtil.decodePath(deployment.getName());
        if (apiKeyData.getInterceptors() == null || !decodedName.equals(getInitialDeployment())) {
            return !interceptors.isEmpty();
        } else { // make sure if a next interceptor is available from the list
            return apiKeyData.getInterceptorIndex() + 1 < apiKeyData.getInterceptors().size();
        }
    }

    public String getInitialDeployment() {
        return initialDeployment == null ? apiKeyData.getInitialDeployment() : initialDeployment;
    }

    public ProxyContext putHeader(CharSequence name, String value) {
        response.putHeader(name, value);

        return this;
    }

    public ProxyContext exposeHeaders() {
        Set<String> headers = response.headers().names().stream()
                .filter(header -> {
                    String lowerCase = header.toLowerCase();
                    return !CORS_SAFE_LIST.contains(lowerCase)
                            // Exclude CORS headers
                            && !lowerCase.startsWith("access-control-");
                })
                .collect(Collectors.toUnmodifiableSet());
        if (!headers.isEmpty()) {
            response.putHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, String.join(", ", headers));
        }

        return this;
    }

    public String getRequestHeader(String name) {
        String value = request.getHeader(name);
        if (value != null) {
            return value;
        }
        return Optional.ofNullable(apiKeyData)
                .map(ApiKeyData::getHttpHeaders)
                .map(h -> h.get(name))
                .orElse(null);
    }

    public ProxyContext copyWith(ApiKeyData newApiKeyData) {
        return new ProxyContext(proxy, request, newApiKeyData, extractedClaims, traceId, spanId, traceFlags);
    }

    public boolean isOriginalRequest() {
        return apiKeyData.getPerRequestKey() == null;
    }
}
