package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.SecuredResource;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;
import com.epam.aidial.core.credentials.data.credentials.ResourceSignInRequest;
import com.epam.aidial.core.credentials.data.credentials.ResourceSignOutRequest;
import com.epam.aidial.core.credentials.service.ResourceCredentialsService;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.mcp.McpHttpClientBuilder;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SecuredResourceServiceTest {

    private static final String RESOURCE_URL = "toolsets/encrypted-user-bucket/toolset-1";

    @Mock
    private ProxyContext context;
    @Mock
    private Proxy proxy;
    @Mock
    private EncryptionService encryptionService;
    @Mock
    private ResourceCredentialsService resourceCredentialsService;

    private McpHttpClientBuilder mcpHttpClientBuilder;
    private SecuredResourceService service;

    @BeforeEach
    void setup() {
        McpHttpClientBuilder.Settings settings = new McpHttpClientBuilder.Settings();
        settings.setConnectTimeout(1000);
        mcpHttpClientBuilder = new McpHttpClientBuilder(settings);
        service = new SecuredResourceService(resourceCredentialsService, mcpHttpClientBuilder);
    }

    @AfterEach
    void tearDown() {
        mcpHttpClientBuilder.close();
    }

    @Test
    void testSignIn_StoresCredentials() {
        mockLocatorPlumbing();
        ToolSet toolSet = createToolSet(null);
        ResourceSignInRequest request = signInRequest(AuthenticationType.OAUTH, null);

        service.signIn(context, toolSet, request);

        verify(resourceCredentialsService).addResourceCredentials(any(), eq(toolSet.getAuthSettings()), eq(request), eq("userSub"));
    }

    @Test
    void testSignIn_BlankApiKeyRejected() {
        ResourceSignInRequest request = signInRequest(AuthenticationType.API_KEY, " ");

        HttpException error = assertThrows(HttpException.class,
                () -> service.signIn(context, createToolSet("http://localhost:1"), request));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        verifyNoInteractions(resourceCredentialsService);
    }

    @Test
    void testSignIn_ApiKeyWithControlCharactersRejected() {
        ResourceSignInRequest request = signInRequest(AuthenticationType.API_KEY, "key\nwith-newline");

        HttpException error = assertThrows(HttpException.class,
                () -> service.signIn(context, createToolSet("http://localhost:1"), request));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        verifyNoInteractions(resourceCredentialsService);
    }

    // No endpoint to probe -> validation is skipped, the key is stored (#1698 fail-open)
    @Test
    void testSignIn_ApiKeyWithoutEndpointStored() {
        mockLocatorPlumbing();
        ToolSet toolSet = createToolSet(null);
        ResourceSignInRequest request = signInRequest(AuthenticationType.API_KEY, "some-key");

        service.signIn(context, toolSet, request);

        verify(resourceCredentialsService).addResourceCredentials(any(), eq(toolSet.getAuthSettings()), eq(request), eq("userSub"));
    }

    @Test
    void testSignOut_DeletesCredentials() {
        mockLocatorPlumbing();
        ResourceSignOutRequest request = ResourceSignOutRequest.builder()
                .url(RESOURCE_URL)
                .credentialsLevel(CredentialsLevel.GLOBAL)
                .authenticationType(AuthenticationType.API_KEY)
                .build();
        when(resourceCredentialsService.deleteResourceCredentials(any(), eq(request), eq("userSub"))).thenReturn(true);

        assertTrue(service.signOut(context, createToolSet(null), request));
    }

    @Test
    void testSignIn_UnsupportedResourceTypeRejected() {
        SecuredResource unknownResource = new SecuredResource() {
        };
        ResourceSignInRequest request = signInRequest(AuthenticationType.OAUTH, null);

        assertThrows(IllegalArgumentException.class, () -> service.signIn(context, unknownResource, request));
        verifyNoInteractions(resourceCredentialsService);
    }

    // #1843: re-submitting the stored key must neither probe the endpoint nor rewrite the credentials
    @Test
    void testSignIn_UnchangedApiKeySkipsProbeAndStore() throws IOException {
        mockLocatorPlumbing();
        try (MockWebServer mcpServer = new MockWebServer()) {
            mcpServer.start();
            ToolSet toolSet = createToolSet(mcpServer.url("/mcp").toString());
            ResourceSignInRequest request = signInRequest(AuthenticationType.API_KEY, "same-key");
            when(resourceCredentialsService.getResourceCredentials(any()))
                    .thenReturn(storedApiKey("same-key", "Authorization", CredentialsLevel.GLOBAL));

            service.signIn(context, toolSet, request);

            assertEquals(0, mcpServer.getRequestCount());
            verify(resourceCredentialsService, never()).addResourceCredentials(any(), any(), any(), any());
        }
    }

    @Test
    void testSignIn_ChangedApiKeyStored() {
        mockLocatorPlumbing();
        ToolSet toolSet = createToolSet(null);
        ResourceSignInRequest request = signInRequest(AuthenticationType.API_KEY, "new-key");
        when(resourceCredentialsService.getResourceCredentials(any()))
                .thenReturn(storedApiKey("old-key", "Authorization", CredentialsLevel.GLOBAL));

        service.signIn(context, toolSet, request);

        verify(resourceCredentialsService).addResourceCredentials(any(), eq(toolSet.getAuthSettings()), eq(request), eq("userSub"));
    }

    // The header the key is sent under is part of the stored record, so a config change must be persisted
    @Test
    void testSignIn_SameApiKeyWithChangedHeaderStored() {
        mockLocatorPlumbing();
        ToolSet toolSet = createToolSet(null);
        ResourceSignInRequest request = signInRequest(AuthenticationType.API_KEY, "same-key");
        when(resourceCredentialsService.getResourceCredentials(any()))
                .thenReturn(storedApiKey("same-key", "X-Api-Key", CredentialsLevel.GLOBAL));

        service.signIn(context, toolSet, request);

        verify(resourceCredentialsService).addResourceCredentials(any(), eq(toolSet.getAuthSettings()), eq(request), eq("userSub"));
    }

    @Test
    void testSignIn_UserLevelUnchangedApiKeySkipped() {
        mockLocatorPlumbing();
        ResourceSignInRequest request = userSignInRequest("same-key", true);
        ResourceCredentials stored = storedApiKey("same-key", "Authorization", CredentialsLevel.USER);
        stored.setUserId("userSub");
        stored.setOfflineUsageConsent(true);
        when(resourceCredentialsService.getResourceCredentials(any())).thenReturn(stored);

        service.signIn(context, createToolSet(null), request);

        verify(resourceCredentialsService, never()).addResourceCredentials(any(), any(), any(), any());
    }

    // Offline-usage consent is recorded on the credentials, so flipping it must go through the store path
    @Test
    void testSignIn_UserLevelConsentChangeStored() {
        mockLocatorPlumbing();
        ToolSet toolSet = createToolSet(null);
        ResourceSignInRequest request = userSignInRequest("same-key", true);
        ResourceCredentials stored = storedApiKey("same-key", "Authorization", CredentialsLevel.USER);
        stored.setUserId("userSub");
        when(resourceCredentialsService.getResourceCredentials(any())).thenReturn(stored);

        service.signIn(context, toolSet, request);

        verify(resourceCredentialsService).addResourceCredentials(any(), eq(toolSet.getAuthSettings()), eq(request), eq("userSub"));
    }

    // An unreadable record must not fail sign-in: it is overwritten, as before the pre-check existed
    @Test
    void testSignIn_UnreadableStoredCredentialsStored() {
        mockLocatorPlumbing();
        ToolSet toolSet = createToolSet(null);
        ResourceSignInRequest request = signInRequest(AuthenticationType.API_KEY, "some-key");
        when(resourceCredentialsService.getResourceCredentials(any()))
                .thenThrow(new IllegalArgumentException("Provided payload do not match required schema"));

        service.signIn(context, toolSet, request);

        verify(resourceCredentialsService).addResourceCredentials(any(), eq(toolSet.getAuthSettings()), eq(request), eq("userSub"));
    }

    private static ResourceCredentials storedApiKey(String apiKey, String apiKeyHeader, CredentialsLevel credentialsLevel) {
        return ResourceCredentials.builder()
                .resourceId(RESOURCE_URL)
                .credentialsLevel(credentialsLevel)
                .authenticationType(AuthenticationType.API_KEY)
                .apiKeyHeader(apiKeyHeader)
                .apiKey(apiKey)
                .build();
    }

    private static ResourceSignInRequest userSignInRequest(String apiKey, boolean offlineUsageConsent) {
        return ResourceSignInRequest.builder()
                .url(RESOURCE_URL)
                .credentialsLevel(CredentialsLevel.USER)
                .authenticationType(AuthenticationType.API_KEY)
                .apiKey(apiKey)
                .offlineUsageConsent(offlineUsageConsent)
                .build();
    }

    private void mockLocatorPlumbing() {
        when(context.getProxy()).thenReturn(proxy);
        when(proxy.getEncryptionService()).thenReturn(encryptionService);
        when(context.getConfig()).thenReturn(mock(Config.class));
        when(encryptionService.decrypt("encrypted-user-bucket")).thenReturn("Users/userSub/");
        when(context.getApiKeyData()).thenReturn(mock(ApiKeyData.class));
        when(context.getUserId()).thenReturn("userSub");
        when(context.getInitiatorId()).thenReturn("userSub");
    }

    private static ToolSet createToolSet(String endpoint) {
        ToolSet toolSet = new ToolSet();
        toolSet.setEndpoint(endpoint);
        toolSet.setAuthSettings(ResourceAuthSettings.builder()
                .authenticationType(AuthenticationType.API_KEY)
                .apiKeyHeader("Authorization")
                .build());
        return toolSet;
    }

    private static ResourceSignInRequest signInRequest(AuthenticationType authenticationType, String apiKey) {
        return ResourceSignInRequest.builder()
                .url(RESOURCE_URL)
                .credentialsLevel(CredentialsLevel.GLOBAL)
                .authenticationType(authenticationType)
                .apiKey(apiKey)
                .build();
    }
}
