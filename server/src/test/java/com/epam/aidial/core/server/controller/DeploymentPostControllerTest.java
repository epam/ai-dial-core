package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.DeploymentInterface;
import com.epam.aidial.core.config.Features;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.log.LogContext;
import com.epam.aidial.core.server.data.cache.CacheBreakpointContext;
import com.epam.aidial.core.server.limiter.RateLimiter;
import com.epam.aidial.core.server.log.LogStore;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.service.ApplicationSchemaService;
import com.epam.aidial.core.server.token.TokenStatsTracker;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.epam.aidial.core.server.upstream.UpstreamRouteProvider;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.server.vertx.stream.BufferingReadStream;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import static com.epam.aidial.core.server.Proxy.HEADER_API_KEY;
import static com.epam.aidial.core.server.Proxy.HEADER_APPLICATION_ID;
import static com.epam.aidial.core.server.Proxy.HEADER_APPLICATION_PROPERTIES;
import static com.epam.aidial.core.server.Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON;
import static com.epam.aidial.core.storage.http.HttpStatus.BAD_GATEWAY;
import static com.epam.aidial.core.storage.http.HttpStatus.FORBIDDEN;
import static com.epam.aidial.core.storage.http.HttpStatus.NOT_FOUND;
import static com.epam.aidial.core.storage.http.HttpStatus.UNSUPPORTED_MEDIA_TYPE;
import static io.vertx.core.http.HttpHeaders.AUTHORIZATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class DeploymentPostControllerTest {

    @Mock
    private ProxyContext context;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Proxy proxy;

    @Mock
    private HttpServerRequest request;

    @Mock
    private RateLimiter rateLimiter;

    @Mock
    private LogStore logStore;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private TokenStatsTracker tokenStatsTracker;

    @Mock
    private AsyncTaskExecutor taskExecutor;

    @Mock
    private ApplicationSchemaService applicationSchemaService;

    @InjectMocks
    private DeploymentPostController controller;

    @Test
    public void testUnsupportedContentType() {
        when(context.getRequest()).thenReturn(request);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn("unsupported");
        when(proxy.getTokenStatsTracker()).thenReturn(tokenStatsTracker);

        controller.handle("app1");

        verify(context).respond(eq(UNSUPPORTED_MEDIA_TYPE), anyString());
    }

    @Test
    public void testDeploymentNotFound() {
        when(context.getRequest()).thenReturn(request);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(HEADER_CONTENT_TYPE_APPLICATION_JSON);
        Config config = new Config();
        config.setApplications(new HashMap<>());
        Application app = new Application();
        config.getApplications().put("app1", app);
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor);
        when(proxy.getTokenStatsTracker()).thenReturn(tokenStatsTracker);
        when(taskExecutor.submit(any(Callable.class)))
                .thenReturn(Future.failedFuture(new ResourceNotFoundException("Not found")));

        controller.handle("unknown-app");

        verify(context).respond(eq(NOT_FOUND), anyString());
    }

    @Test
    public void testDeploymentIsNotAccessible() {
        when(context.getRequest()).thenReturn(request);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(HEADER_CONTENT_TYPE_APPLICATION_JSON);
        Config config = new Config();
        config.setApplications(new HashMap<>());
        Application app = new Application();
        app.setEndpoint("http://fake-endpoint.com");
        Features features = new Features();
        features.setAccessibleByPerRequestKey(false);
        app.setFeatures(features);
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setPerRequestKey("perRequestKey");
        when(context.getApiKeyData()).thenReturn(apiKeyData);
        config.getApplications().put("app1", app);
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor);
        when(proxy.getTokenStatsTracker()).thenReturn(tokenStatsTracker);
        when(taskExecutor.submit(any(Callable.class)))
                .thenReturn(Future.succeededFuture(app));

        controller.handle("unknown-app");

        verify(context).respond(eq(FORBIDDEN), anyString());
    }

    @Disabled
    @Test
    public void testNoRoute() {
        //TODO It looks like test doesnt reflect the actual code. It should be rewritten
        when(context.getRequest()).thenReturn(request);
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(HEADER_CONTENT_TYPE_APPLICATION_JSON);
        Config config = new Config();
        config.setApplications(new HashMap<>());
        Application application = new Application();
        application.setName("app1");
        application.setEndpoint("http://fake.com");
        config.getApplications().put("app1", application);
        when(context.getConfig()).thenReturn(config);
        UpstreamRouteProvider balancerProvider = mock(UpstreamRouteProvider.class);
        when(proxy.getUpstreamRouteProvider()).thenReturn(balancerProvider);
        UpstreamRoute endpointRoute = mock(UpstreamRoute.class);
        when(balancerProvider.get(any(Deployment.class), any(CacheBreakpointContext.class))).thenReturn(endpointRoute);
        when(endpointRoute.next()).thenThrow(new HttpException(BAD_GATEWAY, "no route"));
        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(context.getDeployment()).thenReturn(application);
        when(proxy.getTokenStatsTracker()).thenReturn(tokenStatsTracker);

        controller.handle("app1");

        verify(context).respond(any(HttpException.class));
    }


    @Disabled
    @Test
    public void testHandler_Ok() {
        //TODO It looks like test doesnt reflect the actual code. It should be rewritten
        when(context.getRequest()).thenReturn(request);
        request = mock(HttpServerRequest.class, RETURNS_DEEP_STUBS);
        when(context.getRequest()).thenReturn(request);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(HEADER_CONTENT_TYPE_APPLICATION_JSON);
        Config config = new Config();
        config.setApplications(new HashMap<>());
        Application application = new Application();
        application.setName("app1");
        application.setEndpoint("http://fake.com");
        config.getApplications().put("app1", application);
        when(context.getConfig()).thenReturn(config);
        UpstreamRouteProvider balancerProvider = mock(UpstreamRouteProvider.class);
        when(proxy.getUpstreamRouteProvider()).thenReturn(balancerProvider);
        UpstreamRoute endpointRoute = mock(UpstreamRoute.class);
        when(balancerProvider.get(any(Deployment.class), any(CacheBreakpointContext.class))).thenReturn(endpointRoute);
        when(endpointRoute.next()).thenReturn(new Upstream());
        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(context.getDeployment()).thenReturn(application);
        when(proxy.getTokenStatsTracker()).thenReturn(tokenStatsTracker);
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());

        controller.handle("app1");

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
        when(upstreamRoute.next()).thenReturn(new Upstream("endpoint", null, null, null, null, 0, 0, null));
        when(context.getUpstreamRoute()).thenReturn(upstreamRoute);
        HttpServerRequest request = mock(HttpServerRequest.class, RETURNS_DEEP_STUBS);
        when(context.getRequest()).thenReturn(request);
        when(proxy.getClient()).thenReturn(mock(HttpClient.class, RETURNS_DEEP_STUBS));
        when(proxy.getApiKeyStore()).thenReturn(mock(ApiKeyStore.class));
        when(proxy.getClientOptions()).thenReturn(new HttpClientOptions());
        ApiKeyData proxyApiKeyData = new ApiKeyData();
        proxyApiKeyData.setInterceptorIndex(0);
        when(context.getProxyApiKeyData()).thenReturn(proxyApiKeyData);

        Model model = new Model();
        model.setName("name");
        model.setEndpoint("http://host/model");
        model.setOverrideName("overrideName");
        when(context.getDeployment()).thenReturn(model);
        String body = """
                {
                    "model": "name",
                    "messages": [],
                    "stream": false
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
    public void testHandleRequestBody_NotOverrideModelName() throws IOException {
        when(context.getRequest()).thenReturn(request);
        UpstreamRoute upstreamRoute = mock(UpstreamRoute.class, RETURNS_DEEP_STUBS);
        when(upstreamRoute.next()).thenReturn(new Upstream("endpoint", null, null, null, null, 0, 0, null));
        when(context.getUpstreamRoute()).thenReturn(upstreamRoute);
        HttpServerRequest request = mock(HttpServerRequest.class, RETURNS_DEEP_STUBS);
        when(context.getRequest()).thenReturn(request);
        when(proxy.getClient()).thenReturn(mock(HttpClient.class, RETURNS_DEEP_STUBS));
        when(proxy.getApiKeyStore()).thenReturn(mock(ApiKeyStore.class));
        when(proxy.getClientOptions()).thenReturn(new HttpClientOptions());
        ApiKeyData proxyApiKeyData = new ApiKeyData();
        proxyApiKeyData.setInterceptorIndex(0);
        when(context.getProxyApiKeyData()).thenReturn(proxyApiKeyData);

        Model model = new Model();
        model.setName("name");
        model.setEndpoint("http://host/model");
        when(context.getDeployment()).thenReturn(model);
        String body = """
                {
                    "model": "name",
                    "messages": [],
                    "stream": false
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
        assertEquals("name", tree.get("model").asText());

    }

    @Test
    public void testHandleRequestBody_UseUpstreamWithoutEndpoint() {
        when(context.getRequest()).thenReturn(request);
        UpstreamRoute upstreamRoute = mock(UpstreamRoute.class, RETURNS_DEEP_STUBS);
        when(upstreamRoute.next()).thenReturn(new Upstream());
        when(context.getUpstreamRoute()).thenReturn(upstreamRoute);
        HttpServerRequest request = mock(HttpServerRequest.class, RETURNS_DEEP_STUBS);
        when(context.getRequest()).thenReturn(request);
        when(proxy.getClient()).thenReturn(mock(HttpClient.class, RETURNS_DEEP_STUBS));
        when(proxy.getApiKeyStore()).thenReturn(mock(ApiKeyStore.class));
        when(proxy.getClientOptions()).thenReturn(new HttpClientOptions());
        ApiKeyData proxyApiKeyData = new ApiKeyData();
        proxyApiKeyData.setInterceptorIndex(0);
        when(context.getProxyApiKeyData()).thenReturn(proxyApiKeyData);

        Model model = new Model();
        model.setName("name");
        model.setEndpoint("http://host/model");
        when(context.getDeployment()).thenReturn(model);
        String body = """
                {
                    "model": "name",
                    "messages": [],
                    "stream": false
                }
                """;
        Buffer requestBody = Buffer.buffer(body);

        controller.handleRequestBody(requestBody);

        verify(proxy.getClient()).request(any());
    }

    @Test
    public void testHandleRequestBody_InterfacesBaseUrl() {
        when(context.getRequest()).thenReturn(request);
        UpstreamRoute upstreamRoute = mock(UpstreamRoute.class, RETURNS_DEEP_STUBS);
        when(upstreamRoute.next()).thenReturn(new Upstream());
        when(context.getUpstreamRoute()).thenReturn(upstreamRoute);
        HttpServerRequest request = mock(HttpServerRequest.class, RETURNS_DEEP_STUBS);
        when(context.getRequest()).thenReturn(request);
        when(request.uri()).thenReturn("/openai/deployments/name/chat/completions");
        HttpClient httpClient = mock(HttpClient.class, RETURNS_DEEP_STUBS);
        when(proxy.getClient()).thenReturn(httpClient);
        when(proxy.getApiKeyStore()).thenReturn(mock(ApiKeyStore.class));
        when(proxy.getClientOptions()).thenReturn(new HttpClientOptions());
        ApiKeyData proxyApiKeyData = new ApiKeyData();
        proxyApiKeyData.setInterceptorIndex(0);
        when(context.getProxyApiKeyData()).thenReturn(proxyApiKeyData);

        Model model = new Model();
        model.setName("name");
        model.setInterfaces(Map.of(
                InterfaceType.OPENAI_CHAT_COMPLETIONS.getValue(), new DeploymentInterface("http://host")));
        when(context.getDeployment()).thenReturn(model);
        String body = """
                {
                    "model": "name",
                    "messages": [],
                    "stream": false
                }
                """;
        Buffer requestBody = Buffer.buffer(body);

        controller.handleRequestBody(requestBody);

        ArgumentCaptor<RequestOptions> captor = ArgumentCaptor.forClass(RequestOptions.class);
        verify(httpClient).request(captor.capture());
        // new flow: base_url + exact ingress path (request.uri())
        assertEquals("/openai/deployments/name/chat/completions", captor.getValue().getURI());
        assertEquals("host", captor.getValue().getHost());
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
        Model model = new Model();
        when(context.getDeployment()).thenReturn(model);
        when(context.getUserId()).thenReturn("test-user");
        HttpServerResponse response = mock(HttpServerResponse.class);
        when(context.getResponse()).thenReturn(response);
        when(response.getStatusCode()).thenReturn(HttpStatus.OK.getCode());
        when(proxy.getRateLimiter()).thenReturn(rateLimiter);
        when(proxy.getLogStore()).thenReturn(logStore);
        UpstreamRoute upstreamRoute = mock(UpstreamRoute.class, RETURNS_DEEP_STUBS);
        when(context.getUpstreamRoute()).thenReturn(upstreamRoute);
        when(context.getResponseBody()).thenReturn(Buffer.buffer());
        when(proxy.getTokenStatsTracker()).thenReturn(tokenStatsTracker);
        when(rateLimiter.increase(any(), any(), any(), any(), any())).thenReturn(Future.succeededFuture());
        when(context.getRequest()).thenReturn(request);
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.POST);
        when(request.uri()).thenReturn("/test");
        BufferingReadStream bufferingReadStream = mock(BufferingReadStream.class);

        controller.handleResponse(bufferingReadStream);

        verify(rateLimiter).increase(eq(model), any(), any(), any(), any());
        verify(context).setTokenUsage(any(TokenUsage.class));
        verify(logStore).save(any(LogContext.class));
        verify(tokenStatsTracker).endSpan(eq(context));
        verify(bufferingReadStream).end(response);
    }

    @Test
    public void testHandleResponse_App() {
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
        when(context.getRequest()).thenReturn(request);
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.POST);
        when(request.uri()).thenReturn("/test");
        BufferingReadStream bufferingReadStream = mock(BufferingReadStream.class);

        controller.handleResponse(bufferingReadStream);

        verify(rateLimiter, never()).increase(any(), any(), any(), any(), any());
        verify(tokenStatsTracker).getTokenStats(eq(context));
        verify(context).setTokenUsage(any(TokenUsage.class));
        verify(logStore).save(any(LogContext.class));
        verify(tokenStatsTracker).endSpan(eq(context));
        verify(bufferingReadStream).end(response);
    }

    @Test
    public void testCustomApplication() {
        when(context.getRequest()).thenReturn(request);
        request = mock(HttpServerRequest.class, RETURNS_DEEP_STUBS);
        when(context.getRequest()).thenReturn(request);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(HEADER_CONTENT_TYPE_APPLICATION_JSON);
        Application application = new Application();
        application.setName("applications/bucket/app1");
        application.setEndpoint("http://fake.com");
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor);
        when(taskExecutor.submit(any(Callable.class))).thenReturn(Future.succeededFuture(application));
        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(context.getDeployment()).thenReturn(application);
        when(proxy.getTokenStatsTracker()).thenReturn(tokenStatsTracker);
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());
        when(applicationSchemaService.modifyEndpointsForCustomApplication(eq(application))).thenReturn(application);
        when(proxy.getApplicationSchemaService()).thenReturn(applicationSchemaService);
        when(tokenStatsTracker.startSpan(any())).thenReturn(Future.succeededFuture());

        controller.handle("applications/bucket/app1");

        verify(tokenStatsTracker).startSpan(eq(context));
    }

    @Test
    public void testHandleProxyRequest_SetsApplicationHeaders_WhenCustomApplication() {
        Application application = new Application();
        application.setName("customApp");
        application.setApplicationTypeSchemaId(URI.create("customSchemaId"));

        Map<String, Object> customProps = new HashMap<>();
        customProps.put("serverFile", "files/public/some-path/server-file.ext");
        customProps.put("clientFile", "files/public/some-path/client-file.ext");
        application.setApplicationProperties(customProps);

        when(context.getDeployment()).thenReturn(application);

        HttpServerRequest request = mock(HttpServerRequest.class);
        when(context.getRequest()).thenReturn(request);
        when(request.headers()).thenReturn(new HeadersMultiMap());

        HttpClientRequest proxyRequest = mock(HttpClientRequest.class, RETURNS_DEEP_STUBS);
        MultiMap proxyHeaders = new HeadersMultiMap();
        when(proxyRequest.headers()).thenReturn(proxyHeaders);

        ApiKeyData proxyApiKeyData = new ApiKeyData();
        proxyApiKeyData.setPerRequestKey("test-key");
        when(context.getProxyApiKeyData()).thenReturn(proxyApiKeyData);

        Buffer requestBody = Buffer.buffer("{}");
        when(context.getRequestBody()).thenReturn(requestBody);
        when(proxy.getApplicationSchemaService()).thenReturn(applicationSchemaService);
        doAnswer(ans -> {
            ApplicationSchemaService.MetadataPropertiesConsumer consumer = ans.getArgument(1);
            Map<String, Object> props = Map.of("serverFile", "files/public/some-path/server-file.ext");
            consumer.accept(props, true);
            return null;
        }).when(applicationSchemaService).consumeMetadataProperties(eq(application), any(ApplicationSchemaService.MetadataPropertiesConsumer.class));

        controller.handleProxyRequest(proxyRequest);

        verify(proxyRequest).putHeader(eq(HEADER_APPLICATION_ID), eq("customApp"));

        verify(proxyRequest).putHeader(eq(HEADER_APPLICATION_PROPERTIES), eq("{\"serverFile\":\"files/public/some-path/server-file.ext\"}"));
    }

    @Test
    public void testHandleProxyRequest_DoesNotSetApplicationHeaders_WhenNotCustomApplication() {
        Model model = new Model();
        model.setName("modelName");

        when(context.getDeployment()).thenReturn(model);

        HttpServerRequest request = mock(HttpServerRequest.class);
        when(context.getRequest()).thenReturn(request);
        when(request.headers()).thenReturn(new HeadersMultiMap());

        HttpClientRequest proxyRequest = mock(HttpClientRequest.class, RETURNS_DEEP_STUBS);
        MultiMap proxyHeaders = new HeadersMultiMap();
        when(proxyRequest.headers()).thenReturn(proxyHeaders);

        ApiKeyData proxyApiKeyData = new ApiKeyData();
        proxyApiKeyData.setPerRequestKey("test-key");
        when(context.getProxyApiKeyData()).thenReturn(proxyApiKeyData);

        Buffer requestBody = Buffer.buffer("{}");
        when(context.getRequestBody()).thenReturn(requestBody);

        controller.handleProxyRequest(proxyRequest);

        verify(proxyRequest, never()).putHeader(eq(HEADER_APPLICATION_ID), anyString());
        verify(proxyRequest, never()).putHeader(eq(HEADER_APPLICATION_PROPERTIES), anyString());
    }

    @Test
    public void testHandleProxyRequest_SetsApplicationPropertiesHeader_WhenApplicationHasCustomSchemaIdAndCustomFields() {
        Application application = new Application();
        application.setName("customApp");
        application.setApplicationTypeSchemaId(URI.create("customSchemaId"));

        Map<String, Object> customProps = new HashMap<>();
        customProps.put("serverFile", "files/public/some-path/server-file.ext");
        customProps.put("clientFile", "files/public/some-path/client-file.ext");
        application.setApplicationProperties(customProps);

        when(context.getDeployment()).thenReturn(application);

        HttpServerRequest request = mock(HttpServerRequest.class);
        when(context.getRequest()).thenReturn(request);
        when(request.headers()).thenReturn(new HeadersMultiMap());

        HttpClientRequest proxyRequest = mock(HttpClientRequest.class, RETURNS_DEEP_STUBS);
        MultiMap proxyHeaders = new HeadersMultiMap();
        when(proxyRequest.headers()).thenReturn(proxyHeaders);

        ApiKeyData proxyApiKeyData = new ApiKeyData();
        proxyApiKeyData.setPerRequestKey("test-key");
        when(context.getProxyApiKeyData()).thenReturn(proxyApiKeyData);

        Buffer requestBody = Buffer.buffer("{\"custom_fields\":{\"foo\":\"bar\"}}");
        when(context.getRequestBody()).thenReturn(requestBody);
        when(proxy.getApplicationSchemaService()).thenReturn(applicationSchemaService);
        doAnswer(ans -> {
            ApplicationSchemaService.MetadataPropertiesConsumer consumer = ans.getArgument(1);
            Map<String, Object> props = Map.of("serverFile", "files/public/some-path/server-file.ext");
            consumer.accept(props, true);
            return null;
        }).when(applicationSchemaService).consumeMetadataProperties(eq(application), any(ApplicationSchemaService.MetadataPropertiesConsumer.class));

        controller.handleProxyRequest(proxyRequest);

        verify(proxyRequest).putHeader(eq(HEADER_APPLICATION_ID), eq("customApp"));

        verify(proxyRequest).putHeader(eq(HEADER_APPLICATION_PROPERTIES),
                argThat((String jsonStr) ->
                        jsonStr.contains("\"serverFile\":\"files/public/some-path/server-file.ext\"")));
    }

    @Test
    public void testHandleProxyRequest_DoesNotSetApplicationPropertiesHeader_WhenFlagIsFalse() {
        Application application = new Application();
        application.setName("customApp");
        application.setApplicationTypeSchemaId(URI.create("customSchemaId"));

        Map<String, Object> customProps = new HashMap<>();
        customProps.put("serverFile", "files/public/some-path/server-file.ext");
        customProps.put("clientFile", "files/public/some-path/client-file.ext");
        application.setApplicationProperties(customProps);

        when(context.getDeployment()).thenReturn(application);

        HttpServerRequest request = mock(HttpServerRequest.class);
        when(context.getRequest()).thenReturn(request);
        when(request.headers()).thenReturn(new HeadersMultiMap());

        HttpClientRequest proxyRequest = mock(HttpClientRequest.class, RETURNS_DEEP_STUBS);
        MultiMap proxyHeaders = new HeadersMultiMap();
        when(proxyRequest.headers()).thenReturn(proxyHeaders);

        ApiKeyData proxyApiKeyData = new ApiKeyData();
        proxyApiKeyData.setPerRequestKey("test-key");
        when(context.getProxyApiKeyData()).thenReturn(proxyApiKeyData);

        Buffer requestBody = Buffer.buffer("{}");
        when(context.getRequestBody()).thenReturn(requestBody);

        controller.handleProxyRequest(proxyRequest);

        verify(proxyRequest).putHeader(eq(HEADER_APPLICATION_ID), eq("customApp"));

        verify(proxyRequest, never()).putHeader(eq(HEADER_APPLICATION_PROPERTIES), anyString());
    }
}
