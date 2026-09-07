package com.epam.aidial.core.server.function.enhancement;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.function.request.ChatCompletionRequest;
import com.epam.aidial.core.server.function.request.MessagesApiRequest;
import com.epam.aidial.core.server.function.request.RequestObject;
import com.epam.aidial.core.server.function.request.ResponsesApiRequest;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.buffer.Buffer;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class EnhanceDeploymentRequestFnTest {

    private static final String CLIENT_CHOSEN_MODEL = """
            {"model": "foo-bar"}
            """;

    @Mock
    private Proxy proxy;

    @Mock
    private ProxyContext context;

    private EnhanceDeploymentRequestFn fn;

    @BeforeEach
    public void setUp() {
        fn = new EnhanceDeploymentRequestFn(proxy, context);
    }

    @Test
    public void testModelIgnoresClientChosenName() {
        Model model = new Model();
        model.setName("dial-model-id");

        forEveryApi(model, CLIENT_CHOSEN_MODEL, request -> {
            assertTrue(fn.apply(request));
            assertEquals("dial-model-id", request.getModel());
        });
    }

    @Test
    public void testModelAppliesOverrideName() {
        Model model = new Model();
        model.setName("dial-model-id");
        model.setOverrideName("upstream-model-id");

        forEveryApi(model, CLIENT_CHOSEN_MODEL, request -> {
            assertTrue(fn.apply(request));
            assertEquals("upstream-model-id", request.getModel());
        });
    }

    @Test
    public void testModelNameIsSetWhenBodyCarriesNone() {
        Model model = new Model();
        model.setName("dial-model-id");

        forEveryApi(model, "{}", request -> {
            assertTrue(fn.apply(request));
            assertEquals("dial-model-id", request.getModel());
        });
    }

    @Test
    public void testApplicationKeepsClientChosenName() {
        Application application = new Application();
        application.setName("dial-app-id");

        forEveryApi(application, CLIENT_CHOSEN_MODEL, request -> {
            assertFalse(fn.apply(request));
            assertEquals("foo-bar", request.getModel());
        });
    }

    @Test
    public void testApplicationAppliesOverrideName() {
        Application application = new Application();
        application.setName("dial-app-id");
        application.setOverrideName("overrideName");

        forEveryApi(application, CLIENT_CHOSEN_MODEL, request -> {
            assertTrue(fn.apply(request));
            assertEquals("overrideName", request.getModel());
        });
    }

    @Test
    public void testInterceptorKeepsClientChosenName() {
        Interceptor interceptor = new Interceptor();
        interceptor.setName("dial-interceptor-id");

        forEveryApi(interceptor, CLIENT_CHOSEN_MODEL, request -> {
            assertFalse(fn.apply(request));
            assertEquals("foo-bar", request.getModel());
        });
    }

    /**
     * The same function serves the Chat Completions, Responses and Anthropic Messages APIs, so every case
     * runs against all three request shapes.
     */
    @SneakyThrows
    private void forEveryApi(Deployment deployment, String body, Consumer<RequestObject> assertion) {
        when(context.getDeployment()).thenReturn(deployment);
        List<Function<ObjectNode, RequestObject>> factories = List.of(
                ChatCompletionRequest::new, ResponsesApiRequest::new, MessagesApiRequest::new);
        for (Function<ObjectNode, RequestObject> factory : factories) {
            assertion.accept(factory.apply(ProxyUtil.parseObject(Buffer.buffer(body))));
        }
    }
}
