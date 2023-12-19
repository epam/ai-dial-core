package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.limiter.RateLimiter;
import com.epam.aidial.core.upstream.UpstreamBalancer;
import com.epam.aidial.core.upstream.UpstreamProvider;
import com.epam.aidial.core.upstream.UpstreamRoute;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;

import static com.epam.aidial.core.Proxy.HEADER_API_KEY;
import static com.epam.aidial.core.Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON;
import static com.epam.aidial.core.util.HttpStatus.BAD_GATEWAY;
import static com.epam.aidial.core.util.HttpStatus.FORBIDDEN;
import static com.epam.aidial.core.util.HttpStatus.UNSUPPORTED_MEDIA_TYPE;
import static io.vertx.core.http.HttpHeaders.AUTHORIZATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Mock
    private Span currentSpan;

    @Mock
    private OpenTelemetry openTelemetry;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Tracer tracer;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ContextPropagators propagators;

    @Mock
    private RateLimiter rateLimiter;

    private DeploymentPostController controller;

    @BeforeEach
    public void beforeEach() {
        when(context.getRequest()).thenReturn(request);
        when(proxy.getOpenTelemetry()).thenReturn(openTelemetry);
        when(openTelemetry.getPropagators()).thenReturn(propagators);
        when(openTelemetry.getTracer(anyString())).thenReturn(tracer);
        controller = new DeploymentPostController(proxy, context);
    }

    @Test
    public void testUnsupportedContentType() {
        when(context.getCurrentSpan()).thenReturn(currentSpan);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn("unsupported");
        when(proxy.getRateLimiter()).thenReturn(rateLimiter);

        controller.handle("app1", "api");

        verify(context).respond(eq(UNSUPPORTED_MEDIA_TYPE), anyString());

    }

    @Test
    public void testForbiddenDeployment() {
        when(context.getCurrentSpan()).thenReturn(currentSpan);
        when(proxy.getRateLimiter()).thenReturn(rateLimiter);
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
        when(context.getCurrentSpan()).thenReturn(currentSpan);
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
        RateLimiter rateLimiter = mock(RateLimiter.class);
        when(rateLimiter.register(any(ProxyContext.class))).thenReturn(true);
        when(proxy.getRateLimiter()).thenReturn(rateLimiter);

        controller.handle("app1", "chat/completions");

        verify(context).respond(eq(BAD_GATEWAY), anyString());
    }

    @Test
    public void testHandleProxyRequest_PropagateDeploymentApiHeader() {

        Config config = new Config();
        config.setApplications(new HashMap<>());
        Application application = new Application();
        application.setName("app1");
        application.setEndpoint("http://app1/chat");
        application.setApiKey("k2");
        config.getApplications().put("app1", application);

        MultiMap headers = new HeadersMultiMap();
        headers.add(HEADER_API_KEY, "k1");
        when(request.headers()).thenReturn(headers);
        when(context.getDeployment()).thenReturn(application);

        HttpClientRequest proxyRequest = mock(HttpClientRequest.class, RETURNS_DEEP_STUBS);
        MultiMap proxyHeaders = new HeadersMultiMap();
        when(proxyRequest.headers()).thenReturn(proxyHeaders);

        Buffer requestBody = Buffer.buffer();
        when(context.getRequestBody()).thenReturn(requestBody);

        controller.handleProxyRequest(proxyRequest);

        assertEquals("k2", proxyHeaders.get(HEADER_API_KEY));
    }

    @Test
    public void testHandleProxyRequest_PropagateAuthHeader() {

        Config config = new Config();
        config.setApplications(new HashMap<>());
        Application application = new Application();
        application.setName("app1");
        application.setForwardAuthToken(false);
        application.setEndpoint("http://app1/chat");
        application.setApiKey("k2");
        config.getApplications().put("app1", application);

        MultiMap headers = new HeadersMultiMap();
        headers.add(AUTHORIZATION, "k1");
        when(request.headers()).thenReturn(headers);
        when(context.getDeployment()).thenReturn(application);

        HttpClientRequest proxyRequest = mock(HttpClientRequest.class, RETURNS_DEEP_STUBS);
        MultiMap proxyHeaders = new HeadersMultiMap();
        when(proxyRequest.headers()).thenReturn(proxyHeaders);

        Buffer requestBody = Buffer.buffer();
        when(context.getRequestBody()).thenReturn(requestBody);

        controller.handleProxyRequest(proxyRequest);

        assertNull(proxyHeaders.get(AUTHORIZATION));
    }

    @Test
    public void testHandleProxyRequest_PropagateApiHeader() {

        Config config = new Config();
        config.setApplications(new HashMap<>());
        Application application = new Application();
        application.setName("app1");
        application.setForwardApiKey(false);
        application.setEndpoint("http://app1/chat");
        application.setApiKey("k2");
        config.getApplications().put("app1", application);

        MultiMap headers = new HeadersMultiMap();
        headers.add(HEADER_API_KEY, "k1");
        when(request.headers()).thenReturn(headers);
        when(context.getDeployment()).thenReturn(application);

        HttpClientRequest proxyRequest = mock(HttpClientRequest.class, RETURNS_DEEP_STUBS);
        MultiMap proxyHeaders = new HeadersMultiMap();
        when(proxyRequest.headers()).thenReturn(proxyHeaders);

        Buffer requestBody = Buffer.buffer();
        when(context.getRequestBody()).thenReturn(requestBody);

        controller.handleProxyRequest(proxyRequest);

        assertNull(proxyHeaders.get(HEADER_API_KEY));
    }


}
