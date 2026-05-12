package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsEncryptionService;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.security.EncryptionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthSettingsResolverTest {

    @Mock
    private EncryptionService encryptionService;
    @Mock
    private ResourceAuthSettingsEncryptionService authSettingsEncryptionService;
    @Mock
    private ProxyContext context;
    @Mock
    private Config config;

    @InjectMocks
    private AuthSettingsResolver resolver;

    @Test
    void resolve_configDefinedToolset_returnsOriginalReferenceWithoutDecryption() {
        ToolSet toolSet = toolSet("config-toolset", "plaintext-secret");
        ResourceAuthSettings stored = toolSet.getAuthSettings();
        when(context.getConfig()).thenReturn(config);
        when(config.isDeploymentExists("config-toolset")).thenReturn(true);

        ResourceAuthSettings resolved = resolver.resolve(toolSet, context);

        assertSame(stored, resolved, "Config-defined toolset must yield the original authSettings reference");
        verifyNoInteractions(encryptionService, authSettingsEncryptionService);
    }

    @Test
    void resolve_resourceStoredToolset_returnsDecryptedCopy() {
        String url = "toolsets/encrypted-bucket/my-toolset";
        ToolSet toolSet = toolSet(url, "ciphertext-secret");
        ResourceAuthSettings encrypted = toolSet.getAuthSettings();
        when(context.getConfig()).thenReturn(config);
        when(config.isDeploymentExists(url)).thenReturn(false);
        when(encryptionService.decrypt("encrypted-bucket")).thenReturn("Users/owner/");
        doAnswer(invocation -> {
            ResourceAuthSettings target = invocation.getArgument(2);
            target.setClientSecret("plaintext-secret");
            return null;
        }).when(authSettingsEncryptionService).decrypt(
                eq("toolsets/encrypted-bucket/my-toolset"),
                eq(new BucketInfo("encrypted-bucket", "Users/owner/")),
                org.mockito.ArgumentMatchers.any());

        ResourceAuthSettings resolved = resolver.resolve(toolSet, context);

        assertNotSame(encrypted, resolved, "Resource-stored toolset must yield a decrypted copy, not the original");
        assertEquals("plaintext-secret", resolved.getClientSecret());
    }

    @Test
    void resolve_resourceStoredToolset_doesNotMutateOriginalAuthSettings() {
        String url = "toolsets/encrypted-bucket/my-toolset";
        ToolSet toolSet = toolSet(url, "ciphertext-secret");
        ResourceAuthSettings encrypted = toolSet.getAuthSettings();
        when(context.getConfig()).thenReturn(config);
        when(config.isDeploymentExists(url)).thenReturn(false);
        when(encryptionService.decrypt("encrypted-bucket")).thenReturn("Users/owner/");
        doAnswer(invocation -> {
            ResourceAuthSettings target = invocation.getArgument(2);
            target.setClientSecret("plaintext-secret");
            return null;
        }).when(authSettingsEncryptionService).decrypt(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());

        resolver.resolve(toolSet, context);

        // The toolset held in ProxyContext must keep its ciphertext for the rest of the request,
        // so a heap dump or accidental log of the deployment never exposes the plaintext secret.
        assertEquals("ciphertext-secret", encrypted.getClientSecret());
        assertSame(encrypted, toolSet.getAuthSettings());
    }

    @Test
    void resolve_resourceStoredToolsetWithEncodedName_doesNotDoubleEncode() {
        // toolSet.getName() is set to ResourceDescriptor.getUrl() at PUT time, so it is already
        // percent-encoded. The decrypt call must use the SAME URL string the encrypt call used
        // (it is the AAD); otherwise AES-GCM rejects the decryption. Re-encoding here would
        // turn "my%20toolset" into "my%2520toolset" and break decryption silently for any
        // toolset whose name contains percent-encoded characters.
        String url = "toolsets/encrypted-bucket/my%20toolset";
        ToolSet toolSet = toolSet(url, "ciphertext-secret");
        when(context.getConfig()).thenReturn(config);
        when(config.isDeploymentExists(url)).thenReturn(false);
        when(encryptionService.decrypt("encrypted-bucket")).thenReturn("Users/owner/");
        doAnswer(invocation -> {
            ResourceAuthSettings target = invocation.getArgument(2);
            target.setClientSecret("plaintext-secret");
            return null;
        }).when(authSettingsEncryptionService).decrypt(
                eq("toolsets/encrypted-bucket/my%20toolset"),
                eq(new BucketInfo("encrypted-bucket", "Users/owner/")),
                org.mockito.ArgumentMatchers.any());

        ResourceAuthSettings resolved = resolver.resolve(toolSet, context);

        assertEquals("plaintext-secret", resolved.getClientSecret());
    }

    private static ToolSet toolSet(String name, String clientSecret) {
        ToolSet toolSet = new ToolSet();
        toolSet.setName(name);
        toolSet.setAuthSettings(ResourceAuthSettings.builder()
                .authenticationType(AuthenticationType.OAUTH)
                .clientId("client-id")
                .clientSecret(clientSecret)
                .build());
        return toolSet;
    }
}
