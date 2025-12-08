package com.epam.aidial.core.server.function;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.AutoSharedData;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.ApplicationSchemaService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class CollectDeploymentsFnTest {

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

    @InjectMocks
    private CollectDeploymentsFn fn;

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
        when(applicationSchemaService.getDeployments(application)).thenReturn(List.of());
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
        when(encryptionService.encrypt(anyString())).thenReturn("bucket/");
        ResourceDescriptor privateTool = mock(ResourceDescriptor.class);
        when(privateTool.isPublic()).thenReturn(false);
        when(privateTool.getUrl()).thenReturn("tools/bucket/my-tool");
        ResourceDescriptor publicTool = mock(ResourceDescriptor.class);
        when(publicTool.isPublic()).thenReturn(true);
        when(applicationSchemaService.getDeployments(application)).thenReturn(List.of(privateTool, publicTool));

        when(proxy.getAccessService()).thenReturn(accessService);
        when(accessService.hasReadAccess(privateTool, context)).thenReturn(true);

        ApiKeyData source = new ApiKeyData();
        when(context.getApiKeyData()).thenReturn(source);
        ApiKeyData dest = new ApiKeyData();
        when(context.getProxyApiKeyData()).thenReturn(dest);

        assertFalse(fn.apply(EMPTY_OBJECT));

        Assertions.assertEquals(1, dest.getAttachedDeployments().size());
        String toolsetId = dest.getAttachedDeployments().keySet().iterator().next();
        Assertions.assertEquals("tools/bucket/my-tool", toolsetId);
        Assertions.assertEquals(ResourceAccessType.READ_ONLY, dest.getAttachedDeployments().get(toolsetId).accessTypes());
    }

    @Test
    void testApply_WithToolSetWithCreds() {
        Application application = new Application();
        when(context.getProxy()).thenReturn(proxy);
        when(context.getDeployment()).thenReturn(application);
        when(context.getUserSub()).thenReturn("userSub");
        when(proxy.getApplicationSchemaService()).thenReturn(applicationSchemaService);
        when(proxy.getEncryptionService()).thenReturn(encryptionService);
        when(encryptionService.encrypt("Users/userSub/")).thenReturn("encryptedBucket");
        when(encryptionService.decrypt("encryptedBucket")).thenReturn("decryptedBucket/");
        ResourceDescriptor privateTool = mock(ResourceDescriptor.class);
        when(privateTool.isPublic()).thenReturn(false);
        when(privateTool.getUrl()).thenReturn("toolsets/encryptedBucket/my%20tool");
        ResourceDescriptor publicTool = mock(ResourceDescriptor.class);
        when(publicTool.isPublic()).thenReturn(true);
        when(applicationSchemaService.getDeployments(application)).thenReturn(List.of(privateTool, publicTool));

        when(proxy.getAccessService()).thenReturn(accessService);
        when(accessService.hasReadAccess(any(ResourceDescriptor.class), eq(context))).thenReturn(true);

        ApiKeyData source = new ApiKeyData();
        when(context.getApiKeyData()).thenReturn(source);
        ApiKeyData dest = new ApiKeyData();
        when(context.getProxyApiKeyData()).thenReturn(dest);

        assertFalse(fn.apply(EMPTY_OBJECT));

        Assertions.assertEquals(1, dest.getAttachedDeployments().size());
        String toolsetId = dest.getAttachedDeployments().keySet().iterator().next();
        Assertions.assertEquals("toolsets/encryptedBucket/my%20tool", toolsetId);
        Assertions.assertEquals(ResourceAccessType.READ_ONLY, dest.getAttachedDeployments().get(toolsetId).accessTypes());

        Map<String, AutoSharedData> attachedResourceCredentials = dest.getAttachedResourceCredentials();
        Assertions.assertEquals(1, dest.getAttachedResourceCredentials().size());

        List<AutoSharedData> autoSharedData = attachedResourceCredentials.values().stream().toList();
        Assertions.assertEquals(ResourceAccessType.READ_ONLY, autoSharedData.getFirst().accessTypes());

        List<String> autoSharedResources = attachedResourceCredentials.keySet().stream().toList();
        Assertions.assertEquals("credentials/encryptedBucket/toolsets/encryptedBucket/my%20tool", autoSharedResources.getFirst());
    }
}
