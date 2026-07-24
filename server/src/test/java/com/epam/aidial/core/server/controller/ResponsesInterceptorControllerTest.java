package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.DeploymentInterface;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.function.request.RequestObject;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ResponsesInterceptorControllerTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Proxy proxy;

    @Mock
    private ProxyContext context;

    @Mock
    private HttpServerRequest request;

    @Test
    void parseRequest_emptyUriSuffix_returnsRequest() throws IOException {
        ResponsesInterceptorController controller = new ResponsesInterceptorController(proxy, context, 0);

        RequestObject result = controller.parseRequest(Buffer.buffer("{\"model\":\"test\"}"));

        assertNotNull(result);
    }

    @Test
    void parseRequest_nonEmptyUriSuffix_returnsNull() throws IOException {
        ResponsesInterceptorController controller =
                new ResponsesInterceptorController(proxy, context, "dial_dep_abc", "/cancel", 0);

        RequestObject result = controller.parseRequest(Buffer.buffer("{\"model\":\"test\"}"));

        assertNull(result);
    }

    @Test
    void buildUri_legacyFlow_noSuffix_noQuery() {
        Model deployment = new Model();
        deployment.setResponsesEndpoint("http://interceptor/responses");

        when(context.getDeployment()).thenReturn(deployment);
        when(context.getRequest()).thenReturn(request);
        when(request.query()).thenReturn(null);

        ResponsesInterceptorController controller = new ResponsesInterceptorController(proxy, context, 0);

        assertEquals("http://interceptor/responses", controller.buildUri(context));
    }

    @Test
    void buildUri_legacyFlow_noSuffix_withQuery() {
        Model deployment = new Model();
        deployment.setResponsesEndpoint("http://interceptor/responses");

        when(context.getDeployment()).thenReturn(deployment);
        when(context.getRequest()).thenReturn(request);
        when(request.query()).thenReturn("stream=true");

        ResponsesInterceptorController controller = new ResponsesInterceptorController(proxy, context, 0);

        assertEquals("http://interceptor/responses?stream=true", controller.buildUri(context));
    }

    @Test
    void buildUri_legacyFlow_withSuffix_noQuery() {
        Model deployment = new Model();
        deployment.setResponsesEndpoint("http://interceptor/responses");

        when(context.getDeployment()).thenReturn(deployment);
        when(context.getRequest()).thenReturn(request);
        when(request.query()).thenReturn(null);

        ResponsesInterceptorController controller =
                new ResponsesInterceptorController(proxy, context, "dial_dep_abc", "/cancel", 0);

        assertEquals("http://interceptor/responses/dial_dep_abc/cancel", controller.buildUri(context));
    }

    @Test
    void buildUri_legacyFlow_withSuffix_withQuery() {
        Model deployment = new Model();
        deployment.setResponsesEndpoint("http://interceptor/responses");

        when(context.getDeployment()).thenReturn(deployment);
        when(context.getRequest()).thenReturn(request);
        when(request.query()).thenReturn("stream=true");

        ResponsesInterceptorController controller =
                new ResponsesInterceptorController(proxy, context, "dial_dep_abc", "", 0);

        assertEquals("http://interceptor/responses/dial_dep_abc?stream=true", controller.buildUri(context));
    }

    @Test
    void buildUri_newFlow_noSuffix_noQuery() {
        Model deployment = new Model();
        deployment.setInterfaces(Map.of(
                InterfaceType.OPENAI_RESPONSES.getValue(), new DeploymentInterface("http://adapter")));

        when(context.getDeployment()).thenReturn(deployment);
        when(context.getRequest()).thenReturn(request);
        when(request.query()).thenReturn(null);

        ResponsesInterceptorController controller = new ResponsesInterceptorController(proxy, context, 0);

        assertEquals("http://adapter/openai/v1/responses", controller.buildUri(context));
    }

    @Test
    void buildUri_newFlow_noSuffix_withQuery() {
        Model deployment = new Model();
        deployment.setInterfaces(Map.of(
                InterfaceType.OPENAI_RESPONSES.getValue(), new DeploymentInterface("http://adapter")));

        when(context.getDeployment()).thenReturn(deployment);
        when(context.getRequest()).thenReturn(request);
        when(request.query()).thenReturn("stream=true");

        ResponsesInterceptorController controller = new ResponsesInterceptorController(proxy, context, 0);

        assertEquals("http://adapter/openai/v1/responses?stream=true", controller.buildUri(context));
    }

    @Test
    void buildUri_newFlow_withSuffix_noQuery() {
        Model deployment = new Model();
        deployment.setInterfaces(Map.of(
                InterfaceType.OPENAI_RESPONSES.getValue(), new DeploymentInterface("http://adapter")));

        when(context.getDeployment()).thenReturn(deployment);
        when(context.getRequest()).thenReturn(request);
        when(request.query()).thenReturn(null);

        ResponsesInterceptorController controller =
                new ResponsesInterceptorController(proxy, context, "dial_dep_abc", "/cancel", 0);

        assertEquals("http://adapter/openai/v1/responses/dial_dep_abc/cancel", controller.buildUri(context));
    }

    @Test
    void buildUri_newFlow_withSuffix_withQuery() {
        Model deployment = new Model();
        deployment.setInterfaces(Map.of(
                InterfaceType.OPENAI_RESPONSES.getValue(), new DeploymentInterface("http://adapter")));

        when(context.getDeployment()).thenReturn(deployment);
        when(context.getRequest()).thenReturn(request);
        when(request.query()).thenReturn("stream=true");

        ResponsesInterceptorController controller =
                new ResponsesInterceptorController(proxy, context, "dial_dep_abc", "", 0);

        assertEquals("http://adapter/openai/v1/responses/dial_dep_abc?stream=true", controller.buildUri(context));
    }

    @Test
    void buildUri_newFlow_trailingSlashStripped() {
        Model deployment = new Model();
        deployment.setInterfaces(Map.of(
                InterfaceType.OPENAI_RESPONSES.getValue(), new DeploymentInterface("http://adapter/")));

        when(context.getDeployment()).thenReturn(deployment);
        when(context.getRequest()).thenReturn(request);
        when(request.query()).thenReturn(null);

        ResponsesInterceptorController controller = new ResponsesInterceptorController(proxy, context, 0);

        assertEquals("http://adapter/openai/v1/responses", controller.buildUri(context));
    }
}
