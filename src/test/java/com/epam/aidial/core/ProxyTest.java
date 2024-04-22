package com.epam.aidial.core;

import com.epam.aidial.core.config.ApiKeyData;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.ConfigStore;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.limiter.RateLimiter;
import com.epam.aidial.core.log.LogStore;
import com.epam.aidial.core.security.AccessTokenValidator;
import com.epam.aidial.core.security.ApiKeyStore;
import com.epam.aidial.core.storage.BlobStorage;
import com.epam.aidial.core.upstream.UpstreamBalancer;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.epam.aidial.core.Proxy.FILES_REQUEST_BODY_MAX_SIZE_BYTES;
import static com.epam.aidial.core.Proxy.HEADER_API_KEY;
import static com.epam.aidial.core.Proxy.HEALTH_CHECK_PATH;
import static com.epam.aidial.core.util.HttpStatus.BAD_REQUEST;
import static com.epam.aidial.core.util.HttpStatus.HTTP_VERSION_NOT_SUPPORTED;
import static com.epam.aidial.core.util.HttpStatus.METHOD_NOT_ALLOWED;
import static com.epam.aidial.core.util.HttpStatus.OK;
import static com.epam.aidial.core.util.HttpStatus.REQUEST_ENTITY_TOO_LARGE;
import static com.epam.aidial.core.util.HttpStatus.UNAUTHORIZED;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
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
    private UpstreamBalancer upstreamBalancer;
    @Mock
    private AccessTokenValidator accessTokenValidator;
    @Mock
    private BlobStorage storage;

    @Mock
    private HttpServerRequest request;

    @Mock
    private HttpServerResponse response;

    @InjectMocks
    private Proxy proxy;

    @BeforeEach
    public void beforeEach() {
        when(request.response()).thenReturn(response);
        when(response.setStatusCode(anyInt())).thenReturn(response);
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
        when(request.method()).thenReturn(HttpMethod.PATCH);

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
        when(headers.get(eq(HttpHeaders.CONTENT_LENGTH))).thenReturn(Integer.toString(FILES_REQUEST_BODY_MAX_SIZE_BYTES + 1));

        proxy.handle(request);

        verify(response).setStatusCode(REQUEST_ENTITY_TOO_LARGE.getCode());
    }

    @Test
    public void testHandle_ContentBodyIsTooLarge() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.POST);
        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(headers.get(eq(HttpHeaders.CONTENT_LENGTH))).thenReturn(Integer.toString(FILES_REQUEST_BODY_MAX_SIZE_BYTES + 1));

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

        verify(response).setStatusCode(OK.getCode());
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

    @Test
    public void testHandle_BothApiKeyAndToken() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.GET);
        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(null);
        when(request.getHeader(eq(HttpHeaders.AUTHORIZATION))).thenReturn("token");
        when(headers.get(eq(HEADER_API_KEY))).thenReturn("api-key");
        when(headers.get(eq(HttpHeaders.CONTENT_LENGTH))).thenReturn(Integer.toString(512));
        when(request.path()).thenReturn("/foo");

        proxy.handle(request);

        verify(response).setStatusCode(BAD_REQUEST.getCode());
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
        when(configStore.load()).thenReturn(config);
        when(apiKeyStore.getApiKeyData(anyString())).thenReturn(Future.succeededFuture());

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
        route.setMethods(Set.of(HttpMethod.GET));
        route.setName("route");
        route.setPaths(List.of(Pattern.compile("/foo")));
        route.setResponse(new Route.Response());
        LinkedHashMap<String, Route> routes = new LinkedHashMap<>();
        routes.put("route", route);
        config.setRoutes(routes);
        when(configStore.load()).thenReturn(config);
        when(apiKeyStore.getApiKeyData("key1")).thenReturn(Future.succeededFuture(new ApiKeyData()));

        proxy.handle(request);

        verify(response).setStatusCode(OK.getCode());
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
        when(configStore.load()).thenReturn(config);
        when(apiKeyStore.getApiKeyData(anyString())).thenReturn(Future.succeededFuture());

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
        route.setMethods(Set.of(HttpMethod.GET));
        route.setName("route");
        route.setPaths(List.of(Pattern.compile("/foo")));
        route.setResponse(new Route.Response());
        LinkedHashMap<String, Route> routes = new LinkedHashMap<>();
        routes.put("route", route);
        config.setRoutes(routes);
        when(configStore.load()).thenReturn(config);
        when(apiKeyStore.getApiKeyData("key1")).thenReturn(Future.succeededFuture(new ApiKeyData()));

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
        when(accessTokenValidator.extractClaims(eq("token"))).thenReturn(Future.failedFuture(new RuntimeException()));

        proxy.handle(request);

        verify(response).setStatusCode(UNAUTHORIZED.getCode());
    }
}
