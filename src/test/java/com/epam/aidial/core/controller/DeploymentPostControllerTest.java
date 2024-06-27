package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.ApiKeyData;
import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.limiter.RateLimiter;
import com.epam.aidial.core.log.LogStore;
import com.epam.aidial.core.security.ApiKeyStore;
import com.epam.aidial.core.token.TokenStatsTracker;
import com.epam.aidial.core.token.TokenUsage;
import com.epam.aidial.core.upstream.UpstreamBalancer;
import com.epam.aidial.core.upstream.UpstreamProvider;
import com.epam.aidial.core.upstream.UpstreamRoute;
import com.epam.aidial.core.util.BufferingReadStream;
import com.epam.aidial.core.util.HttpStatus;
import com.epam.aidial.core.util.ProxyUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.Callable;

import static com.epam.aidial.core.Proxy.HEADER_API_KEY;
import static com.epam.aidial.core.Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON;
import static com.epam.aidial.core.util.HttpStatus.BAD_GATEWAY;
import static com.epam.aidial.core.util.HttpStatus.FORBIDDEN;
import static com.epam.aidial.core.util.HttpStatus.NOT_FOUND;
import static com.epam.aidial.core.util.HttpStatus.UNSUPPORTED_MEDIA_TYPE;
import static io.vertx.core.http.HttpHeaders.AUTHORIZATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class DeploymentPostControllerTest {

    @Mock
    private ProxyContext context;
    @Mock
    private Proxy proxy;

    @Mock
    private HttpServerRequest request;

    @Mock
    private ApiKeyStore apiKeyStore;

    @Mock
    private RateLimiter rateLimiter;

    @Mock
    private LogStore logStore;

    @Mock
    private TokenStatsTracker tokenStatsTracker;

    @Mock
    private Vertx vertx;

    @InjectMocks
    private DeploymentPostController controller;

    @Test
    public void testUnsupportedContentType() {
        when(context.getRequest()).thenReturn(request);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn("unsupported");
        when(proxy.getTokenStatsTracker()).thenReturn(tokenStatsTracker);

        controller.handle("app1", "api");

        verify(context).respond(eq(UNSUPPORTED_MEDIA_TYPE), anyString());
    }

    @Test
    public void testForbiddenDeployment() {
        when(context.getRequest()).thenReturn(request);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(HEADER_CONTENT_TYPE_APPLICATION_JSON);
        Config config = new Config();
        config.setApplications(new HashMap<>());
        Application app = new Application();
        app.setName("app1");
        app.setUserRoles(Set.of("role1"));
        config.getApplications().put("app1", app);
        when(context.getConfig()).thenReturn(config);
        when(proxy.getTokenStatsTracker()).thenReturn(tokenStatsTracker);

        controller.handle("app1", "chat/completions");

        verify(context).respond(eq(FORBIDDEN), anyString());
    }

    @Test
    public void testDeploymentNotFound() {
        when(context.getRequest()).thenReturn(request);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(HEADER_CONTENT_TYPE_APPLICATION_JSON);
        Config config = new Config();
        config.setApplications(new HashMap<>());
        Application app = new Application();
        config.getApplications().put("app1", app);
        when(context.getConfig()).thenReturn(config);
        when(proxy.getVertx()).thenReturn(vertx);
        when(proxy.getTokenStatsTracker()).thenReturn(tokenStatsTracker);
        when(vertx.executeBlocking(any(Callable.class), eq(false))).thenReturn(Future.succeededFuture(null));

        controller.handle("unknown-app", "chat/completions");

        verify(context).respond(eq(NOT_FOUND), anyString());
    }

    @Test
    public void testNoRoute() {
        when(context.getRequest()).thenReturn(request);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(HEADER_CONTENT_TYPE_APPLICATION_JSON);
        Config config = new Config();
        config.setApplications(new HashMap<>());
        Application application = new Application();
        application.setName("app1");
        config.getApplications().put("app1", application);
        when(context.getConfig()).thenReturn(config);
        UpstreamBalancer balancer = mock(UpstreamBalancer.class);
        when(proxy.getUpstreamBalancer()).thenReturn(balancer);
        UpstreamRoute endpointRoute = mock(UpstreamRoute.class);
        when(balancer.balance(any(UpstreamProvider.class))).thenReturn(endpointRoute);
        when(endpointRoute.hasNext()).thenReturn(false);
        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(context.getDeployment()).thenReturn(application);
        when(proxy.getTokenStatsTracker()).thenReturn(tokenStatsTracker);

        controller.handle("app1", "chat/completions");

        verify(context).respond(eq(BAD_GATEWAY), anyString());
    }

    @Test
    public void testHandler_Ok() {
        when(context.getRequest()).thenReturn(request);
        request = mock(HttpServerRequest.class, RETURNS_DEEP_STUBS);
        when(context.getRequest()).thenReturn(request);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(HEADER_CONTENT_TYPE_APPLICATION_JSON);
        Config config = new Config();
        config.setApplications(new HashMap<>());
        Application application = new Application();
        application.setName("app1");
        config.getApplications().put("app1", application);
        when(context.getConfig()).thenReturn(config);
        UpstreamBalancer balancer = mock(UpstreamBalancer.class);
        when(proxy.getUpstreamBalancer()).thenReturn(balancer);
        UpstreamRoute endpointRoute = mock(UpstreamRoute.class);
        when(balancer.balance(any(UpstreamProvider.class))).thenReturn(endpointRoute);
        when(endpointRoute.hasNext()).thenReturn(true);
        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(context.getDeployment()).thenReturn(application);
        when(proxy.getTokenStatsTracker()).thenReturn(tokenStatsTracker);
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());

        controller.handle("app1", "chat/completions");

        verify(tokenStatsTracker).startSpan(eq(context));
    }

    @Test
    public void testHandleProxyRequest_NotPropagateAuthHeader() {
        when(context.getRequest()).thenReturn(request);

        Config config = new Config();
        config.setApplications(new HashMap<>());
        Application application = new Application();
        application.setName("app1");
        application.setForwardAuthToken(false);
        application.setEndpoint("http://app1/chat");
        config.getApplications().put("app1", application);

        MultiMap headers = new HeadersMultiMap();
        headers.add(AUTHORIZATION, "token");
        when(request.headers()).thenReturn(headers);
        when(context.getDeployment()).thenReturn(application);

        HttpClientRequest proxyRequest = mock(HttpClientRequest.class, RETURNS_DEEP_STUBS);
        MultiMap proxyHeaders = new HeadersMultiMap();
        when(proxyRequest.headers()).thenReturn(proxyHeaders);

        ApiKeyData proxyApiKeyData = new ApiKeyData();
        proxyApiKeyData.setPerRequestKey("key1");
        when(context.getProxyApiKeyData()).thenReturn(proxyApiKeyData);

        Buffer requestBody = Buffer.buffer();
        when(context.getRequestBody()).thenReturn(requestBody);

        controller.handleProxyRequest(proxyRequest);

        assertNull(proxyHeaders.get(AUTHORIZATION));
        assertEquals("key1", proxyHeaders.get(HEADER_API_KEY));
    }

    @Test
    public void testHandleRequestBody_OverrideModelName() throws IOException {
        when(context.getRequest()).thenReturn(request);
        UpstreamRoute upstreamRoute = mock(UpstreamRoute.class, RETURNS_DEEP_STUBS);
        when(upstreamRoute.hasNext()).thenReturn(true);
        when(context.getUpstreamRoute()).thenReturn(upstreamRoute);
        HttpServerRequest request = mock(HttpServerRequest.class, RETURNS_DEEP_STUBS);
        when(context.getRequest()).thenReturn(request);
        when(proxy.getClient()).thenReturn(mock(HttpClient.class, RETURNS_DEEP_STUBS));
        when(proxy.getApiKeyStore()).thenReturn(mock(ApiKeyStore.class));

        Model model = new Model();
        model.setName("name");
        model.setEndpoint("http://host/model");
        model.setOverrideName("overrideName");
        when(context.getDeployment()).thenReturn(model);
        String body = """
                {
                    "model": "name",
                    "messages": []
                }
                """;
        Buffer requestBody = Buffer.buffer(body);
        when(context.getRequestBody()).thenCallRealMethod();
        doCallRealMethod().when(context).setRequestBody(any());

        controller.handleRequestBody(requestBody);

        Buffer updatedBody = context.getRequestBody();
        assertNotNull(updatedBody);

        byte[] content = updatedBody.getBytes();
        ObjectNode tree = (ObjectNode) ProxyUtil.MAPPER.readTree(content);
        assertEquals(tree.get("model").asText(), "overrideName");

    }

    @Test
    public void testHandleRequestBody_NotOverrideModelName() {
        when(context.getRequest()).thenReturn(request);
        UpstreamRoute upstreamRoute = mock(UpstreamRoute.class, RETURNS_DEEP_STUBS);
        when(upstreamRoute.hasNext()).thenReturn(true);
        when(context.getUpstreamRoute()).thenReturn(upstreamRoute);
        HttpServerRequest request = mock(HttpServerRequest.class, RETURNS_DEEP_STUBS);
        when(context.getRequest()).thenReturn(request);
        when(proxy.getClient()).thenReturn(mock(HttpClient.class, RETURNS_DEEP_STUBS));
        when(proxy.getApiKeyStore()).thenReturn(mock(ApiKeyStore.class));

        Model model = new Model();
        model.setName("name");
        model.setEndpoint("http://host/model");
        when(context.getDeployment()).thenReturn(model);
        String body = """
                {
                    "model": "name",
                    "messages": []
                }
                """;
        Buffer requestBody = Buffer.buffer(body);
        when(context.getRequestBody()).thenCallRealMethod();
        doCallRealMethod().when(context).setRequestBody(any());

        controller.handleRequestBody(requestBody);

        assertEquals(requestBody, context.getRequestBody());

    }

    @Test
    public void testHandleProxyRequest_PropagateAuthHeader() {
        when(context.getRequest()).thenReturn(request);
        Application application = new Application();
        application.setName("app1");
        application.setEndpoint("http://app1/chat");
        application.setForwardAuthToken(true);

        when(context.getDeployment()).thenReturn(application);

        MultiMap headers = new HeadersMultiMap();
        headers.add(HEADER_API_KEY, "k1");
        headers.add(AUTHORIZATION, "token");
        when(request.headers()).thenReturn(headers);
        when(context.getDeployment()).thenReturn(application);

        HttpClientRequest proxyRequest = mock(HttpClientRequest.class, RETURNS_DEEP_STUBS);
        MultiMap proxyHeaders = new HeadersMultiMap();
        when(proxyRequest.headers()).thenReturn(proxyHeaders);

        Buffer requestBody = Buffer.buffer();
        when(context.getRequestBody()).thenReturn(requestBody);

        ApiKeyData proxyApiKeyData = new ApiKeyData();
        proxyApiKeyData.setPerRequestKey("key1");
        when(context.getProxyApiKeyData()).thenReturn(proxyApiKeyData);

        controller.handleProxyRequest(proxyRequest);

        assertEquals("key1", proxyHeaders.get(HEADER_API_KEY));
        assertEquals("token", proxyHeaders.get(AUTHORIZATION));

    }

    @Test
    public void testHandleResponse_Model() {
        when(context.getResponseStream()).thenReturn(mock(BufferingReadStream.class, RETURNS_DEEP_STUBS));
        Model model = new Model();
        when(context.getDeployment()).thenReturn(model);
        HttpServerResponse response = mock(HttpServerResponse.class);
        when(context.getResponse()).thenReturn(response);
        when(response.getStatusCode()).thenReturn(HttpStatus.OK.getCode());
        when(proxy.getRateLimiter()).thenReturn(rateLimiter);
        when(proxy.getLogStore()).thenReturn(logStore);
        UpstreamRoute upstreamRoute = mock(UpstreamRoute.class, RETURNS_DEEP_STUBS);
        when(context.getUpstreamRoute()).thenReturn(upstreamRoute);
        when(context.getResponseBody()).thenReturn(Buffer.buffer());
        when(proxy.getTokenStatsTracker()).thenReturn(tokenStatsTracker);
        when(rateLimiter.increase(any(ProxyContext.class))).thenReturn(Future.succeededFuture());

        controller.handleResponse();

        verify(rateLimiter).increase(eq(context));
        verify(context).setTokenUsage(any(TokenUsage.class));
        verify(logStore).save(eq(context));
        verify(tokenStatsTracker).endSpan(eq(context));
    }

    @Test
    public void testHandleResponse_App() {
        when(context.getResponseStream()).thenReturn(mock(BufferingReadStream.class, RETURNS_DEEP_STUBS));
        Application app = new Application();
        when(context.getDeployment()).thenReturn(app);

        when(proxy.getLogStore()).thenReturn(logStore);
        UpstreamRoute upstreamRoute = mock(UpstreamRoute.class, RETURNS_DEEP_STUBS);
        when(context.getUpstreamRoute()).thenReturn(upstreamRoute);
        HttpServerResponse response = mock(HttpServerResponse.class);
        when(context.getResponse()).thenReturn(response);
        when(response.getStatusCode()).thenReturn(HttpStatus.OK.getCode());
        when(context.getResponseBody()).thenReturn(Buffer.buffer());
        when(proxy.getTokenStatsTracker()).thenReturn(tokenStatsTracker);
        when(tokenStatsTracker.getTokenStats(eq(context))).thenReturn(Future.succeededFuture(new TokenUsage()));

        controller.handleResponse();

        verify(rateLimiter, never()).increase(eq(context));
        verify(tokenStatsTracker).getTokenStats(eq(context));
        verify(context).setTokenUsage(any(TokenUsage.class));
        verify(logStore).save(eq(context));
        verify(tokenStatsTracker).endSpan(eq(context));
    }

    @Test
    public void testCustomApplication() {
        when(context.getRequest()).thenReturn(request);
        request = mock(HttpServerRequest.class, RETURNS_DEEP_STUBS);
        when(context.getRequest()).thenReturn(request);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(HEADER_CONTENT_TYPE_APPLICATION_JSON);
        Config config = new Config();
        config.setApplications(new HashMap<>());
        when(context.getConfig()).thenReturn(config);
        Application application = new Application();
        application.setName("applications/bucket/app1");
        when(proxy.getVertx()).thenReturn(vertx);
        when(vertx.executeBlocking(any(Callable.class), eq(false))).thenReturn(Future.succeededFuture(application));
        UpstreamBalancer balancer = mock(UpstreamBalancer.class);
        when(proxy.getUpstreamBalancer()).thenReturn(balancer);
        UpstreamRoute endpointRoute = mock(UpstreamRoute.class);
        when(balancer.balance(any(UpstreamProvider.class))).thenReturn(endpointRoute);
        when(endpointRoute.hasNext()).thenReturn(true);
        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(context.getDeployment()).thenReturn(application);
        when(proxy.getTokenStatsTracker()).thenReturn(tokenStatsTracker);
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());

        controller.handle("applications/bucket/app1", "chat/completions");

        verify(tokenStatsTracker).startSpan(eq(context));
    }

}
