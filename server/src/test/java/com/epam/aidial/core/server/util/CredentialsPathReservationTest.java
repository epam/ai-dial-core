package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reserved {@code offline} credentials id must stay unreachable from the app-scoped credential endpoints, no
 * matter how an application, toolset or external service is named.
 */
public class CredentialsPathReservationTest {

    @Test
    void offlineIdIsRejectedAsExternalServiceScope() {
        // Both credential endpoints reach their blobs through this parser, so anything it rejects is unaddressable.
        assertThrows(IllegalArgumentException.class,
                () -> CredentialsLocatorFactory.parseExternalServiceScope(CredentialsDescriptorFactory.OFFLINE_CREDENTIALS_ID));
        assertThrows(IllegalArgumentException.class,
                () -> CredentialsLocatorFactory.parseExternalServiceScope("applications/offline"));
        assertThrows(IllegalArgumentException.class,
                () -> CredentialsLocatorFactory.parseExternalServiceScope("offline/external_services/offline"));
    }

    @Test
    void serviceNamedOfflineResolvesElsewhere() {
        String[] parts = CredentialsLocatorFactory.parseExternalServiceScope(
                "applications/my-app/external_services/offline");

        assertEquals("my-app", parts[0]);
        assertEquals("offline", parts[1]);
        // The storage id is the normalized scope, never the bare service name.
        assertNotEqualsReservedId("applications/config/my-app/external_services/offline");
    }

    @Test
    void appNamedOfflineResolvesElsewhere() {
        String[] parts = CredentialsLocatorFactory.parseExternalServiceScope(
                "applications/offline/external_services/dial");

        assertEquals("offline", parts[0]);
        assertEquals("dial", parts[1]);
        assertNotEqualsReservedId("applications/config/offline/external_services/dial");
    }

    @Test
    void everyAppScopedIdIsPathShaped() {
        // The reservation rests on this: app- and toolset-scoped ids always carry a type prefix and therefore a
        // separator, while the offline id carries none.
        assertFalse(CredentialsDescriptorFactory.OFFLINE_CREDENTIALS_ID.contains("/"),
                "the reserved id must stay separator-free");
        assertTrue("applications/config/my-app/external_services/dial".contains("/"));
    }

    @Test
    void toolsetNamedOfflineResolvesElsewhere() {
        // Derived from the real locator, not a literal: a config toolset named "offline" must land on the
        // type-prefixed storage id, never on the reserved one.
        ProxyContext proxyContext = Mockito.mock(ProxyContext.class);
        Proxy proxy = Mockito.mock(Proxy.class);
        Config config = Mockito.mock(Config.class);
        EncryptionService encryptionService = Mockito.mock(EncryptionService.class);
        Mockito.when(proxyContext.getProxy()).thenReturn(proxy);
        Mockito.when(proxy.getEncryptionService()).thenReturn(encryptionService);
        Mockito.when(proxyContext.getApiKeyData()).thenReturn(Mockito.mock(ApiKeyData.class));
        Mockito.when(proxyContext.getConfig()).thenReturn(config);
        Mockito.when(config.isDeploymentExists("offline")).thenReturn(true);
        Mockito.when(proxyContext.getUserId()).thenReturn("userSub");
        Mockito.when(encryptionService.encrypt(Mockito.any())).thenReturn("userBucket");

        CredentialsLocator locator = CredentialsLocatorFactory.fromAnyUrl("offline", proxyContext, ResourceTypes.TOOL_SET);

        assertEquals("toolsets/config/offline", locator.getResourceId());
        assertNotEqualsReservedId(locator.getResourceId());
    }

    private static void assertNotEqualsReservedId(String storageId) {
        assertFalse(storageId.equals(CredentialsDescriptorFactory.OFFLINE_CREDENTIALS_ID),
                "app-scoped credentials must never land on the reserved offline id");
    }
}
