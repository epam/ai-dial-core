package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.ConfigStore;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.limiter.RateLimiter;
import com.epam.aidial.core.upstream.UpstreamBalancer;
import com.epam.aidial.core.upstream.UpstreamProvider;
import com.epam.aidial.core.upstream.UpstreamRoute;
import com.epam.aidial.core.util.ProxyUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.HashMap;
import java.util.Set;

import static com.epam.aidial.core.Proxy.HEADER_API_KEY;
import static com.epam.aidial.core.Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON;
import static com.epam.aidial.core.util.HttpStatus.BAD_GATEWAY;
import static com.epam.aidial.core.util.HttpStatus.BAD_REQUEST;
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
    private RateLimiter rateLimiter;

    @InjectMocks
    private DeploymentPostController controller;

    @BeforeEach
    public void beforeEach() {
        when(context.getRequest()).thenReturn(request);
    }

    @Test
    public void testUnsupportedContentType() {
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn("unsupported");
        when(proxy.getConfigStore()).thenReturn(mock(ConfigStore.class));

        controller.handle("app1", "api");

        verify(context).respond(eq(UNSUPPORTED_MEDIA_TYPE), anyString());

    }

    @Test
    public void testForbiddenDeployment() {
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(HEADER_CONTENT_TYPE_APPLICATION_JSON);
        Config config = new Config();
        config.setApplications(new HashMap<>());
        Application app = new Application();
        app.setName("app1");
        app.setUserRoles(Set.of("role1"));
        config.getApplications().put("app1", app);
        when(context.getConfig()).thenReturn(config);

        controller.handle("app1", "chat/completions");

        verify(context).respond(eq(FORBIDDEN), anyString());
    }

    @Test
    public void testDeploymentNotFound() {
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(HEADER_CONTENT_TYPE_APPLICATION_JSON);
        Config config = new Config();
        config.setApplications(new HashMap<>());
        Application app = new Application();
        config.getApplications().put("app1", app);
        when(context.getConfig()).thenReturn(config);

        controller.handle("unknown-app", "chat/completions");

        verify(context).respond(eq(NOT_FOUND), anyString());
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
        when(proxy.getConfigStore()).thenReturn(mock(ConfigStore.class));

        controller.handle("app1", "chat/completions");

        verify(context).respond(eq(BAD_GATEWAY), anyString());
    }

    @Test
    public void testHandleRequestBody_OverrideModelName() throws IOException {
        UpstreamRoute upstreamRoute = mock(UpstreamRoute.class, RETURNS_DEEP_STUBS);
        when(upstreamRoute.hasNext()).thenReturn(true);
        when(context.getUpstreamRoute()).thenReturn(upstreamRoute);
        HttpServerRequest request = mock(HttpServerRequest.class, RETURNS_DEEP_STUBS);
        when(context.getRequest()).thenReturn(request);
        when(proxy.getClient()).thenReturn(mock(HttpClient.class, RETURNS_DEEP_STUBS));

        Model model = new Model();
        model.setName("name");
        model.setEndpoint("http://host/model");
        model.setOverrideName("overrideName");
        when(context.getDeployment()).thenReturn(model);
        String body = """
                {
                    "model": "name"
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
        UpstreamRoute upstreamRoute = mock(UpstreamRoute.class, RETURNS_DEEP_STUBS);
        when(upstreamRoute.hasNext()).thenReturn(true);
        when(context.getUpstreamRoute()).thenReturn(upstreamRoute);
        HttpServerRequest request = mock(HttpServerRequest.class, RETURNS_DEEP_STUBS);
        when(context.getRequest()).thenReturn(request);
        when(proxy.getClient()).thenReturn(mock(HttpClient.class, RETURNS_DEEP_STUBS));

        Model model = new Model();
        model.setName("name");
        model.setEndpoint("http://host/model");
        when(context.getDeployment()).thenReturn(model);
        String body = """
                {
                    "model": "name"
                }
                """;
        Buffer requestBody = Buffer.buffer(body);
        when(context.getRequestBody()).thenCallRealMethod();
        doCallRealMethod().when(context).setRequestBody(any());

        controller.handleRequestBody(requestBody);

        assertEquals(requestBody, context.getRequestBody());

    }


}
