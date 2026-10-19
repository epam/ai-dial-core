package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsService;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.DeploymentService;
import com.epam.aidial.core.server.service.ToolSetService;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import io.vertx.core.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolSetControllerTest {

    @Mock
    private ProxyContext context;
    @Mock
    private AsyncTaskExecutor taskExecutor;
    @Mock
    private DeploymentService deploymentService;
    @Mock
    private ToolSetService toolSetService;
    @Mock
    private ResourceAuthSettingsService resourceAuthSettingsService;
    @Mock
    private EncryptionService encryptionService;
    @Mock
    private Proxy proxy;

    private ToolSetController controller;

    @BeforeEach
    void setUp() {
        when(context.getProxy()).thenReturn(proxy);
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor);
        when(proxy.getDeploymentService()).thenReturn(deploymentService);
        when(proxy.getToolSetService()).thenReturn(toolSetService);

        // Mock the async task executor to run the callable immediately
        doAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            try {
                return Future.succeededFuture(callable.call());
            } catch (Exception e) {
                return Future.failedFuture(e);
            }
        }).when(taskExecutor).submit(any(Callable.class));

        controller = new ToolSetController(context);
    }

    @Test
    void getToolSet_setsResourceAuthStatuses() {
        String toolSetId = "toolsets/test-toolset";
        ToolSet toolSet = new ToolSet();
        toolSet.setName(toolSetId);
        toolSet.setAuthSettings(new ResourceAuthSettings());

        when(deploymentService.findDeployment(context, toolSetId)).thenReturn(toolSet);
        when(proxy.getResourceAuthSettingsService()).thenReturn(resourceAuthSettingsService);
        when(proxy.getEncryptionService()).thenReturn(encryptionService);
        when(context.getConfig()).thenReturn(mock(Config.class));
        when(context.getApiKeyData()).thenReturn(mock(ApiKeyData.class));
        when(context.getUserId()).thenReturn("user-123");
        when(context.getInitiatorId()).thenReturn("initiator-id");
        when(encryptionService.encrypt("Users/user-123/")).thenReturn("encrypted-user-123");

        controller.getToolSet(toolSetId);

        ArgumentCaptor<CredentialsLocator> credentialsLocatorCaptor = ArgumentCaptor.forClass(CredentialsLocator.class);
        verify(resourceAuthSettingsService).setResourceAuthStatuses(
                credentialsLocatorCaptor.capture(), eq(toolSet.getAuthSettings()), eq("initiator-id"));
        assertEquals(toolSetId, credentialsLocatorCaptor.getValue().getResourceId());
    }

    //TODO: add more tests

}
