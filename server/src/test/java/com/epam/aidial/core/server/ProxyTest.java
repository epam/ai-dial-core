package com.epam.aidial.core.server;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.server.config.ConfigStore;
import com.epam.aidial.core.server.controller.HealthCheckController;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.ApiKeyValidation;
import com.epam.aidial.core.server.limiter.RateLimiter;
import com.epam.aidial.core.server.log.LogStore;
import com.epam.aidial.core.server.security.AccessTokenValidator;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.security.ExtractedClaims;
import com.epam.aidial.core.server.service.WellKnownResourceMetadataService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.service.ResourceService;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.Logger;

import java.util.*;
import java.util.regex.Pattern;

import static com.epam.aidial.core.server.Proxy.HEADER_API_KEY;
import static com.epam.aidial.core.server.Proxy.HEALTH_CHECK_PATH;
import static com.epam.aidial.core.storage.blobstore.Storage.DEFAULT_MAX_UPLOADED_FILE_SIZE_BYTES;
import static com.epam.aidial.core.storage.http.HttpStatus.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class ProxyTest {

    @Mock
    private Vertx vertx;
    @Mock
    private HttpClient client;
    @Mock
    private ConfigStore configStore;
    @Mock
    private ApiKeyStore apiKeyStore;
    @Mock
    private LogStore logStore;
    @Mock
    private RateLimiter rateLimiter;
    @Mock
    private AccessTokenValidator accessTokenValidator;
    @Mock
    private BlobStorage storage;
    @Mock
    private ResourceService resourceService;
    @Mock
    private HealthCheckController healthCheckController;
    @Mock
    private WellKnownResourceMetadataService resourceMetadataService;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private HttpServerRequest request;

    @Mock
    private HttpServerResponse response;

    @Mock
    private ApiKeyValidation apiKeyValidation;

    @InjectMocks
    private Proxy proxy;

    @Mock
    private Logger mockLogger;

    @BeforeEach
    public void beforeEach() {
        when(resourceService.getMaxSize()).thenReturn(67108864);
        when(request.response()).thenReturn(response);
        when(request.getHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD)).thenReturn(null);
        when(request.getHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS)).thenReturn(null);
        when(response.setStatusCode(anyInt())).thenReturn(response);
        HttpConnection httpConnection = mock(HttpConnection.class);
        when(request.connection()).thenReturn(httpConnection);

        // Mock params() to avoid NullPointerException in error handling
        MultiMap params = mock(MultiMap.class);
        when(params.toString()).thenReturn("test-params");
        when(request.params()).thenReturn(params);
    }

    @AfterEach
    public void afterEach() {
        verify(response).putHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
    }

    @Test
    public void testHandle_UnsupportedHttpVersion() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_0);

        proxy.handle(request);

        verify(response).setStatusCode(HTTP_VERSION_NOT_SUPPORTED.getCode());
    }

    @Test
    public void testHandle_HttpMethodNotAllowed() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.MERGE);

        proxy.handle(request);

        verify(response).setStatusCode(METHOD_NOT_ALLOWED.getCode());
    }

    @Test
    public void testHandle_ContentBodyIsTooLarge_Multipart() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.POST);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn("multipart/form-data");
        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(headers.get(eq(HttpHeaders.CONTENT_LENGTH))).thenReturn(Long.toString(DEFAULT_MAX_UPLOADED_FILE_SIZE_BYTES + 1));

        proxy.handle(request);

        verify(response).setStatusCode(REQUEST_ENTITY_TOO_LARGE.getCode());
    }

    @Test
    public void testHandle_ContentBodyIsTooLarge() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.POST);
        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(headers.get(eq(HttpHeaders.CONTENT_LENGTH))).thenReturn(Long.toString(DEFAULT_MAX_UPLOADED_FILE_SIZE_BYTES + 1));

        proxy.handle(request);

        verify(response).setStatusCode(REQUEST_ENTITY_TOO_LARGE.getCode());
    }

    @Test
    public void testHandle_HealthCheck() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.GET);
        when(request.path()).thenReturn(HEALTH_CHECK_PATH);
        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);

        proxy.handle(request);

        verify(healthCheckController).handle(request);
    }

    @Test
    public void testHandle_MissingApiKeyAndToken() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.GET);
        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(request.path()).thenReturn("/foo");

        proxy.handle(request);

        verify(response).setStatusCode(UNAUTHORIZED.getCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST"})
    public void testHandle_MissingApiKeyAndToken_PathMatchesToolsetProxyPattern(String method) {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.valueOf(method));
        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(request.path()).thenReturn("/v1/toolset/test/mcp");
        when(resourceMetadataService.resolveResourceMetadataPath(request)).thenReturn(Optional.of("example.com"));

        proxy.handle(request);

        verify(response).setStatusCode(UNAUTHORIZED.getCode());
        verify(response).putHeader("WWW-Authenticate", "Bearer resource_metadata=\"example.com\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST"})
    public void testHandle_MissingApiKeyAndToken_PathMatchesApplicationMcpProxyPattern(String method) {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.valueOf(method));
        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(request.path()).thenReturn("/v1/deployments/test-app/mcp");
        when(resourceMetadataService.resolveResourceMetadataPath(request)).thenReturn(Optional.of("example.com"));

        proxy.handle(request);

        verify(response).setStatusCode(UNAUTHORIZED.getCode());
        verify(response).putHeader("WWW-Authenticate", "Bearer resource_metadata=\"example.com\"");
    }

    @Test
    public void testHandle_BothApiKeyAndToken_ApiKeyNotFound() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.GET);
        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(null);
        when(request.getHeader(eq(HttpHeaders.AUTHORIZATION))).thenReturn("bearer token");
        when(headers.get(eq(HEADER_API_KEY))).thenReturn("api-key");
        when(headers.get(eq(HttpHeaders.CONTENT_LENGTH))).thenReturn(Integer.toString(512));
        when(request.path()).thenReturn("/foo");

        Config config = new Config();
        when(configStore.get()).thenReturn(config);
        when(apiKeyStore.getApiKeyData(anyString(), isNull())).thenReturn(Future.failedFuture(new HttpException(UNAUTHORIZED, "Unknown API key")));

        proxy.handle(request);

        verify(response).setStatusCode(UNAUTHORIZED.getCode());
    }

    @Test
    public void testHandle_BothApiKeyAndToken_ApiKeyIsNotPerRequestKey() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.GET);
        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(null);
        when(request.getHeader(eq(HttpHeaders.AUTHORIZATION))).thenReturn("bearer token");
        when(headers.get(eq(HEADER_API_KEY))).thenReturn("api-key");
        when(headers.get(eq(HttpHeaders.CONTENT_LENGTH))).thenReturn(Integer.toString(512));
        when(request.path()).thenReturn("/foo");

        Config config = new Config();
        when(configStore.get()).thenReturn(config);
        ApiKeyData apiKeyData = new ApiKeyData();
        when(apiKeyStore.getApiKeyData(anyString(), isNull())).thenReturn(Future.succeededFuture(apiKeyData));

        proxy.handle(request);

        verify(response).setStatusCode(BAD_REQUEST.getCode());
    }

    @Test
    public void testHandle_BothApiKeyAndToken_WithPerRequestKey() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.GET);
        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(null);
        when(request.getHeader(eq(HttpHeaders.AUTHORIZATION))).thenReturn("bearer token");
        when(headers.get(eq(HEADER_API_KEY))).thenReturn("per-request-key");
        when(headers.get(eq(HttpHeaders.CONTENT_LENGTH))).thenReturn(Integer.toString(512));
        when(request.path()).thenReturn("/foo");

        Config config = new Config();
        Route route = new Route();
        route.setMethods(Set.of("GET"));
        route.setName("route");
        route.setPaths(List.of(Pattern.compile("/foo")));
        route.setResponse(new Route.Response());
        LinkedHashMap<String, Route> routes = new LinkedHashMap<>();
        routes.put("route", route);
        config.setRoutes(routes);
        when(configStore.get()).thenReturn(config);
        ApiKeyData apiKeyData = new ApiKeyData();
        Key originalKey = new Key();
        apiKeyData.setOriginalKey(originalKey);
        apiKeyData.setPerRequestKey("per-request-key");
        when(apiKeyStore.getApiKeyData("per-request-key", null)).thenReturn(Future.succeededFuture(apiKeyData));

        proxy.handle(request);

        verify(response).setStatusCode(OK.getCode());
    }

    @Test
    public void testHandle_BothApiKeyAndToken_CallerIsInterceptor() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.GET);
        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(null);
        when(request.getHeader(eq(HttpHeaders.AUTHORIZATION))).thenReturn("bearer token");
        when(headers.get(eq(HEADER_API_KEY))).thenReturn("api-key");
        when(headers.get(eq(HttpHeaders.CONTENT_LENGTH))).thenReturn(Integer.toString(512));
        when(request.path()).thenReturn("/foo");
        when(request.uri()).thenReturn("/foo");

        Config config = new Config();
        Route route = new Route();
        route.setMethods(Set.of("GET"));
        route.setName("route");
        route.setPaths(List.of(Pattern.compile("/foo")));
        route.setResponse(new Route.Response());
        LinkedHashMap<String, Route> routes = new LinkedHashMap<>();
        routes.put("route", route);
        config.setRoutes(routes);
        when(configStore.get()).thenReturn(config);

        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setPerRequestKey("per-request_key");
        apiKeyData.setInterceptors(List.of("interceptor1", "interceptor2"));
        apiKeyData.setInterceptorIndex(1);
        Key originalKey = new Key();
        apiKeyData.setOriginalKey(originalKey);
        when(apiKeyStore.getApiKeyData(anyString(), isNull())).thenReturn(Future.succeededFuture(apiKeyData));

        proxy.handle(request);

        verify(response).setStatusCode(OK.getCode());
    }

    @Test
    public void testHandle_UnknownApiKey() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.GET);
        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(null);
        when(headers.get(eq(HEADER_API_KEY))).thenReturn("bad-key");
        when(headers.get(eq(HttpHeaders.CONTENT_LENGTH))).thenReturn(Integer.toString(512));
        when(request.path()).thenReturn("/foo");
        Config config = new Config();
        config.setKeys(Map.of("key1", new Key()));
        when(configStore.get()).thenReturn(config);
        when(apiKeyStore.getApiKeyData(anyString(), isNull())).thenReturn(Future.failedFuture(new HttpException(UNAUTHORIZED, "Api key is not found")));

        when(request.response()).thenReturn(response);
        when(response.ended()).thenReturn(false);

        proxy.handle(request);

        verify(response).setStatusCode(UNAUTHORIZED.getCode());
    }

    @Test
    public void testHandle_OpenAiRequestSuccess() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.GET);
        when(request.path()).thenReturn("/foo");
        when(request.uri()).thenReturn("/foo");

        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(null);
        when(headers.get(eq(HEADER_API_KEY))).thenReturn("key1");
        when(request.getHeader(eq(HttpHeaders.AUTHORIZATION))).thenReturn("bearer key1");
        when(headers.get(eq(HttpHeaders.CONTENT_LENGTH))).thenReturn(Integer.toString(512));
        when(request.path()).thenReturn("/foo");

        Config config = new Config();
        Route route = new Route();
        route.setMethods(Set.of("GET"));
        route.setName("route");
        route.setPaths(List.of(Pattern.compile("/foo")));
        route.setResponse(new Route.Response());
        LinkedHashMap<String, Route> routes = new LinkedHashMap<>();
        routes.put("route", route);
        config.setRoutes(routes);
        when(configStore.get()).thenReturn(config);
        ApiKeyData apiKeyData = new ApiKeyData();
        Key originalKey = new Key();
        apiKeyData.setOriginalKey(originalKey);
        when(accessTokenValidator.extractClaims(anyString())).thenReturn(Future.failedFuture(new RuntimeException()));
        when(apiKeyStore.getApiKeyData("key1", null)).thenReturn(Future.succeededFuture(apiKeyData));

        proxy.handle(request);

        verify(response).setStatusCode(OK.getCode());
    }

    @Test
    public void testHandle_TryAccessToken_Success() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.GET);
        when(request.path()).thenReturn("/foo");
        when(request.uri()).thenReturn("/foo");

        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(null);
        when(headers.get(eq(HEADER_API_KEY))).thenReturn("key1");
        when(request.getHeader(eq(HttpHeaders.AUTHORIZATION))).thenReturn("bearer key1");
        when(headers.get(eq(HttpHeaders.CONTENT_LENGTH))).thenReturn(Integer.toString(512));
        when(request.path()).thenReturn("/foo");

        Config config = new Config();
        Route route = new Route();
        route.setMethods(Set.of("GET"));
        route.setName("route");
        route.setPaths(List.of(Pattern.compile("/foo")));
        route.setResponse(new Route.Response());
        LinkedHashMap<String, Route> routes = new LinkedHashMap<>();
        routes.put("route", route);
        config.setRoutes(routes);
        when(configStore.get()).thenReturn(config);
        ApiKeyData apiKeyData = new ApiKeyData();
        Key originalKey = new Key();
        apiKeyData.setOriginalKey(originalKey);
        ExtractedClaims extractedClaims = new ExtractedClaims("sub", List.of("role1"), "hash",
                ProxyUtil.MAPPER.createObjectNode(), null, null);
        when(accessTokenValidator.extractClaims(anyString())).thenReturn(Future.succeededFuture(extractedClaims));

        proxy.handle(request);

        verify(response).setStatusCode(OK.getCode());
        verify(apiKeyStore, never()).getApiKeyData(anyString(), anyString());
    }

    @Test
    public void testHandle_TryAccessToken_Failure() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.GET);
        when(request.path()).thenReturn("/foo");

        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(null);
        when(headers.get(eq(HEADER_API_KEY))).thenReturn("key1");
        when(request.getHeader(eq(HttpHeaders.AUTHORIZATION))).thenReturn("bearer key1");
        when(headers.get(eq(HttpHeaders.CONTENT_LENGTH))).thenReturn(Integer.toString(512));
        when(request.path()).thenReturn("/foo");

        Config config = new Config();
        Route route = new Route();
        route.setMethods(Set.of("GET"));
        route.setName("route");
        route.setPaths(List.of(Pattern.compile("/foo")));
        route.setResponse(new Route.Response());
        LinkedHashMap<String, Route> routes = new LinkedHashMap<>();
        routes.put("route", route);
        config.setRoutes(routes);
        when(configStore.get()).thenReturn(config);
        ApiKeyData apiKeyData = new ApiKeyData();
        Key originalKey = new Key();
        apiKeyData.setOriginalKey(originalKey);
        when(accessTokenValidator.extractClaims(anyString())).thenReturn(Future.failedFuture(new RuntimeException()));
        when(apiKeyStore.getApiKeyData(anyString(), isNull())).thenReturn(Future.failedFuture(new HttpException(UNAUTHORIZED, "Unknown API key")));

        proxy.handle(request);

        verify(response).setStatusCode(UNAUTHORIZED.getCode());
    }

    @Test
    public void testHandle_OpenAiRequestWrongApiKey() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.GET);

        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(null);
        when(headers.get(eq(HEADER_API_KEY))).thenReturn("wrong-key");
        when(request.getHeader(eq(HttpHeaders.AUTHORIZATION))).thenReturn("bearer wrong-key");
        when(headers.get(eq(HttpHeaders.CONTENT_LENGTH))).thenReturn(Integer.toString(512));
        when(request.path()).thenReturn("/foo");
        Config config = new Config();
        config.setKeys(Map.of("key1", new Key()));
        when(configStore.get()).thenReturn(config);
        when(accessTokenValidator.extractClaims(anyString())).thenReturn(Future.failedFuture(new HttpException(UNAUTHORIZED, "Bad Authorization header")));
        when(apiKeyStore.getApiKeyData(anyString(), isNull())).thenReturn(Future.failedFuture(new HttpException(UNAUTHORIZED, "Unknown API key")));
        when(request.response()).thenReturn(response);
        when(response.ended()).thenReturn(false);

        proxy.handle(request);

        verify(response).setStatusCode(UNAUTHORIZED.getCode());
    }

    @Test
    public void testHandle_SuccessApiKey() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.GET);
        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(null);
        when(headers.get(eq(HEADER_API_KEY))).thenReturn("key1");
        when(headers.get(eq(HttpHeaders.CONTENT_LENGTH))).thenReturn(Integer.toString(512));
        when(request.path()).thenReturn("/foo");
        when(request.uri()).thenReturn("/foo");

        Config config = new Config();
        Route route = new Route();
        route.setMethods(Set.of("GET"));
        route.setName("route");
        route.setPaths(List.of(Pattern.compile("/foo")));
        route.setResponse(new Route.Response());
        LinkedHashMap<String, Route> routes = new LinkedHashMap<>();
        routes.put("route", route);
        config.setRoutes(routes);
        when(configStore.get()).thenReturn(config);
        ApiKeyData apiKeyData = new ApiKeyData();
        Key originalKey = new Key();
        apiKeyData.setOriginalKey(originalKey);
        when(apiKeyStore.getApiKeyData("key1", null)).thenReturn(Future.succeededFuture(apiKeyData));

        proxy.handle(request);

        verify(response).setStatusCode(OK.getCode());
    }

    @Test
    public void testHandle_AzureOpenAiRequest() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.GET);
        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(null);
        when(request.getHeader(eq(HttpHeaders.AUTHORIZATION))).thenReturn("bearer");
        when(headers.get(eq(HEADER_API_KEY))).thenReturn("key1");
        when(headers.get(eq(HttpHeaders.CONTENT_LENGTH))).thenReturn(Integer.toString(512));
        when(request.path()).thenReturn("/foo");
        when(request.uri()).thenReturn("/foo");

        Config config = new Config();
        Route route = new Route();
        route.setMethods(Set.of("GET"));
        route.setName("route");
        route.setPaths(List.of(Pattern.compile("/foo")));
        route.setResponse(new Route.Response());
        LinkedHashMap<String, Route> routes = new LinkedHashMap<>();
        routes.put("route", route);
        config.setRoutes(routes);
        when(configStore.get()).thenReturn(config);
        ApiKeyData apiKeyData = new ApiKeyData();
        Key originalKey = new Key();
        apiKeyData.setOriginalKey(originalKey);
        when(apiKeyStore.getApiKeyData("key1", null)).thenReturn(Future.succeededFuture(apiKeyData));

        proxy.handle(request);

        verify(response).setStatusCode(OK.getCode());
    }

    @Test
    public void testHandle_SuccessAccessToken() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.GET);
        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(null);
        when(request.getHeader(eq(HttpHeaders.AUTHORIZATION))).thenReturn("bearer key1");
        when(headers.get(eq(HttpHeaders.CONTENT_LENGTH))).thenReturn(Integer.toString(512));
        when(request.path()).thenReturn("/foo");
        when(request.uri()).thenReturn("/foo");

        Config config = new Config();
        Route route = new Route();
        route.setMethods(Set.of("GET"));
        route.setName("route");
        route.setPaths(List.of(Pattern.compile("/foo")));
        route.setResponse(new Route.Response());
        LinkedHashMap<String, Route> routes = new LinkedHashMap<>();
        routes.put("route", route);
        config.setRoutes(routes);
        when(configStore.get()).thenReturn(config);
        ExtractedClaims extractedClaims = new ExtractedClaims("sub", List.of("role1"), "hash",
                ProxyUtil.MAPPER.createObjectNode(), null, null);
        when(accessTokenValidator.extractClaims(anyString())).thenReturn(Future.succeededFuture(extractedClaims));

        proxy.handle(request);

        verify(response).setStatusCode(OK.getCode());
    }

    @Test
    public void testHandle_WrongAccessTokenTreatedAsKey() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.GET);
        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(null);
        when(request.getHeader(eq(HttpHeaders.AUTHORIZATION))).thenReturn("bearer key1");
        when(headers.get(eq(HttpHeaders.CONTENT_LENGTH))).thenReturn(Integer.toString(512));
        when(request.path()).thenReturn("/foo");

        Config config = new Config();
        Route route = new Route();
        route.setMethods(Set.of("GET"));
        route.setName("route");
        route.setPaths(List.of(Pattern.compile("/foo")));
        route.setResponse(new Route.Response());
        LinkedHashMap<String, Route> routes = new LinkedHashMap<>();
        routes.put("route", route);
        config.setRoutes(routes);
        when(configStore.get()).thenReturn(config);
        when(accessTokenValidator.extractClaims(anyString())).thenReturn(Future.failedFuture(new HttpException(UNAUTHORIZED, "Bad Authorization header")));
        ApiKeyData apiKeyData = new ApiKeyData();
        Key originalKey = new Key();
        apiKeyData.setOriginalKey(originalKey);
        when(apiKeyStore.getApiKeyData("key1", null)).thenReturn(Future.succeededFuture(apiKeyData));

        proxy.handle(request);

        verify(response).setStatusCode(OK.getCode());
    }

    @Test
    public void testHandle_InvalidToken() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.GET);
        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(null);
        when(request.getHeader(eq(HttpHeaders.AUTHORIZATION))).thenReturn("token");
        when(headers.get(eq(HttpHeaders.CONTENT_LENGTH))).thenReturn(Integer.toString(512));
        when(request.path()).thenReturn("/foo");
        when(accessTokenValidator.extractClaims(eq("token"))).thenReturn(Future.failedFuture(new HttpException(UNAUTHORIZED, "Bad Authorization header")));

        proxy.handle(request);

        verify(response).setStatusCode(UNAUTHORIZED.getCode());
    }

    @Test
    public void testHandle_OptionsRequest() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.OPTIONS);
        when(request.getHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD)).thenReturn("GET");
        when(request.getHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS)).thenReturn("Api-Key");

        proxy.handle(request);

        verify(response).setStatusCode(OK.getCode());
        verify(response).putHeader(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "86400");
        verify(response).putHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET");
        verify(response).putHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "Api-Key");
    }

    @Test
    public void testHandleError_RuntimeException() {
        // Setup
        // Use the same response mock as the regular tests
        when(response.ended()).thenReturn(false);
        when(response.end(anyString())).thenReturn(Future.succeededFuture());

        // Create a new request mock that throws an exception
        HttpServerRequest errorRequest = mock(HttpServerRequest.class);
        when(errorRequest.response()).thenReturn(response);
        when(errorRequest.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(errorRequest.method()).thenReturn(HttpMethod.GET);
        when(errorRequest.path()).thenReturn("/test-path");

        // Mock params() to return a valid MultiMap to avoid NullPointerException
        MultiMap params = mock(MultiMap.class);
        when(params.toString()).thenReturn("test-params");
        when(errorRequest.params()).thenReturn(params);

        // Simulate a RuntimeException when headers() is called
        RuntimeException runtimeException = new RuntimeException("Test runtime exception");
        when(errorRequest.headers()).thenThrow(runtimeException);

        // Execute
        proxy.handle(errorRequest);

        // Verify
        verify(response).setStatusCode(500);
    }

    @Test
    public void testHandleError_SeriousError() {
        // Setup
        // Use the same response mock as the regular tests
        when(response.ended()).thenReturn(false);
        when(response.end(anyString())).thenReturn(Future.succeededFuture());

        // Create a new request mock that throws an exception
        HttpServerRequest errorRequest = mock(HttpServerRequest.class);
        when(errorRequest.response()).thenReturn(response);
        when(errorRequest.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(errorRequest.method()).thenReturn(HttpMethod.GET);
        when(errorRequest.path()).thenReturn("/test-path");

        // Mock params() to return a valid MultiMap to avoid NullPointerException
        MultiMap params = mock(MultiMap.class);
        when(params.toString()).thenReturn("test-params");
        when(errorRequest.params()).thenReturn(params);

        // Simulate a serious Error when headers() is called
        Error seriousError = new OutOfMemoryError("Test serious error");
        when(errorRequest.headers()).thenThrow(seriousError);

        // Execute
        proxy.handle(errorRequest);

        // Verify
        verify(response).setStatusCode(500);
    }

    @Test
    public void testHandleError_CheckedException() {
        // Setup
        // Use the same response mock as the regular tests
        when(response.ended()).thenReturn(false);
        when(response.end(anyString())).thenReturn(Future.succeededFuture());

        // Create a new request mock that throws an exception
        HttpServerRequest errorRequest = mock(HttpServerRequest.class);
        when(errorRequest.response()).thenReturn(response);
        when(errorRequest.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(errorRequest.method()).thenReturn(HttpMethod.GET);
        when(errorRequest.path()).thenReturn("/test-path");

        // Mock params() to return a valid MultiMap to avoid NullPointerException
        MultiMap params = mock(MultiMap.class);
        when(params.toString()).thenReturn("test-params");
        when(errorRequest.params()).thenReturn(params);

        // Simulate a runtime exception when getHeader() is called (can't use checked exceptions)
        RuntimeException runtimeException = new RuntimeException("Test runtime exception");
        when(errorRequest.getHeader(HttpHeaders.CONTENT_TYPE)).thenThrow(runtimeException);

        // Execute
        proxy.handle(errorRequest);

        // Verify
        verify(response).setStatusCode(500);
    }
}
