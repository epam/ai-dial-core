package com.epam.aidial.core.server.function;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.credentials.mapper.CredentialsDescriptorToResourceDescriptorMapper;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.ApplicationSchemaService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class CollectToolSetsFnTest {

    @Mock
    private Proxy proxy;

    @Mock
    private ProxyContext context;

    @Mock
    private ApplicationSchemaService applicationSchemaService;

    @Mock
    private EncryptionService encryptionService;

    @Mock
    private AccessService accessService;

    @Mock
    private CredentialsDescriptorToResourceDescriptorMapper credentialsDescriptorToResourceDescriptorMapper;

    @InjectMocks
    private CollectToolSetsFn fn;

    private static final ObjectNode EMPTY_OBJECT = ProxyUtil.MAPPER.createObjectNode();

    @Test
    public void testApply_NotApplication() {
        when(context.getDeployment()).thenReturn(new Model());
        assertFalse(fn.apply(EMPTY_OBJECT));
    }

    @Test
    public void testApply_NoToolSets() {
        Application application = new Application();
        when(context.getDeployment()).thenReturn(application);
        when(proxy.getApplicationSchemaService()).thenReturn(applicationSchemaService);
        when(applicationSchemaService.getToolSets(application)).thenReturn(List.of());
        assertFalse(fn.apply(EMPTY_OBJECT));
    }

    @Test
    public void testApply_WithToolSets() {
        Application application = new Application();
        when(context.getProxy()).thenReturn(proxy);
        when(context.getDeployment()).thenReturn(application);
        when(context.getUserSub()).thenReturn("userSub");
        when(proxy.getApplicationSchemaService()).thenReturn(applicationSchemaService);
        when(proxy.getEncryptionService()).thenReturn(encryptionService);
        when(proxy.getCredentialsDescriptorToResourceDescriptorMapper()).thenReturn(credentialsDescriptorToResourceDescriptorMapper);
        when(encryptionService.encrypt(anyString())).thenReturn("bucket/");
        ResourceDescriptor privateTool = mock(ResourceDescriptor.class);
        when(privateTool.isPublic()).thenReturn(false);
        when(privateTool.getUrl()).thenReturn("tools/bucket/my-tool");
        ResourceDescriptor publicTool = mock(ResourceDescriptor.class);
        when(publicTool.isPublic()).thenReturn(true);
        when(applicationSchemaService.getToolSets(application)).thenReturn(List.of(privateTool, publicTool));

        when(proxy.getAccessService()).thenReturn(accessService);
        when(accessService.hasReadAccess(privateTool, context)).thenReturn(true);

        ApiKeyData source = new ApiKeyData();
        when(context.getApiKeyData()).thenReturn(source);
        ApiKeyData dest = new ApiKeyData();
        when(context.getProxyApiKeyData()).thenReturn(dest);

        assertFalse(fn.apply(EMPTY_OBJECT));

        Assertions.assertEquals(1, dest.getAttachedToolSets().size());
        String toolsetId = dest.getAttachedToolSets().keySet().iterator().next();
        Assertions.assertEquals("tools/bucket/my-tool", toolsetId);
        Assertions.assertEquals(ResourceAccessType.READ_ONLY, dest.getAttachedToolSets().get(toolsetId).accessTypes());
    }

    @Test
    void testApply_WithToolSetWithCreds() {
        // Given
        Application application = new Application();
        when(context.getProxy()).thenReturn(proxy);
        when(context.getDeployment()).thenReturn(application);
        when(context.getUserSub()).thenReturn("userSub");
        when(proxy.getApplicationSchemaService()).thenReturn(applicationSchemaService);
        when(proxy.getEncryptionService()).thenReturn(encryptionService);
        when(proxy.getCredentialsDescriptorToResourceDescriptorMapper()).thenReturn(credentialsDescriptorToResourceDescriptorMapper);
        when(encryptionService.encrypt(anyString())).thenReturn("bucket");
        ResourceDescriptor privateTool = mock(ResourceDescriptor.class);
        when(privateTool.isPublic()).thenReturn(false);
        when(privateTool.getUrl()).thenReturn("tools/bucket/my%20tool");
        ResourceDescriptor publicTool = mock(ResourceDescriptor.class);
        when(publicTool.isPublic()).thenReturn(true);
        when(applicationSchemaService.getToolSets(application)).thenReturn(List.of(privateTool, publicTool));

        when(proxy.getAccessService()).thenReturn(accessService);
        when(accessService.hasReadAccess(privateTool, context)).thenReturn(true);

        ResourceDescriptor privateToolCredentials = mock(ResourceDescriptor.class);
        when(privateToolCredentials.getUrl()).thenReturn("credentials/bucket/tools/bucket/my%20tool");
        when(credentialsDescriptorToResourceDescriptorMapper.map(any())).thenReturn(privateToolCredentials);

        try (MockedStatic<ResourceDescriptorFactory> mockedStatic = mockStatic(ResourceDescriptorFactory.class)) {
            mockedStatic.when(() -> ResourceDescriptorFactory.fromAnyUrl("tools/bucket/my%20tool", encryptionService))
                    .thenReturn(privateToolCredentials);

            when(accessService.hasReadAccess(privateToolCredentials, context)).thenReturn(true);

            ApiKeyData source = new ApiKeyData();
            when(context.getApiKeyData()).thenReturn(source);
            ApiKeyData dest = new ApiKeyData();
            when(context.getProxyApiKeyData()).thenReturn(dest);

            // When
            assertFalse(fn.apply(EMPTY_OBJECT));

            // Then
            mockedStatic.verify(() -> ResourceDescriptorFactory.fromAnyUrl("tools/bucket/my%20tool", encryptionService));

            Assertions.assertEquals(1, dest.getAttachedToolSets().size());
            String toolsetId = dest.getAttachedToolSets().keySet().iterator().next();
            Assertions.assertEquals("tools/bucket/my%20tool", toolsetId);
            Assertions.assertEquals(ResourceAccessType.READ_ONLY, dest.getAttachedToolSets().get(toolsetId).accessTypes());

            Assertions.assertEquals(1, dest.getAttachedResourceCredentials().size());
            String credentialsId = dest.getAttachedResourceCredentials().keySet().iterator().next();
            Assertions.assertEquals("credentials/bucket/tools/bucket/my%20tool", credentialsId);
            Assertions.assertEquals(ResourceAccessType.READ_ONLY, dest.getAttachedResourceCredentials().get(credentialsId).accessTypes());
        }
    }
}
