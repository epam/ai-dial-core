package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.service.CustomApplicationService;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        assertNotNull(controller);
        SerializedLambda lambda = getSerializedLambda(controller);
        assertNotNull(lambda);
        Object arg1 = lambda.getCapturedArg(0);
        Object arg2 = lambda.getCapturedArg(1);
        assertInstanceOf(DeploymentController.class, arg1);
        assertEquals("deployment1", arg2);
    }

    @Test
    public void testSelectGetDeploymentsController() {
        when(request.path()).thenReturn("/openai/deployments");
        when(request.method()).thenReturn(HttpMethod.GET);
        Controller controller = ControllerSelector.select(proxy, context);
        assertNotNull(controller);
        SerializedLambda lambda = getSerializedLambda(controller);
        assertNotNull(lambda);
        assertEquals(1, lambda.getCapturedArgCount());
        Object arg1 = lambda.getCapturedArg(0);
        assertInstanceOf(DeploymentController.class, arg1);
        assertEquals("getDeployments", lambda.getImplMethodName());
    }

    @Test
    public void testSelectGetModelController() {
        when(request.path()).thenReturn("/openai/models/model1");
        when(request.method()).thenReturn(HttpMethod.GET);
        Controller controller = ControllerSelector.select(proxy, context);
        assertNotNull(controller);
        SerializedLambda lambda = getSerializedLambda(controller);
        assertNotNull(lambda);
        Object arg1 = lambda.getCapturedArg(0);
        Object arg2 = lambda.getCapturedArg(1);
        assertInstanceOf(ModelController.class, arg1);
        assertEquals("model1", arg2);
    }

    @Test
    public void testSelectGetModelsController() {
        when(request.path()).thenReturn("/openai/models");
        when(request.method()).thenReturn(HttpMethod.GET);
        Controller controller = ControllerSelector.select(proxy, context);
        assertNotNull(controller);
        SerializedLambda lambda = getSerializedLambda(controller);
        assertNotNull(lambda);
        assertEquals(1, lambda.getCapturedArgCount());
        Object arg1 = lambda.getCapturedArg(0);
        assertInstanceOf(ModelController.class, arg1);
        assertEquals("getModels", lambda.getImplMethodName());
    }

    @Test
    public void testSelectGetAddonController() {
        when(request.path()).thenReturn("/openai/addons/addon1");
        when(request.method()).thenReturn(HttpMethod.GET);
        Controller controller = ControllerSelector.select(proxy, context);
        assertNotNull(controller);
        SerializedLambda lambda = getSerializedLambda(controller);
        assertNotNull(lambda);
        Object arg1 = lambda.getCapturedArg(0);
        Object arg2 = lambda.getCapturedArg(1);
        assertInstanceOf(AddonController.class, arg1);
        assertEquals("addon1", arg2);
    }

    @Test
    public void testSelectGetAddonsController() {
        when(request.path()).thenReturn("/openai/addons");
        when(request.method()).thenReturn(HttpMethod.GET);
        Controller controller = ControllerSelector.select(proxy, context);
        assertNotNull(controller);
        SerializedLambda lambda = getSerializedLambda(controller);
        assertNotNull(lambda);
        assertEquals(1, lambda.getCapturedArgCount());
        Object arg1 = lambda.getCapturedArg(0);
        assertInstanceOf(AddonController.class, arg1);
        assertEquals("getAddons", lambda.getImplMethodName());
    }

    @Test
    public void testSelectGetAssistantController() {
        when(request.path()).thenReturn("/openai/assistants/as1");
        when(request.method()).thenReturn(HttpMethod.GET);
        Controller controller = ControllerSelector.select(proxy, context);
        assertNotNull(controller);
        SerializedLambda lambda = getSerializedLambda(controller);
        assertNotNull(lambda);
        Object arg1 = lambda.getCapturedArg(0);
        Object arg2 = lambda.getCapturedArg(1);
        assertInstanceOf(AssistantController.class, arg1);
        assertEquals("as1", arg2);
    }

    @Test
    public void testSelectGetAssistantsController() {
        when(request.path()).thenReturn("/openai/assistants");
        when(request.method()).thenReturn(HttpMethod.GET);
        Controller controller = ControllerSelector.select(proxy, context);
        assertNotNull(controller);
        SerializedLambda lambda = getSerializedLambda(controller);
        assertNotNull(lambda);
        assertEquals(1, lambda.getCapturedArgCount());
        Object arg1 = lambda.getCapturedArg(0);
        assertInstanceOf(AssistantController.class, arg1);
        assertEquals("getAssistants", lambda.getImplMethodName());
    }

    @Test
    public void testSelectGetApplicationController() {
        when(request.path()).thenReturn("/openai/applications/app1");
        when(request.method()).thenReturn(HttpMethod.GET);
        CustomApplicationService customApplicationServiceMock = mock(CustomApplicationService.class);
        when(proxy.getCustomApplicationService()).thenReturn(customApplicationServiceMock);
        when(customApplicationServiceMock.includeCustomApplications()).thenReturn(true);
        Controller controller = ControllerSelector.select(proxy, context);
        assertNotNull(controller);
        SerializedLambda lambda = getSerializedLambda(controller);
        assertNotNull(lambda);
        Object arg1 = lambda.getCapturedArg(0);
        Object arg2 = lambda.getCapturedArg(1);
        assertInstanceOf(ApplicationController.class, arg1);
        assertEquals("app1", arg2);
    }

    @Test
    public void testSelectGetApplicationsController() {
        when(request.path()).thenReturn("/openai/applications");
        when(request.method()).thenReturn(HttpMethod.GET);
        CustomApplicationService customApplicationServiceMock = mock(CustomApplicationService.class);
        when(proxy.getCustomApplicationService()).thenReturn(customApplicationServiceMock);
        when(customApplicationServiceMock.includeCustomApplications()).thenReturn(false);
        Controller controller = ControllerSelector.select(proxy, context);
        assertNotNull(controller);
        SerializedLambda lambda = getSerializedLambda(controller);
        assertNotNull(lambda);
        assertEquals(1, lambda.getCapturedArgCount());
        Object arg1 = lambda.getCapturedArg(0);
        assertInstanceOf(ApplicationController.class, arg1);
        assertEquals("getApplications", lambda.getImplMethodName());
    }

    @Test
    public void testSelectListMetadataFileController() {
        when(request.path()).thenReturn("/v1/metadata/files/bucket/file1");
        when(request.method()).thenReturn(HttpMethod.GET);
        Controller controller = ControllerSelector.select(proxy, context);
        assertNotNull(controller);
        SerializedLambda lambda = getSerializedLambda(controller);
        assertNotNull(lambda);
        assertEquals(2, lambda.getCapturedArgCount());
        Object arg1 = lambda.getCapturedArg(0);
        Object arg2 = lambda.getCapturedArg(1);
        assertInstanceOf(FileMetadataController.class, arg1);
        assertEquals("/v1/metadata/files/bucket/file1", arg2);
    }

    @Test
    public void testSelectListMetadataFileController2() {
        when(request.path()).thenReturn("/v1/metadata/files/bucket/fol%2Fder%201/");
        when(request.method()).thenReturn(HttpMethod.GET);
        Controller controller = ControllerSelector.select(proxy, context);
        assertNotNull(controller);
        SerializedLambda lambda = getSerializedLambda(controller);
        assertNotNull(lambda);
        assertEquals(2, lambda.getCapturedArgCount());
        Object arg1 = lambda.getCapturedArg(0);
        Object arg2 = lambda.getCapturedArg(1);
        assertInstanceOf(FileMetadataController.class, arg1);
        assertEquals("/v1/metadata/files/bucket/fol%2Fder%201/", arg2);
    }

    @Test
    public void testSelectDownloadFileController() {
        when(request.path()).thenReturn("/v1/files/bucket/folder1/file1.txt");
        when(request.method()).thenReturn(HttpMethod.GET);
        Controller controller = ControllerSelector.select(proxy, context);
        assertNotNull(controller);
        SerializedLambda lambda = getSerializedLambda(controller);
        assertNotNull(lambda);
        assertEquals(2, lambda.getCapturedArgCount());
        Object arg1 = lambda.getCapturedArg(0);
        Object arg2 = lambda.getCapturedArg(1);
        assertInstanceOf(DownloadFileController.class, arg1);
        assertEquals("/v1/files/bucket/folder1/file1.txt", arg2);
    }

    @Test
    public void testSelectDownloadFileController2() {
        when(request.path()).thenReturn("/v1/files/bucket/fol%2Fder%201/file1%23.txt");
        when(request.method()).thenReturn(HttpMethod.GET);
        Controller controller = ControllerSelector.select(proxy, context);
        assertNotNull(controller);
        SerializedLambda lambda = getSerializedLambda(controller);
        assertNotNull(lambda);
        assertEquals(2, lambda.getCapturedArgCount());
        Object arg1 = lambda.getCapturedArg(0);
        Object arg2 = lambda.getCapturedArg(1);
        assertInstanceOf(DownloadFileController.class, arg1);
        assertEquals("/v1/files/bucket/fol%2Fder%201/file1%23.txt", arg2);
    }

    @Test
    public void testSelectPostDeploymentController() {
        when(request.path()).thenReturn("/openai/deployments/app1/completions");
        when(request.method()).thenReturn(HttpMethod.POST);
        Controller controller = ControllerSelector.select(proxy, context);
        assertNotNull(controller);
        SerializedLambda lambda = getSerializedLambda(controller);
        assertNotNull(lambda);
        assertEquals(3, lambda.getCapturedArgCount());
        Object arg1 = lambda.getCapturedArg(0);
        Object arg2 = lambda.getCapturedArg(1);
        Object arg3 = lambda.getCapturedArg(2);
        assertInstanceOf(DeploymentPostController.class, arg1);
        assertEquals("app1", arg2);
        assertEquals("completions", arg3);
    }

    @Test
    public void testSelectPostDeploymentControllerWithCustomApplication() {
        when(request.path()).thenReturn("/openai/deployments/applications/bucket/my-application/chat/completions");
        when(request.method()).thenReturn(HttpMethod.POST);
        Controller controller = ControllerSelector.select(proxy, context);
        assertNotNull(controller);
        SerializedLambda lambda = getSerializedLambda(controller);
        assertNotNull(lambda);
        assertEquals(3, lambda.getCapturedArgCount());
        Object arg1 = lambda.getCapturedArg(0);
        Object arg2 = lambda.getCapturedArg(1);
        Object arg3 = lambda.getCapturedArg(2);
        assertInstanceOf(DeploymentPostController.class, arg1);
        assertEquals("applications/bucket/my-application", arg2);
        assertEquals("chat/completions", arg3);
    }

    @Test
    public void testSelectUploadFileController() {
        when(request.path()).thenReturn("/v1/files/bucket/folder1/file1.txt");
        when(request.method()).thenReturn(HttpMethod.PUT);
        Controller controller = ControllerSelector.select(proxy, context);
        assertNotNull(controller);
        SerializedLambda lambda = getSerializedLambda(controller);
        assertNotNull(lambda);
        assertEquals(2, lambda.getCapturedArgCount());
        Object arg1 = lambda.getCapturedArg(0);
        Object arg2 = lambda.getCapturedArg(1);
        assertInstanceOf(UploadFileController.class, arg1);
        assertEquals("/v1/files/bucket/folder1/file1.txt", arg2);
    }

    @Test
    public void testSelectUploadFileController2() {
        when(request.path()).thenReturn("/v1/files/bucket/fol%2Fder%201/file1%23.txt");
        when(request.method()).thenReturn(HttpMethod.PUT);
        Controller controller = ControllerSelector.select(proxy, context);
        assertNotNull(controller);
        SerializedLambda lambda = getSerializedLambda(controller);
        assertNotNull(lambda);
        assertEquals(2, lambda.getCapturedArgCount());
        Object arg1 = lambda.getCapturedArg(0);
        Object arg2 = lambda.getCapturedArg(1);
        assertInstanceOf(UploadFileController.class, arg1);
        assertEquals("/v1/files/bucket/fol%2Fder%201/file1%23.txt", arg2);
    }

    @Test
    public void testSelectDeleteFileController() {
        when(request.path()).thenReturn("/v1/files/bucket/folder1/file1.txt");
        when(request.method()).thenReturn(HttpMethod.DELETE);
        Controller controller = ControllerSelector.select(proxy, context);
        assertNotNull(controller);
        SerializedLambda lambda = getSerializedLambda(controller);
        assertNotNull(lambda);
        assertEquals(2, lambda.getCapturedArgCount());
        Object arg1 = lambda.getCapturedArg(0);
        Object arg2 = lambda.getCapturedArg(1);
        assertInstanceOf(DeleteFileController.class, arg1);
        assertEquals("/v1/files/bucket/folder1/file1.txt", arg2);
    }

    @Test
    public void testSelectDeleteFileController2() {
        when(request.path()).thenReturn("/v1/files/bucket/fol%2Fder%201/file1%23.txt");
        when(request.method()).thenReturn(HttpMethod.DELETE);
        Controller controller = ControllerSelector.select(proxy, context);
        assertNotNull(controller);
        SerializedLambda lambda = getSerializedLambda(controller);
        assertNotNull(lambda);
        assertEquals(2, lambda.getCapturedArgCount());
        Object arg1 = lambda.getCapturedArg(0);
        Object arg2 = lambda.getCapturedArg(1);
        assertInstanceOf(DeleteFileController.class, arg1);
        assertEquals("/v1/files/bucket/fol%2Fder%201/file1%23.txt", arg2);
    }

    @Test
    public void testSelectRateResponseController() {
        when(request.path()).thenReturn("/v1/app/rate");
        when(request.method()).thenReturn(HttpMethod.POST);
        Controller controller = ControllerSelector.select(proxy, context);
        assertNotNull(controller);
        SerializedLambda lambda = getSerializedLambda(controller);
        assertNotNull(lambda);
        Object arg1 = lambda.getCapturedArg(0);
        Object arg2 = lambda.getCapturedArg(1);
        assertInstanceOf(DeploymentFeatureController.class, arg1);
        assertEquals("app", arg2);
    }

    @Test
    public void testSelectTokenizeController() {
        when(request.path()).thenReturn("/v1/deployments/app/tokenize");
        when(request.method()).thenReturn(HttpMethod.POST);
        Controller controller = ControllerSelector.select(proxy, context);
        assertNotNull(controller);
        SerializedLambda lambda = getSerializedLambda(controller);
        assertNotNull(lambda);
        Object arg1 = lambda.getCapturedArg(0);
        Object arg2 = lambda.getCapturedArg(1);
        assertInstanceOf(DeploymentFeatureController.class, arg1);
        assertEquals("app", arg2);
    }

    @Test
    public void testSelectTruncatePromptController() {
        when(request.path()).thenReturn("/v1/deployments/app/truncate_prompt");
        when(request.method()).thenReturn(HttpMethod.POST);
        Controller controller = ControllerSelector.select(proxy, context);
        assertNotNull(controller);
        SerializedLambda lambda = getSerializedLambda(controller);
        assertNotNull(lambda);
        Object arg1 = lambda.getCapturedArg(0);
        Object arg2 = lambda.getCapturedArg(1);
        assertInstanceOf(DeploymentFeatureController.class, arg1);
        assertEquals("app", arg2);
    }

    @Test
    public void testSelectRouteController() {
        when(request.path()).thenReturn("/route/request");
        when(request.method()).thenReturn(HttpMethod.POST);
        Controller controller = ControllerSelector.select(proxy, context);
        assertNotNull(controller);
        assertInstanceOf(RouteController.class, controller);
    }

    @Test
    void testSelectDeploymentWithSpecialName() {
        when(request.path()).thenReturn("/openai/deployments/deployment_x-y%2B%2F");
        when(request.method()).thenReturn(HttpMethod.GET);
        Controller controller = ControllerSelector.select(proxy, context);
        assertNotNull(controller);
        SerializedLambda lambda = getSerializedLambda(controller);
        assertNotNull(lambda);
        Object arg1 = lambda.getCapturedArg(0);
        Object arg2 = lambda.getCapturedArg(1);
        assertInstanceOf(DeploymentController.class, arg1);
        assertEquals("deployment_x-y+/", arg2);
    }

    @Test
    void testFailDeploymentWithSlash() {
        when(request.path()).thenReturn("/openai/deployments/deployment/xy");
        when(request.method()).thenReturn(HttpMethod.GET);
        Controller controller = ControllerSelector.select(proxy, context);
        assertInstanceOf(RouteController.class, controller);
    }

    @Test
    void testFailDeploymentWithBadPrefix() {
        when(request.path()).thenReturn("/prefix/openai/deployments/deployment");
        when(request.method()).thenReturn(HttpMethod.GET);
        Controller controller = ControllerSelector.select(proxy, context);
        assertInstanceOf(RouteController.class, controller);
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
