package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.Serializable;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import javax.annotation.Nullable;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ControllerSelectorTest {

    @Mock
    private ProxyContext context;
    @Mock
    private Proxy proxy;

    @Mock
    private HttpServerRequest request;

    @BeforeEach
    public void beforeEach() {
        when(context.getRequest()).thenReturn(request);
    }

    @Test
    public void testSelectGetDeploymentController() {
        when(request.path()).thenReturn("/openai/deployments/deployment1");
        when(request.method()).thenReturn(HttpMethod.GET);
        Controller controller = ControllerSelector.select(proxy, context);
        Assertions.assertNotNull(controller);
        SerializedLambda lambda = getSerializedLambda(controller);
        Assertions.assertNotNull(lambda);
        Object arg1 = lambda.getCapturedArg(0);
        Object arg2 = lambda.getCapturedArg(1);
        Assertions.assertTrue(arg1 instanceof DeploymentController);
        Assertions.assertTrue(arg2 instanceof String);
        Assertions.assertEquals("deployment1", arg2);
    }

    @Test
    public void testSelectGetDeploymentsController() {
        when(request.path()).thenReturn("/openai/deployments");
        when(request.method()).thenReturn(HttpMethod.GET);
        Controller controller = ControllerSelector.select(proxy, context);
        Assertions.assertNotNull(controller);
        SerializedLambda lambda = getSerializedLambda(controller);
        Assertions.assertNotNull(lambda);
        Assertions.assertEquals(1, lambda.getCapturedArgCount());
        Object arg1 = lambda.getCapturedArg(0);
        Assertions.assertTrue(arg1 instanceof DeploymentController);
        Assertions.assertEquals("getDeployments", lambda.getImplMethodName());
    }

    @Test
    public void testSelectGetModelController() {
        when(request.path()).thenReturn("/openai/models/model1");
        when(request.method()).thenReturn(HttpMethod.GET);
        Controller controller = ControllerSelector.select(proxy, context);
        Assertions.assertNotNull(controller);
        SerializedLambda lambda = getSerializedLambda(controller);
        Assertions.assertNotNull(lambda);
        Object arg1 = lambda.getCapturedArg(0);
        Object arg2 = lambda.getCapturedArg(1);
        Assertions.assertTrue(arg1 instanceof ModelController);
        Assertions.assertTrue(arg2 instanceof String);
        Assertions.assertEquals("model1", arg2);
    }

    @Test
    public void testSelectGetModelsController() {
        when(request.path()).thenReturn("/openai/models");
        when(request.method()).thenReturn(HttpMethod.GET);
        Controller controller = ControllerSelector.select(proxy, context);
        Assertions.assertNotNull(controller);
        SerializedLambda lambda = getSerializedLambda(controller);
        Assertions.assertNotNull(lambda);
        Assertions.assertEquals(1, lambda.getCapturedArgCount());
        Object arg1 = lambda.getCapturedArg(0);
        Assertions.assertTrue(arg1 instanceof ModelController);
        Assertions.assertEquals("getModels", lambda.getImplMethodName());
    }

    @Test
    public void testSelectGetAddonController() {
        when(request.path()).thenReturn("/openai/addons/addon1");
        when(request.method()).thenReturn(HttpMethod.GET);
        Controller controller = ControllerSelector.select(proxy, context);
        Assertions.assertNotNull(controller);
        SerializedLambda lambda = getSerializedLambda(controller);
        Assertions.assertNotNull(lambda);
        Object arg1 = lambda.getCapturedArg(0);
        Object arg2 = lambda.getCapturedArg(1);
        Assertions.assertTrue(arg1 instanceof AddonController);
        Assertions.assertTrue(arg2 instanceof String);
        Assertions.assertEquals("addon1", arg2);
    }

    @Test
    public void testSelectGetAddonsController() {
        when(request.path()).thenReturn("/openai/addons");
        when(request.method()).thenReturn(HttpMethod.GET);
        Controller controller = ControllerSelector.select(proxy, context);
        Assertions.assertNotNull(controller);
        SerializedLambda lambda = getSerializedLambda(controller);
        Assertions.assertNotNull(lambda);
        Assertions.assertEquals(1, lambda.getCapturedArgCount());
        Object arg1 = lambda.getCapturedArg(0);
        Assertions.assertTrue(arg1 instanceof AddonController);
        Assertions.assertEquals("getAddons", lambda.getImplMethodName());
    }

    @Test
    public void testSelectGetAssistantController() {
        when(request.path()).thenReturn("/openai/assistants/as1");
        when(request.method()).thenReturn(HttpMethod.GET);
        Controller controller = ControllerSelector.select(proxy, context);
        Assertions.assertNotNull(controller);
        SerializedLambda lambda = getSerializedLambda(controller);
        Assertions.assertNotNull(lambda);
        Object arg1 = lambda.getCapturedArg(0);
        Object arg2 = lambda.getCapturedArg(1);
        Assertions.assertTrue(arg1 instanceof AssistantController);
        Assertions.assertTrue(arg2 instanceof String);
        Assertions.assertEquals("as1", arg2);
    }

    @Test
    public void testSelectGetAssistantsController() {
        when(request.path()).thenReturn("/openai/assistants");
        when(request.method()).thenReturn(HttpMethod.GET);
        Controller controller = ControllerSelector.select(proxy, context);
        Assertions.assertNotNull(controller);
        SerializedLambda lambda = getSerializedLambda(controller);
        Assertions.assertNotNull(lambda);
        Assertions.assertEquals(1, lambda.getCapturedArgCount());
        Object arg1 = lambda.getCapturedArg(0);
        Assertions.assertTrue(arg1 instanceof AssistantController);
        Assertions.assertEquals("getAssistants", lambda.getImplMethodName());
    }

    @Test
    public void testSelectGetApplicationController() {
        when(request.path()).thenReturn("/openai/applications/app1");
        when(request.method()).thenReturn(HttpMethod.GET);
        Controller controller = ControllerSelector.select(proxy, context);
        Assertions.assertNotNull(controller);
        SerializedLambda lambda = getSerializedLambda(controller);
        Assertions.assertNotNull(lambda);
        Object arg1 = lambda.getCapturedArg(0);
        Object arg2 = lambda.getCapturedArg(1);
        Assertions.assertTrue(arg1 instanceof ApplicationController);
        Assertions.assertTrue(arg2 instanceof String);
        Assertions.assertEquals("app1", arg2);
    }

    @Test
    public void testSelectGetApplicationsController() {
        when(request.path()).thenReturn("/openai/applications");
        when(request.method()).thenReturn(HttpMethod.GET);
        Controller controller = ControllerSelector.select(proxy, context);
        Assertions.assertNotNull(controller);
        SerializedLambda lambda = getSerializedLambda(controller);
        Assertions.assertNotNull(lambda);
        Assertions.assertEquals(1, lambda.getCapturedArgCount());
        Object arg1 = lambda.getCapturedArg(0);
        Assertions.assertTrue(arg1 instanceof ApplicationController);
        Assertions.assertEquals("getApplications", lambda.getImplMethodName());
    }

    @Test
    public void testSelectListMetadataFileController() {
        when(request.path()).thenReturn("/v1/files/folder1/file1?purpose=metadata");
        when(request.method()).thenReturn(HttpMethod.GET);
        MultiMap params = mock(MultiMap.class);
        when(request.params()).thenReturn(params);
        when(params.get("purpose")).thenReturn("metadata");
        Controller controller = ControllerSelector.select(proxy, context);
        Assertions.assertNotNull(controller);
        SerializedLambda lambda = getSerializedLambda(controller);
        Assertions.assertNotNull(lambda);
        Assertions.assertEquals(2, lambda.getCapturedArgCount());
        Object arg1 = lambda.getCapturedArg(0);
        Object arg2 = lambda.getCapturedArg(1);
        Assertions.assertTrue(arg1 instanceof FileMetadataController);
        Assertions.assertTrue(arg2 instanceof String);
        Assertions.assertEquals("/folder1/file1?purpose=metadata", arg2);
    }

    @Test
    public void testSelectDownloadFileController() {
        when(request.path()).thenReturn("/v1/files/folder1/file1");
        when(request.method()).thenReturn(HttpMethod.GET);
        MultiMap params = mock(MultiMap.class);
        when(request.params()).thenReturn(params);
        Controller controller = ControllerSelector.select(proxy, context);
        Assertions.assertNotNull(controller);
        SerializedLambda lambda = getSerializedLambda(controller);
        Assertions.assertNotNull(lambda);
        Assertions.assertEquals(2, lambda.getCapturedArgCount());
        Object arg1 = lambda.getCapturedArg(0);
        Object arg2 = lambda.getCapturedArg(1);
        Assertions.assertTrue(arg1 instanceof DownloadFileController);
        Assertions.assertTrue(arg2 instanceof String);
        Assertions.assertEquals("/folder1/file1", arg2);
    }

    @Test
    public void testSelectPostDeploymentController() {
        when(request.path()).thenReturn("/openai/deployments/app1/completions");
        when(request.method()).thenReturn(HttpMethod.POST);
        Controller controller = ControllerSelector.select(proxy, context);
        Assertions.assertNotNull(controller);
        SerializedLambda lambda = getSerializedLambda(controller);
        Assertions.assertNotNull(lambda);
        Assertions.assertEquals(3, lambda.getCapturedArgCount());
        Object arg1 = lambda.getCapturedArg(0);
        Object arg2 = lambda.getCapturedArg(1);
        Object arg3 = lambda.getCapturedArg(2);
        Assertions.assertTrue(arg1 instanceof DeploymentPostController);
        Assertions.assertTrue(arg2 instanceof String);
        Assertions.assertTrue(arg3 instanceof String);
        Assertions.assertEquals("app1", arg2);
        Assertions.assertEquals("completions", arg3);
    }

    @Test
    public void testSelectUploadFileController() {
        when(request.path()).thenReturn("/v1/files/folder1/file1");
        when(request.method()).thenReturn(HttpMethod.POST);
        Controller controller = ControllerSelector.select(proxy, context);
        Assertions.assertNotNull(controller);
        SerializedLambda lambda = getSerializedLambda(controller);
        Assertions.assertNotNull(lambda);
        Assertions.assertEquals(2, lambda.getCapturedArgCount());
        Object arg1 = lambda.getCapturedArg(0);
        Object arg2 = lambda.getCapturedArg(1);
        Assertions.assertTrue(arg1 instanceof UploadFileController);
        Assertions.assertTrue(arg2 instanceof String);
        Assertions.assertEquals("/folder1/file1", arg2);
    }

    @Test
    public void testSelectDeleteFileController() {
        when(request.path()).thenReturn("/v1/files/folder1/file1");
        when(request.method()).thenReturn(HttpMethod.DELETE);
        Controller controller = ControllerSelector.select(proxy, context);
        Assertions.assertNotNull(controller);
        SerializedLambda lambda = getSerializedLambda(controller);
        Assertions.assertNotNull(lambda);
        Assertions.assertEquals(2, lambda.getCapturedArgCount());
        Object arg1 = lambda.getCapturedArg(0);
        Object arg2 = lambda.getCapturedArg(1);
        Assertions.assertTrue(arg1 instanceof DeleteFileController);
        Assertions.assertTrue(arg2 instanceof String);
        Assertions.assertEquals("/folder1/file1", arg2);
    }

    @Test
    public void testSelectRateResponseController() {
        when(request.path()).thenReturn("/v1/app/rate");
        when(request.method()).thenReturn(HttpMethod.POST);
        Controller controller = ControllerSelector.select(proxy, context);
        Assertions.assertNotNull(controller);
        SerializedLambda lambda = getSerializedLambda(controller);
        Assertions.assertNotNull(lambda);
        Object arg1 = lambda.getCapturedArg(0);
        Object arg2 = lambda.getCapturedArg(1);
        Assertions.assertTrue(arg1 instanceof RateResponseController);
        Assertions.assertTrue(arg2 instanceof String);
        Assertions.assertEquals("app", arg2);
    }

    @Nullable
    private static SerializedLambda getSerializedLambda(Serializable lambda) {
        for (Class<?> cl = lambda.getClass(); cl != null; cl = cl.getSuperclass()) {
            try {
                Method m = cl.getDeclaredMethod("writeReplace");
                m.setAccessible(true);
                Object replacement = m.invoke(lambda);
                if (!(replacement instanceof SerializedLambda)) {
                    break;
                }
                return (SerializedLambda) replacement;
            } catch (NoSuchMethodException e) {
                // skip, continue
            } catch (IllegalAccessException | InvocationTargetException | SecurityException e) {
                throw new IllegalStateException("Failed to call writeReplace", e);
            }
        }
        return null;
    }
}
