package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.credentials.data.registration.AuthorizationServerMetadata;
import com.epam.aidial.core.credentials.data.registration.ClientRegistration;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsEncryptionService;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsService;
import com.epam.aidial.core.credentials.service.ResourceCredentialsService;
import com.epam.aidial.core.credentials.service.registration.ResourceRegistrationService;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.util.CredentialsLocatorFactory;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolSetRepairServiceTest {

    @Mock
    private ResourceService resourceService;
    @Mock
    private ResourceAuthSettingsEncryptionService encryptionService;
    @Mock
    private ResourceCredentialsService credentialsService;
    @Mock
    private ResourceRegistrationService registrationService;
    @Mock
    private ResourceAuthSettingsService authSettingsService;

    @InjectMocks
    private ToolSetRepairService repairService;

    @Test
    void repairSucceedsEvenWhenCredentialCleanupFails() {
        String toolsetUrl = "toolsets/bucket/toolset@";

        ResourceDescriptor resource = mock(ResourceDescriptor.class);
        when(resource.getUrl()).thenReturn(toolsetUrl);
        when(resource.getBucketName()).thenReturn("bucket");
        when(resource.getBucketLocation()).thenReturn("Users/user/");

        ResourceAuthSettings authSettings = new ResourceAuthSettings();
        authSettings.setAuthenticationType(AuthenticationType.OAUTH);
        authSettings.setDynamicallyRegistered(true);

        ToolSet toolSet = new ToolSet();
        toolSet.setEndpoint("http://as.example.com/mcp");
        toolSet.setAuthSettings(authSettings);

        ResourceItemMetadata meta = new ResourceItemMetadata();
        meta.setAuthor("test-author");

        when(resourceService.getResourceWithMetadata(resource, EtagHeader.ANY))
                .thenReturn(Pair.of(meta, ProxyUtil.convertToString(toolSet)));

        when(registrationService.discoverMetadata(any(), any()))
                .thenReturn(mock(AuthorizationServerMetadata.class));

        when(registrationService.register(any(), any(), any(), anyBoolean()))
                .thenReturn(mock(ClientRegistration.class));

        when(resourceService.computeResource(eq(resource), eq(EtagHeader.ANY), eq("test-author"), any()))
                .thenReturn(meta);

        doThrow(new RuntimeException("Redis unavailable"))
                .when(credentialsService).deleteResourceCredentials(any());

        ProxyContext context = mock(ProxyContext.class);

        try (MockedStatic<CredentialsLocatorFactory> factory = mockStatic(CredentialsLocatorFactory.class)) {
            factory.when(() -> CredentialsLocatorFactory.fromAnyUrl(
                    eq(toolsetUrl), eq(context), eq(ResourceTypes.TOOL_SET)))
                    .thenReturn(mock(CredentialsLocator.class));

            assertDoesNotThrow(() -> repairService.repair(resource, context));
            verify(credentialsService).deleteResourceCredentials(any());
        }
    }
}
