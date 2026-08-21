package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.SecuredResource;
import com.epam.aidial.core.config.ToolSet;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
