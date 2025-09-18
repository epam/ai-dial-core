package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
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

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
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
    private Proxy proxy;
    @Mock
    private EncryptionService encryptionService;

    private ToolSetController controller;

    @BeforeEach
    void setUp() {
        when(context.getProxy()).thenReturn(proxy);
        when(proxy.getEncryptionService()).thenReturn(encryptionService);
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor);
        when(proxy.getDeploymentService()).thenReturn(deploymentService);
        when(proxy.getToolSetService()).thenReturn(toolSetService);
        when(proxy.getResourceAuthSettingsService()).thenReturn(resourceAuthSettingsService);

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
    void getToolSet_usesCorrectCredentialsLocator() {
        String toolSetId = "toolsets/test-toolset";
        ToolSet toolSet = new ToolSet();
        toolSet.setName(toolSetId);
        toolSet.setAuthSettings(new ResourceAuthSettings());

        ApiKeyData apiKeyData = mock(ApiKeyData.class);
        when(context.getApiKeyData()).thenReturn(apiKeyData);
        when(context.getUserSub()).thenReturn("user-123");
        when(deploymentService.findDeployment(context, toolSetId)).thenReturn(toolSet);
        when(encryptionService.encrypt("Users/user-123/")).thenReturn("encrypted-user-123");

        controller.getToolSet(toolSetId);

        ArgumentCaptor<CredentialsLocator> credentialsLocatorCaptor = ArgumentCaptor.forClass(CredentialsLocator.class);
        verify(resourceAuthSettingsService).setResourceAuthStatuses(credentialsLocatorCaptor.capture(), any(), any());
        CredentialsLocator credentialsLocator = credentialsLocatorCaptor.getValue();
        assertEquals(toolSetId, credentialsLocator.getResourceId());
        assertEquals(2, credentialsLocator.getBuckets().size());
        Set<String> bucketNames = credentialsLocator.getBuckets().values().stream()
                .map(BucketInfo::name)
                .collect(Collectors.toSet());
        assertEquals(Set.of("public", "encrypted-user-123"), bucketNames);
    }

}
