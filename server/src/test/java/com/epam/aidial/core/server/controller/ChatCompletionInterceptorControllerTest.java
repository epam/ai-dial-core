package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.DeploymentInterface;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ChatCompletionInterceptorControllerTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Proxy proxy;

    @Mock
    private ProxyContext context;

    @Mock
    private HttpServerRequest request;

    @Test
    void rewritesDeploymentSegmentToInterceptorName() {
        assertEquals("/openai/deployments/my-interceptor/chat/completions",
                ChatCompletionInterceptorController.rewriteDeploymentSegment(
                        "/openai/deployments/initial-model/chat/completions", "my-interceptor"));
    }

    @Test
    void leavesNonMatchingPathUnchanged() {
        assertEquals("/some/other/path",
                ChatCompletionInterceptorController.rewriteDeploymentSegment("/some/other/path", "my-interceptor"));
    }

    @Test
    void rewritesMultiSegmentDeploymentIdToInterceptorName() {
        // Platform-bucket entities are addressed by a multi-segment canonical id
        // (e.g. models/platform/{name}); the whole id must collapse to the target name.
        assertEquals("/openai/deployments/my-interceptor/chat/completions",
                ChatCompletionInterceptorController.rewriteDeploymentSegment(
                        "/openai/deployments/models/platform/initial-model/chat/completions", "my-interceptor"));
    }

    @Test
    void buildUri_legacyFlow_noQuery() {
        Model deployment = new Model();
        deployment.setName("my-interceptor");
        deployment.setEndpoint("http://interceptor/openai/deployments/my-interceptor/chat/completions");

        when(context.getDeployment()).thenReturn(deployment);
        when(context.getRequest()).thenReturn(request);
        when(request.query()).thenReturn(null);

        ChatCompletionInterceptorController controller = new ChatCompletionInterceptorController(proxy, context, 0);

        assertEquals(
                "http://interceptor/openai/deployments/my-interceptor/chat/completions",
                controller.buildUri(context));
    }

    @Test
    void buildUri_legacyFlow_withQuery() {
        Model deployment = new Model();
        deployment.setName("my-interceptor");
        deployment.setEndpoint("http://interceptor/openai/deployments/my-interceptor/chat/completions");

        when(context.getDeployment()).thenReturn(deployment);
        when(context.getRequest()).thenReturn(request);
        when(request.query()).thenReturn("api-version=2024-05");

        ChatCompletionInterceptorController controller = new ChatCompletionInterceptorController(proxy, context, 0);

        assertEquals(
                "http://interceptor/openai/deployments/my-interceptor/chat/completions?api-version=2024-05",
                controller.buildUri(context));
    }

    @Test
    void buildUri_newFlow_rewritesDeploymentSegment_noQuery() {
        Model deployment = new Model();
        deployment.setName("my-interceptor");
        deployment.setInterfaces(Map.of(
                InterfaceType.OPENAI_CHAT_COMPLETIONS.getValue(), new DeploymentInterface("http://adapter")));

        when(context.getDeployment()).thenReturn(deployment);
        when(context.getRequest()).thenReturn(request);
        when(request.query()).thenReturn(null);
        when(request.path()).thenReturn("/openai/deployments/original-model/chat/completions");

        ChatCompletionInterceptorController controller = new ChatCompletionInterceptorController(proxy, context, 0);

        assertEquals(
                "http://adapter/openai/deployments/my-interceptor/chat/completions",
                controller.buildUri(context));
    }

    @Test
    void buildUri_newFlow_rewritesDeploymentSegment_withQuery() {
        Model deployment = new Model();
        deployment.setName("my-interceptor");
        deployment.setInterfaces(Map.of(
                InterfaceType.OPENAI_CHAT_COMPLETIONS.getValue(), new DeploymentInterface("http://adapter")));

        when(context.getDeployment()).thenReturn(deployment);
        when(context.getRequest()).thenReturn(request);
        when(request.query()).thenReturn("api-version=2024-05");
        when(request.path()).thenReturn("/openai/deployments/original-model/chat/completions");

        ChatCompletionInterceptorController controller = new ChatCompletionInterceptorController(proxy, context, 0);

        assertEquals(
                "http://adapter/openai/deployments/my-interceptor/chat/completions?api-version=2024-05",
                controller.buildUri(context));
    }

    @Test
    void buildUri_newFlow_trailingSlashStripped() {
        Model deployment = new Model();
        deployment.setName("my-interceptor");
        deployment.setInterfaces(Map.of(
                InterfaceType.OPENAI_CHAT_COMPLETIONS.getValue(), new DeploymentInterface("http://adapter/")));

        when(context.getDeployment()).thenReturn(deployment);
        when(context.getRequest()).thenReturn(request);
        when(request.query()).thenReturn(null);
        when(request.path()).thenReturn("/openai/deployments/model/chat/completions");

        ChatCompletionInterceptorController controller = new ChatCompletionInterceptorController(proxy, context, 0);

        assertEquals(
                "http://adapter/openai/deployments/my-interceptor/chat/completions",
                controller.buildUri(context));
    }

    @Test
    void buildUri_newFlow_rewritesMultiSegmentDeploymentId() {
        Model deployment = new Model();
        deployment.setName("my-interceptor");
        deployment.setInterfaces(Map.of(
                InterfaceType.OPENAI_CHAT_COMPLETIONS.getValue(), new DeploymentInterface("http://adapter")));

        when(context.getDeployment()).thenReturn(deployment);
        when(context.getRequest()).thenReturn(request);
        when(request.query()).thenReturn("api-version=2025-01-01-preview");
        when(request.path()).thenReturn("/openai/deployments/models/platform/original-model/chat/completions");

        ChatCompletionInterceptorController controller = new ChatCompletionInterceptorController(proxy, context, 0);

        assertEquals(
                "http://adapter/openai/deployments/my-interceptor/chat/completions?api-version=2025-01-01-preview",
                controller.buildUri(context));
    }

    @Test
    void buildUri_newFlow_usesOverrideNameForPathSegmentWhenSet() {
        Interceptor deployment = new Interceptor();
        deployment.setName("my-interceptor");
        deployment.setOverrideName("interceptor-override");
        deployment.setInterfaces(Map.of(
                InterfaceType.OPENAI_CHAT_COMPLETIONS.getValue(), new DeploymentInterface("http://adapter")));

        when(context.getDeployment()).thenReturn(deployment);
        when(context.getRequest()).thenReturn(request);
        when(request.query()).thenReturn(null);
        when(request.path()).thenReturn("/openai/deployments/original-model/chat/completions");

        ChatCompletionInterceptorController controller = new ChatCompletionInterceptorController(proxy, context, 0);

        assertEquals(
                "http://adapter/openai/deployments/interceptor-override/chat/completions",
                controller.buildUri(context));
    }

    @Test
    void buildUri_newFlow_nonMatchingPath_leftUnchanged() {
        Model deployment = new Model();
        deployment.setName("my-interceptor");
        deployment.setInterfaces(Map.of(
                InterfaceType.OPENAI_CHAT_COMPLETIONS.getValue(), new DeploymentInterface("http://adapter")));

        when(context.getDeployment()).thenReturn(deployment);
        when(context.getRequest()).thenReturn(request);
        when(request.query()).thenReturn(null);
        when(request.path()).thenReturn("/some/other/path");

        ChatCompletionInterceptorController controller = new ChatCompletionInterceptorController(proxy, context, 0);

        assertEquals("http://adapter/some/other/path", controller.buildUri(context));
    }

    @Test
    void handleRequestBody_overridesModelName() throws IOException {
        Interceptor interceptor = new Interceptor();
        interceptor.setName("interceptor1");
        interceptor.setEndpoint("http://localhost:4088/api/v1/interceptor/handle");
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

        ChatCompletionInterceptorController controller = new ChatCompletionInterceptorController(proxy, context, 0);

        String body = """
                {
                    "model": "name",
                    "messages": [],
                    "stream": false
                }
                """;
        controller.handleRequestBody(Buffer.buffer(body));

        Buffer updatedBody = context.getRequestBody();
        assertNotNull(updatedBody);
        ObjectNode tree = (ObjectNode) ProxyUtil.MAPPER.readTree(updatedBody.getBytes());
        assertEquals("overrideName", tree.get("model").asText());
    }
}
