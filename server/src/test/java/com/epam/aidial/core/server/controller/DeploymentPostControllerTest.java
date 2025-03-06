package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Features;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.limiter.RateLimiter;
import com.epam.aidial.core.server.log.LogStore;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.service.ResourceNotFoundException;
import com.epam.aidial.core.server.token.TokenStatsTracker;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.epam.aidial.core.server.upstream.UpstreamRouteProvider;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.stream.BufferingReadStream;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
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

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
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
        when(vertx.executeBlocking(any(Callable.class), eq(false)))
                .thenReturn(Future.failedFuture(new ResourceNotFoundException("Not found")));
        when(context.getProxy()).thenReturn(proxy);

        controller.handle("unknown-app", "chat/completions");

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
        when(context.getConfig()).thenReturn(config);
        when(proxy.getVertx()).thenReturn(vertx);
        when(proxy.getTokenStatsTracker()).thenReturn(tokenStatsTracker);
        when(vertx.executeBlocking(any(Callable.class), eq(false)))
                .thenReturn(Future.succeededFuture(app));
        when(context.getProxy()).thenReturn(proxy);

        controller.handle("unknown-app", "chat/completions");

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
        when(balancerProvider.get(any(Deployment.class))).thenReturn(endpointRoute);
        when(endpointRoute.next()).thenThrow(new HttpException(BAD_GATEWAY, "no route"));
        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(context.getDeployment()).thenReturn(application);
        when(proxy.getTokenStatsTracker()).thenReturn(tokenStatsTracker);

        controller.handle("app1", "chat/completions");

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
        when(balancerProvider.get(any(Deployment.class))).thenReturn(endpointRoute);
        when(endpointRoute.next()).thenReturn(new Upstream());
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
        when(upstreamRoute.next()).thenReturn(new Upstream());
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
    public void testHandleRequestBody_NotOverrideModelName() {
        when(context.getRequest()).thenReturn(request);
        UpstreamRoute upstreamRoute = mock(UpstreamRoute.class, RETURNS_DEEP_STUBS);
        when(upstreamRoute.next()).thenReturn(new Upstream());
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
                    "messages": [],
                    "stream": false
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
        when(rateLimiter.increase(any(ProxyContext.class), eq(model))).thenReturn(Future.succeededFuture());
        when(tokenStatsTracker.updateModelStats(context)).thenReturn(Future.succeededFuture());
        BufferingReadStream bufferingReadStream = mock(BufferingReadStream.class);

        controller.handleResponse(bufferingReadStream);

        verify(rateLimiter).increase(eq(context), eq(model));
        verify(context).setTokenUsage(any(TokenUsage.class));
        verify(logStore).save(eq(context));
        verify(tokenStatsTracker).endSpan(eq(context));
        verify(bufferingReadStream).end(response);
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
        BufferingReadStream bufferingReadStream = mock(BufferingReadStream.class);

        controller.handleResponse(bufferingReadStream);

        verify(rateLimiter, never()).increase(eq(context), eq(app));
        verify(tokenStatsTracker).getTokenStats(eq(context));
        verify(context).setTokenUsage(any(TokenUsage.class));
        verify(logStore).save(eq(context));
        verify(tokenStatsTracker).endSpan(eq(context));
        verify(bufferingReadStream).end(response);
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
        application.setEndpoint("http://fake.com");
        when(proxy.getVertx()).thenReturn(vertx);
        when(vertx.executeBlocking(any(Callable.class), eq(false))).thenReturn(Future.succeededFuture(application));
        UpstreamRouteProvider balancerProvider = mock(UpstreamRouteProvider.class);
        when(proxy.getUpstreamRouteProvider()).thenReturn(balancerProvider);
        UpstreamRoute endpointRoute = mock(UpstreamRoute.class);
        when(balancerProvider.get(any(Deployment.class))).thenReturn(endpointRoute);
        when(endpointRoute.next()).thenReturn(new Upstream());
        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(context.getDeployment()).thenReturn(application);
        when(proxy.getTokenStatsTracker()).thenReturn(tokenStatsTracker);
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());
        when(context.getProxy()).thenReturn(proxy);

        controller.handle("applications/bucket/app1", "chat/completions");

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

        String schema = """
                {
                  "$schema": "https://dial.epam.com/application_type_schemas/schema#",
                  "$id": "https://mydial.epam.com/custom_application_schemas/specific_application_type",
                  "dial:applicationTypeEditorUrl": "https://mydial.epam.com/specific_application_type_editor",
                  "dial:applicationTypeDisplayName": "Specific Application Type",
                  "dial:applicationTypeCompletionEndpoint": "http://specific_application_service/opeani/v1/completion",
                  "properties": {
                    "clientFile": {
                      "type": "string",
                      "format": "dial-file-encoded",
                      "dial:meta": {
                        "dial:propertyKind": "client",
                        "dial:propertyOrder": 1
                      },
                      "dial:file": true
                    },
                    "serverFile": {
                      "type": "string",
                      "format": "dial-file-encoded",
                      "dial:meta": {
                        "dial:propertyKind": "server",
                        "dial:propertyOrder": 2
                      },
                      "dial:file": true
                    }
                  },
                  "required": [
                    "clientFile"
                  ]
                }""";

        when(context.getDeployment()).thenReturn(application);
        when(context.getConfig()).thenReturn(mock(Config.class));
        when(context.getConfig().getCustomApplicationSchema(any(URI.class))).thenReturn(schema);

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
        when(context.getRequestHeaders()).thenReturn(Map.of());

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

        String schema = """
                {
                  "$schema": "https://dial.epam.com/application_type_schemas/schema#",
                  "$id": "https://mydial.epam.com/custom_application_schemas/specific_application_type",
                  "properties": {
                    "clientFile": {
                      "type": "string",
                      "format": "dial-file-encoded",
                      "dial:meta": {
                        "dial:propertyKind": "client",
                        "dial:propertyOrder": 1
                      },
                      "dial:file": true
                    },
                    "serverFile": {
                      "type": "string",
                      "format": "dial-file-encoded",
                      "dial:meta": {
                        "dial:propertyKind": "server",
                        "dial:propertyOrder": 2
                      },
                      "dial:file": true
                    }
                  },
                  "required": ["clientFile"]
                }""";

        when(context.getDeployment()).thenReturn(application);
        when(context.getConfig()).thenReturn(mock(Config.class));
        when(context.getConfig().getCustomApplicationSchema(any(URI.class))).thenReturn(schema);

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
        when(context.getRequestHeaders()).thenReturn(Map.of());

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

        String schema = """
                {
                  "$schema": "https://dial.epam.com/application_type_schemas/schema#",
                  "$id": "https://mydial.epam.com/custom_application_schemas/specific_application_type",
                  "dial:usePropertiesHeader": false,
                  "properties": {
                    "clientFile": {
                      "type": "string",
                      "format": "dial-file-encoded",
                      "dial:meta": {
                        "dial:propertyKind": "client",
                        "dial:propertyOrder": 1
                      },
                      "dial:file": true
                    },
                    "serverFile": {
                      "type": "string",
                      "format": "dial-file-encoded",
                      "dial:meta": {
                        "dial:propertyKind": "server",
                        "dial:propertyOrder": 2
                      },
                      "dial:file": true
                    }
                  },
                  "required": ["clientFile"]
                }""";

        when(context.getDeployment()).thenReturn(application);
        when(context.getConfig()).thenReturn(mock(Config.class));
        when(context.getConfig().getCustomApplicationSchema(any(URI.class))).thenReturn(schema);

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
        when(context.getRequestHeaders()).thenReturn(Map.of());

        controller.handleProxyRequest(proxyRequest);

        verify(proxyRequest).putHeader(eq(HEADER_APPLICATION_ID), eq("customApp"));

        verify(proxyRequest, never()).putHeader(eq(HEADER_APPLICATION_PROPERTIES), anyString());
    }
}
