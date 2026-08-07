package com.epam.aidial.core.server.function;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.function.request.ChatCompletionRequest;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.DeploymentService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;


@ExtendWith(MockitoExtension.class)
public class AutoShareDeploymentFnTest {

    @Mock
    private Proxy proxy;

    @Mock
    private ProxyContext context;

    @Mock
    private Config config;

    @Mock
    private EncryptionService encryptionService;

    @Mock
    private AccessService accessService;

    @Mock
    private DeploymentService deploymentService;

    private ApiKeyData proxyApiKeyData;

    @InjectMocks
    private AutoShareDeploymentFn fn;

    private static final ChatCompletionRequest EMPTY_OBJECT = new ChatCompletionRequest(ProxyUtil.MAPPER.createObjectNode());

    @BeforeEach
    public void beforeEach() {
        proxyApiKeyData = new ApiKeyData();
        lenient().when(context.getConfig()).thenReturn(config);
    }

    @Test
    public void testApply_WhenInitialDeploymentIsNull() {
        assertFalse(fn.apply(EMPTY_OBJECT));
        assertTrue(proxyApiKeyData.getAttachedDeployments().isEmpty());
    }

    @Test
    public void testApply_WhenInitialDeploymentIsPublicResource() {
        when(context.getInitialDeployment()).thenReturn("applications/public/my-app");

        assertFalse(fn.apply(EMPTY_OBJECT));
        assertTrue(proxyApiKeyData.getAttachedDeployments().isEmpty());
    }

    @Test
    public void testApply_WhenInitialDeploymentIsForbidden() {
        when(encryptionService.decrypt(anyString())).thenReturn("/Users/user/");
        when(proxy.getEncryptionService()).thenReturn(encryptionService);
        when(proxy.getAccessService()).thenReturn(accessService);
        when(context.getInitialDeployment()).thenReturn("applications/123/my-app");
        when(accessService.hasReadAccess(any(ResourceDescriptor.class), eq(context))).thenReturn(false);

        assertThrows(HttpException.class, () -> fn.apply(EMPTY_OBJECT));
        assertTrue(proxyApiKeyData.getAttachedDeployments().isEmpty());
    }

    @Test
    public void testApply_WhenInitialDeploymentIdContainsSpace() {
        when(encryptionService.decrypt(anyString())).thenReturn("/Users/user/");
        when(proxy.getEncryptionService()).thenReturn(encryptionService);
        when(proxy.getAccessService()).thenReturn(accessService);
        when(context.getInitialDeployment()).thenReturn("applications/123/demo a_0.0.1");
        when(accessService.hasReadAccess(any(ResourceDescriptor.class), eq(context))).thenReturn(true);
        when(context.getProxyApiKeyData()).thenReturn(proxyApiKeyData);
        when(proxy.getDeploymentService()).thenReturn(deploymentService);

        assertFalse(fn.apply(EMPTY_OBJECT));
        assertFalse(proxyApiKeyData.getAttachedDeployments().isEmpty());
        assertEquals(ResourceAccessType.READ_ONLY,
                proxyApiKeyData.getAttachedDeployments().get("applications/123/demo%20a_0.0.1").accessTypes());
    }

    @Test
    public void testApply_WhenInitialDeploymentIsApp() {
        when(encryptionService.decrypt(anyString())).thenReturn("/Users/user/");
        when(proxy.getEncryptionService()).thenReturn(encryptionService);
        when(proxy.getAccessService()).thenReturn(accessService);
        when(context.getInitialDeployment()).thenReturn("applications/123/my-app");
        when(accessService.hasReadAccess(any(ResourceDescriptor.class), eq(context))).thenReturn(true);
        when(context.getProxyApiKeyData()).thenReturn(proxyApiKeyData);
        when(proxy.getDeploymentService()).thenReturn(deploymentService);

        assertFalse(fn.apply(EMPTY_OBJECT));
        assertFalse(proxyApiKeyData.getAttachedDeployments().isEmpty());
        assertEquals(ResourceAccessType.READ_ONLY, proxyApiKeyData.getAttachedDeployments().get("applications/123/my-app").accessTypes());
    }

    @Test
    public void testApply_WhenInitialDeploymentIsBareNameModel() {
        when(context.getInitialDeployment()).thenReturn("gpt-5-2025-08-07");
        when(config.selectDeployment("gpt-5-2025-08-07")).thenReturn(new Model());

        assertFalse(fn.apply(EMPTY_OBJECT));
        assertTrue(proxyApiKeyData.getAttachedDeployments().isEmpty());
    }

    @Test
    public void testApply_WhenInitialDeploymentIsCanonicalIdModel() {
        when(context.getInitialDeployment()).thenReturn("models/platform/gpt-5-2025-08-07");
        when(config.selectDeployment("models/platform/gpt-5-2025-08-07")).thenReturn(new Model());

        assertFalse(fn.apply(EMPTY_OBJECT));
        assertTrue(proxyApiKeyData.getAttachedDeployments().isEmpty());
    }
}
