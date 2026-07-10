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
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
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

    /**
     * Verifies that repair() decrypts storedSettings inside the computeResource callback before
     * calling applyRegistration() and re-encrypting. Without the decrypt step, any fields NOT
     * replaced by applyRegistration (e.g. codeVerifier when the new registration has no PKCE)
     * would still carry their encrypted value from storage and be double-encrypted on the write.
     */
    @Test
    @SuppressWarnings("unchecked")
    void repairDecryptsStoredSettingsBeforeReEncryptingInComputeCallback() {
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

        // No PKCE in new registration: codeChallengeMethod is null, so applyRegistration() will
        // not overwrite codeVerifier. If storedSettings.codeVerifier is still encrypted at that
        // point, encrypt() would double-encrypt it.
        ClientRegistration registration = ClientRegistration.builder()
                .clientId("new-client-id")
                .clientSecret("new-client-secret")
                .authorizationEndpoint("http://as.example.com/authorize")
                .tokenEndpoint("http://as.example.com/token")
                .build();

        when(registrationService.register(any(), any(), any(), anyBoolean()))
                .thenReturn(registration);

        // Simulate storage: codeVerifier is encrypted (non-null value present).
        ResourceAuthSettings storedAuthSettings = new ResourceAuthSettings();
        storedAuthSettings.setAuthenticationType(AuthenticationType.OAUTH);
        storedAuthSettings.setDynamicallyRegistered(true);
        storedAuthSettings.setCodeVerifier("c2VjcmV0LWVuY3J5cHRlZA==");
        ToolSet storedToolSet = new ToolSet();
        storedToolSet.setAuthSettings(storedAuthSettings);
        String storedJson = ProxyUtil.convertToString(storedToolSet);

        // Track call order for decrypt/applyRegistration/encrypt to verify the invariant:
        // both decrypts (outer + inner-callback) happen before applyRegistration, which happens before encrypt.
        List<String> callOrder = new ArrayList<>();
        doAnswer(inv -> {
            callOrder.add("decrypt");
            return null;
        }).when(encryptionService).decrypt(any(), any(), any());
        doAnswer(inv -> {
            callOrder.add("encrypt");
            return null;
        }).when(encryptionService).encrypt(any(), any(), any());
        doAnswer(inv -> {
            callOrder.add("applyRegistration");
            return null;
        }).when(authSettingsService).applyRegistration(any(), any());

        when(resourceService.computeResource(eq(resource), eq(EtagHeader.ANY), eq("test-author"), any()))
                .thenAnswer(invocation -> {
                    Function<String, String> fn = invocation.getArgument(3);
                    fn.apply(storedJson);
                    return meta;
                });

        ProxyContext context = mock(ProxyContext.class);

        try (MockedStatic<CredentialsLocatorFactory> factory = mockStatic(CredentialsLocatorFactory.class)) {
            factory.when(() -> CredentialsLocatorFactory.fromAnyUrl(
                    eq(toolsetUrl), eq(context), eq(ResourceTypes.TOOL_SET)))
                    .thenReturn(mock(CredentialsLocator.class));

            repairService.repair(resource, context);
        }

        // Expected: outer decrypt → inner callback decrypt → applyRegistration → encrypt.
        // Enforces that no field reaches encrypt() in its encrypted-from-storage state.
        assertEquals(List.of("decrypt", "decrypt", "applyRegistration", "encrypt"), callOrder);
    }

    @Test
    void repairReturns424WhenDiscoveryReturnsNull() {
        ResourceDescriptor resource = buildDcrToolsetResource("toolsets/bucket/toolset@");

        when(registrationService.discoverMetadata(any(), any())).thenReturn(null);

        HttpException ex = assertThrows(HttpException.class,
                () -> repairService.repair(resource, mock(ProxyContext.class)));
        assertEquals(HttpStatus.FAILED_DEPENDENCY, ex.getStatus());
    }

    private ResourceDescriptor buildDcrToolsetResource(String toolsetUrl) {
        ResourceAuthSettings authSettings = new ResourceAuthSettings();
        authSettings.setAuthenticationType(AuthenticationType.OAUTH);
        authSettings.setDynamicallyRegistered(true);

        ToolSet toolSet = new ToolSet();
        toolSet.setEndpoint("http://as.example.com/mcp");
        toolSet.setAuthSettings(authSettings);

        ResourceItemMetadata meta = new ResourceItemMetadata();
        meta.setAuthor("test-author");

        ResourceDescriptor resource = mock(ResourceDescriptor.class);
        when(resource.getUrl()).thenReturn(toolsetUrl);
        when(resource.getBucketName()).thenReturn("bucket");
        when(resource.getBucketLocation()).thenReturn("Users/user/");
        when(resourceService.getResourceWithMetadata(resource, EtagHeader.ANY))
                .thenReturn(Pair.of(meta, ProxyUtil.convertToString(toolSet)));

        return resource;
    }

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
