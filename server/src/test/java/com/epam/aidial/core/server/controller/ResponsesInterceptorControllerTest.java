package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.DeploymentInterface;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.function.request.RequestObject;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServerRequest;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ResponsesInterceptorControllerTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Proxy proxy;

    @Mock
    private ProxyContext context;

    @BeforeEach
    void stubConfig() {
        // the controller resolves translator references against the request's config on every routing step
        lenient().when(context.getConfig()).thenReturn(new Config());
    }

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

    @Test
    void interfaceType_reportsOpenAiResponses() {
        ResponsesInterceptorController controller = new ResponsesInterceptorController(proxy, context, 0);

        assertEquals(InterfaceType.OPENAI_RESPONSES, controller.interfaceType());
    }

    @Test
    void handleRequestBody_doesNotOverrideModelName() throws IOException {
        Interceptor interceptor = new Interceptor();
        interceptor.setName("interceptor1");
        interceptor.setResponsesEndpoint("http://localhost:4088/openai/v1/responses");
        interceptor.setOverrideName("overrideName");

        when(context.getDeployment()).thenReturn(interceptor);
        when(context.getRequest()).thenReturn(request);
        when(request.query()).thenReturn(null);

        when(proxy.getClient()).thenReturn(mock(HttpClient.class, Answers.RETURNS_DEEP_STUBS));
        when(proxy.getClientOptions()).thenReturn(new HttpClientOptions());
        when(proxy.getApiKeyStore()).thenReturn(mock(ApiKeyStore.class));

        ApiKeyData proxyApiKeyData = new ApiKeyData();
        proxyApiKeyData.setInterceptorIndex(0);
        when(context.getProxyApiKeyData()).thenReturn(proxyApiKeyData);

        when(context.getRequestBody()).thenCallRealMethod();
        doCallRealMethod().when(context).setRequestBody(any());

        ResponsesInterceptorController controller = new ResponsesInterceptorController(proxy, context, 0);

        String body = """
                {
                    "model": "name",
                    "input": []
                }
                """;
        controller.handleRequestBody(Buffer.buffer(body));

        Buffer updatedBody = context.getRequestBody();
        assertNotNull(updatedBody);
        ObjectNode tree = (ObjectNode) ProxyUtil.MAPPER.readTree(updatedBody.getBytes());
        assertEquals("name", tree.get("model").asText());
    }
}
