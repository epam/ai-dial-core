package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class DeploymentServiceTest {

    @Mock
    private EncryptionService encryptionService;

    @Mock
    private ApplicationService applicationService;

    @Mock
    private AccessService accessService;

    @Mock
    private ProxyContext context;

    @Mock
    private Config config;

    @Mock
    private ApplicationSchemaService applicationSchemaService;

    @InjectMocks
    private DeploymentService service;

    @BeforeEach
    public void beforeEach() {
        when(context.getConfig()).thenReturn(config);
    }

    @Test
    public void testFindDeployment_WhenDeploymentExistsInConfig() {
        Application application = new Application();
        when(config.selectDeployment("id")).thenReturn(application);

        Deployment deployment = service.findDeployment(context, "id");
        assertEquals(application, deployment);
    }

    @Test
    public void testFindDeployment_WhenDeploymentExistsInConfigButItNotPermitted() {
        Application application = new Application();
        application.setUserRoles(Set.of("power-user"));
        when(config.selectDeployment("id")).thenReturn(application);

        assertThrows(PermissionDeniedException.class, () -> service.findDeployment(context, "id"));
    }

    @Test
    public void testFindDeployment_WhenCustomAppHasInvalidPath() {

        assertThrows(ResourceNotFoundException.class, () -> service.findDeployment(context, "my/application/id"));
    }

    @Test
    public void testFindDeployment_WhenCustomAppIsFolder() {

        assertThrows(ResourceNotFoundException.class, () -> service.findDeployment(context, "applications/public/my-app/"));
    }

    @Test
    public void testFindDeployment_WhenAccessDenied() {

        assertThrows(PermissionDeniedException.class, () -> service.findDeployment(context, "files/public/my-app"));
    }

    @Test
    public void testFindDeployment_WhenCustomAppHasNoAccess() {
        when(accessService.hasReadAccess(any(ResourceDescriptor.class), eq(context))).thenReturn(false);
        assertThrows(PermissionDeniedException.class, () -> service.findDeployment(context, "applications/public/my-app"));
    }

    @Test
    public void testFindDeployment_Success() {
        when(accessService.hasReadAccess(any(ResourceDescriptor.class), eq(context))).thenReturn(true);
        Application application = new Application();
        when(applicationService.getApplication(any(ResourceDescriptor.class))).thenReturn(Pair.of(new ResourceItemMetadata(), application));

        Deployment deployment = service.findDeployment(context, "applications/public/my-app");

        assertEquals(application, deployment);
    }

    @Test
    public void testGetInterceptors() {
        when(config.getGlobalInterceptors()).thenReturn(List.of("i1", "i2"));
        when(config.getInterceptors()).thenReturn(Map.of());
        Application application = new Application();
        when(applicationSchemaService.getInterceptors(application)).thenReturn(List.of("i3", "i2"));
        application.setInterceptors(List.of("i4", "i3"));

        List<String> result = service.getInterceptors(context, application, InterfaceType.OPENAI_CHAT_COMPLETIONS);

        assertEquals(List.of("i1", "i2", "i3", "i4"), result);
    }

    @Test
    public void testGetInterceptors_WhenInterceptorDoesNotServeRequestedInterface_ThenItIsSkipped() {
        when(config.getGlobalInterceptors()).thenReturn(List.of("i1", "i2"));
        Application application = new Application();
        when(applicationSchemaService.getInterceptors(application)).thenReturn(List.of());
        application.setInterceptors(List.of());

        Interceptor responsesOnly = new Interceptor();
        responsesOnly.setResponsesEndpoint("http://responses-interceptor");
        Interceptor chatCompletionsOnly = new Interceptor();
        chatCompletionsOnly.setEndpoint("http://chat-interceptor");
        when(config.getInterceptors()).thenReturn(Map.of("i1", responsesOnly, "i2", chatCompletionsOnly));
        when(config.getTranslators()).thenReturn(Map.of());

        List<String> chatResult = service.getInterceptors(context, application, InterfaceType.OPENAI_CHAT_COMPLETIONS);
        assertEquals(List.of("i2"), chatResult);

        List<String> responsesResult = service.getInterceptors(context, application, InterfaceType.OPENAI_RESPONSES);
        assertEquals(List.of("i1"), responsesResult);
    }

    @Test
    public void testGetInterceptors_WhenInterceptorNameDoesNotResolve_ThenItIsKept() {
        when(config.getGlobalInterceptors()).thenReturn(List.of("unknown"));
        when(config.getInterceptors()).thenReturn(Map.of());
        Application application = new Application();
        when(applicationSchemaService.getInterceptors(application)).thenReturn(List.of());
        application.setInterceptors(List.of());

        List<String> result = service.getInterceptors(context, application, InterfaceType.OPENAI_CHAT_COMPLETIONS);

        assertEquals(List.of("unknown"), result);
    }
}
