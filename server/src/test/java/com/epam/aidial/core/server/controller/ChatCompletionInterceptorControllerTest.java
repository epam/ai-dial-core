package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.DeploymentInterface;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import io.vertx.core.http.HttpServerRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
