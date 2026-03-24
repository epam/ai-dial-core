package com.epam.aidial.core.server;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.cache.CacheBreakpointContext;
import com.epam.aidial.core.server.security.ExtractedClaims;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.stream.BufferingReadStream;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.util.UrlUtil;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.http.ServerWebSocket;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.epam.aidial.core.server.Proxy.HEADER_APPLICATION_ID;
import static com.epam.aidial.core.server.Proxy.HEADER_APPLICATION_PROPERTIES;

@Slf4j
@Getter
@Setter
public class ProxyContext {
    private static final Set<Integer> DEFAULT_RETRIABLE_HTTP_CODES = Set.of(HttpStatus.TOO_MANY_REQUESTS.getCode(),
            HttpStatus.BAD_GATEWAY.getCode(), HttpStatus.GATEWAY_TIMEOUT.getCode(),
            HttpStatus.SERVICE_UNAVAILABLE.getCode());
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
    private final Config config;
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
    private Route route;
    private UpstreamRoute upstreamRoute;
    private HttpClientRequest proxyRequest;
    private HttpClientResponse proxyResponse;
    private Buffer requestBody;
    private Buffer responseBody;
    private BufferingReadStream responseStream; // received from origin
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

    public ProxyContext(Proxy proxy, Config config, HttpServerRequest request, ApiKeyData apiKeyData,
                        ExtractedClaims extractedClaims, String traceId, String spanId, String traceFlags) {
        this.proxy = proxy;
        this.config = config;
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
        if (body == null) {
            body = "";
        }

        response.setStatusCode(status.getCode()).end(body);

        if (status != HttpStatus.OK) {
            log.warn("Responding with error. Body: {}", body);
        }

        return Future.succeededFuture();
    }

    public Future<?> respond(int status, Buffer body) {
        if (body == null) {
            body = Buffer.buffer();
        }

        response.setStatusCode(status).end(body);

        if (status != HttpStatus.OK.getCode()) {
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

    public String getProject() {
        if (userProject != null) {
            return userProject;
        }
        return key == null ? null : key.getProject();
    }

    public boolean isSecuredApiKey() {
        return key != null && key.isSecured();
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
        return new ProxyContext(proxy, config, request, newApiKeyData, extractedClaims, traceId, spanId, traceFlags);
    }

    public Future<HttpClientRequest> createProxyRequest(Function<Deployment, String> endpointSelector) {
        String uri = endpointSelector.apply(deployment) + (request.query() == null ? "" : request.query());
        RequestOptions options = new RequestOptions()
                .setAbsoluteURI(uri)
                .setMethod(request.method())
                .setTraceOperation(traceOperation)
                .setConnectTimeout(proxy.getClientOptions().getConnectTimeout())
                .setIdleTimeout(proxy.getClientOptions().getIdleTimeout());

        return proxy.getClient().request(options);
    }

    public Future<HttpClientResponse> sendProxyRequest(
            HttpClientRequest proxyRequest, Function<Upstream, String> upstreamSelector) {
        log.info("Connected to origin. Deployment: {}. Address: {}",
                deployment.getName(),
                proxyRequest.connection().remoteAddress());

        MultiMap excludeHeaders = MultiMap.caseInsensitiveMultiMap();
        if (!deployment.isForwardAuthToken()) {
            excludeHeaders.add(HttpHeaders.AUTHORIZATION, "whatever");
        }
        excludeHeaders.add(HEADER_APPLICATION_PROPERTIES, "whatever");
        excludeHeaders.add(HEADER_APPLICATION_ID, "whatever");

        ProxyUtil.copyHeaders(request.headers(), proxyRequest.headers(), excludeHeaders);
        ProxyUtil.setOverrideNameHeader(proxyRequest, deployment);

        proxyRequest.headers().add(Proxy.HEADER_API_KEY, proxyApiKeyData.getPerRequestKey());

        if (deployment instanceof Model model && !model.getUpstreams().isEmpty()) {
            Upstream upstream = Objects.requireNonNull(upstreamRoute.get());
            proxyRequest.putHeader(Proxy.HEADER_UPSTREAM_ENDPOINT, upstreamSelector.apply(upstream))
                    .putHeader(Proxy.HEADER_UPSTREAM_KEY, upstream.getKey())
                    .putHeader(Proxy.HEADER_UPSTREAM_EXTRA_DATA, upstream.getExtraData())
                    .putHeader(Proxy.HEADER_CACHE_BREAKPOINT_PATH, upstreamRoute.getBreakpointPath())
                    .putHeader(Proxy.HEADER_CACHE_EXTRA_METADATA, upstreamRoute.getExtraMetadata());
        }

        if (deployment instanceof Application application && application.hasApplicationTypeSchemaId()) {
            proxyRequest.putHeader(HEADER_APPLICATION_ID, deployment.getName());

            proxy.getApplicationSchemaService().consumeMetadataProperties(application, (properties, appendApplicationPropertiesHeader) -> {
                if (appendApplicationPropertiesHeader) {
                    String propsString = ProxyUtil.MAPPER.writeValueAsString(properties);
                    proxyRequest.putHeader(HEADER_APPLICATION_PROPERTIES, propsString);
                }
            });
        }

        proxyRequest.putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(requestBody.length()));

        return proxyRequest.send(requestBody);
    }

    public boolean isRetriableError(int statusCode) {
        return DEFAULT_RETRIABLE_HTTP_CODES.contains(statusCode) || config.getRetriableErrorCodes().contains(statusCode);
    }
}
