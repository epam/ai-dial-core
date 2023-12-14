package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.upstream.UpstreamBalancer;
import com.epam.aidial.core.upstream.UpstreamProvider;
import com.epam.aidial.core.upstream.UpstreamRoute;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;

import static com.epam.aidial.core.Proxy.HEADER_API_KEY;
import static com.epam.aidial.core.Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON;
import static com.epam.aidial.core.util.HttpStatus.BAD_GATEWAY;
import static com.epam.aidial.core.util.HttpStatus.FORBIDDEN;
import static com.epam.aidial.core.util.HttpStatus.UNSUPPORTED_MEDIA_TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
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

    @InjectMocks
    private DeploymentPostController controller;

    @BeforeEach
    public void beforeEach() {
        when(context.getRequest()).thenReturn(request);
    }

    @Test
    public void testUnsupportedContentType() {
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn("unsupported");

        controller.handle("app1", "api");

        verify(context).respond(eq(UNSUPPORTED_MEDIA_TYPE), anyString());

    }

    @Test
    public void testForbiddenDeployment() {
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(HEADER_CONTENT_TYPE_APPLICATION_JSON);
        Config config = new Config();
        config.setApplications(new HashMap<>());
        config.getApplications().put("app1", new Application());
        when(context.getConfig()).thenReturn(config);

        controller.handle("app1", "api");

        verify(context).respond(eq(FORBIDDEN), anyString());
    }

    @Test
    public void testNoRoute() {
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

        controller.handle("app1", "chat/completions");

        verify(context).respond(eq(BAD_GATEWAY), anyString());
    }

    @Test
    public void testPropagateDeploymentApiHeader() {
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(HEADER_CONTENT_TYPE_APPLICATION_JSON);

        Config config = new Config();
        config.setApplications(new HashMap<>());
        Application application = new Application();
        application.setName("app1");
        application.setEndpoint("http://app1/chat");
        application.setApiKey("k2");
        config.getApplications().put("app1", application);
        when(context.getConfig()).thenReturn(config);

        UpstreamBalancer balancer = mock(UpstreamBalancer.class);
        when(proxy.getUpstreamBalancer()).thenReturn(balancer);
        UpstreamRoute endpointRoute = mock(UpstreamRoute.class, RETURNS_DEEP_STUBS);
        when(balancer.balance(any(UpstreamProvider.class))).thenReturn(endpointRoute);
        Upstream upstream = new Upstream();
        when(endpointRoute.next()).thenReturn(upstream);
        when(endpointRoute.hasNext()).thenReturn(true);
        when(context.getUpstreamRoute()).thenReturn(endpointRoute);

        MultiMap headers = new HeadersMultiMap();
        headers.add(HEADER_API_KEY, "k1");
        when(request.headers()).thenReturn(headers);
        when(context.getDeployment()).thenReturn(application);

        Buffer requestBody = Buffer.buffer();
        when(request.body()).thenReturn(Future.succeededFuture(requestBody));
        HttpClient httpClient = mock(HttpClient.class);
        when(proxy.getClient()).thenReturn(httpClient);

        HttpClientRequest proxyRequest = mock(HttpClientRequest.class, RETURNS_DEEP_STUBS);
        MultiMap proxyHeaders = new HeadersMultiMap();
        when(proxyRequest.headers()).thenReturn(proxyHeaders);

        when(httpClient.request(any(RequestOptions.class))).thenReturn(Future.succeededFuture(proxyRequest));
        when(context.getRequestBody()).thenReturn(requestBody);
        HttpClientResponse proxyResponse = mock(HttpClientResponse.class, RETURNS_DEEP_STUBS);
        when(proxyRequest.send(eq(requestBody))).thenReturn(Future.succeededFuture(proxyResponse));
        HttpServerResponse response = mock(HttpServerResponse.class);
        when(context.getResponse()).thenReturn(response);

        controller.handle("app1", "chat/completions");

        assertEquals("k2", proxyHeaders.get(HEADER_API_KEY));
    }


}
