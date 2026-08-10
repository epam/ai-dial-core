package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.data.credentials.CredentialsDescriptor;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;
import com.epam.aidial.core.credentials.data.credentials.ResourceSignInRequest;
import com.epam.aidial.core.credentials.data.credentials.ResourceSignOutRequest;
import com.epam.aidial.core.credentials.data.credentials.TokenResponse;
import com.epam.aidial.core.credentials.encryption.CredentialEncryptionService;
import com.epam.aidial.core.credentials.exception.EncryptionException;
import com.epam.aidial.core.credentials.factory.ResourceCredentialsFactory;
import com.epam.aidial.core.credentials.factory.ResourceCredentialsFactoryProvider;
import com.epam.aidial.core.credentials.service.token.ApiKeyRefreshStrategy;
import com.epam.aidial.core.credentials.service.token.OauthTokenRefreshStrategy;
import com.epam.aidial.core.credentials.service.token.TokenRefreshStrategyFactory;
import com.epam.aidial.core.credentials.util.JsonMapperUtil;
import com.epam.aidial.core.credentials.util.TimeProvider;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResourceCredentialsServiceTest {

    private static final String TOOL_SET_NAME = "toolsets/toolset-bucket-name/folder1/my-toolset";
    private static final byte[] ENCRYPTED_BODY = "encrypted_body".getBytes();

    @Mock
    private ResourceService resourceService;
    @Mock
    private CredentialEncryptionService credentialEncryptionService;
    @Mock
    private ResourceCredentialsFactoryProvider resourceCredentialsFactoryProvider;
    @Mock
    private TokenRefreshStrategyFactory tokenRefreshStrategyFactory;
    @Mock
    private OauthTokenRefreshStrategy oauthTokenRefreshStrategy;
    @Mock
    private ApiKeyRefreshStrategy apiKeyRefreshStrategy;
    @Mock
    private TokenService tokenService;
    @Mock
    private TimeProvider timeProvider;

    @InjectMocks
    private ResourceCredentialsService service;

    @Test
    void testAddResourceCredentials_putsResource() {
        // Given
        ResourceCredentials creds = createCredentials(CredentialsLevel.USER);
        CredentialsDescriptor descriptor = createCredentialsDescriptor();
        ResourceAuthSettings resourceAuthSettings = ResourceAuthSettings.builder().build();
        ResourceCredentialsFactory resourceCredentialsFactory = mock(ResourceCredentialsFactory.class);

        ResourceSignInRequest resourceSignInRequest = mock(ResourceSignInRequest.class);
        when(resourceSignInRequest.getAuthenticationType()).thenReturn(AuthenticationType.OAUTH);
        when(resourceSignInRequest.getCredentialsLevel()).thenReturn(CredentialsLevel.USER);

        when(credentialEncryptionService.encrypt(any(), any(), any())).thenReturn(ENCRYPTED_BODY);
        when(resourceCredentialsFactoryProvider.getFactory(AuthenticationType.OAUTH)).thenReturn(resourceCredentialsFactory);
        when(resourceCredentialsFactory.createCredentials(
                resourceSignInRequest.getUrl(),
                resourceAuthSettings,
                resourceSignInRequest))
                .thenReturn(creds);

        // When
        service.addResourceCredentials(descriptor, resourceAuthSettings, resourceSignInRequest, "userSub");

        // Then
        ArgumentCaptor<ResourceDescriptor> descriptorCaptor = ArgumentCaptor.forClass(ResourceDescriptor.class);
        ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<EtagHeader> etagCaptor = ArgumentCaptor.forClass(EtagHeader.class);

        verify(resourceService).putResourceBytes(descriptorCaptor.capture(), bodyCaptor.capture(), etagCaptor.capture());

        ResourceDescriptor passed = descriptorCaptor.getValue();
        Assertions.assertEquals(ResourceTypes.CREDENTIALS, passed.getType());
        assertEquals("my-toolset", passed.getName());
        assertEquals(List.of("toolsets", "toolset-bucket-name", "folder1"), passed.getParentFolders());
        assertEquals("bucket-name", passed.getBucketName());
        assertEquals("bucket-location/", passed.getBucketLocation());

        assertEquals(EtagHeader.ANY, etagCaptor.getValue());

        byte[] actualBody = bodyCaptor.getValue();
        assertEquals(ENCRYPTED_BODY, actualBody);
    }

    @Test
    void testGetAllResourceCredentials_returnsOne() {
        // Given
        ResourceCredentials creds = createCredentials(CredentialsLevel.USER);
        byte[] body = JsonMapperUtil.convertToString(creds).getBytes(StandardCharsets.UTF_8);
        CredentialsLocator credentialsLocator = createCredentialsLocator();

        when(resourceService.getResourceBytes(any(ResourceDescriptor.class))).thenReturn(ENCRYPTED_BODY);
        when(credentialEncryptionService.decrypt(any(), any(), any())).thenReturn(body);

        // When
        List<ResourceCredentials> list = service.getAllResourceCredentials(credentialsLocator);

        // Then
        assertNotNull(list);
        assertEquals(1, list.size());
        ResourceCredentials c = list.getFirst();
        assertEquals(CredentialsLevel.USER, c.getCredentialsLevel());
        assertEquals(TOOL_SET_NAME, c.getResourceId());

        ArgumentCaptor<ResourceDescriptor> descriptorCaptor = ArgumentCaptor.forClass(ResourceDescriptor.class);
        verify(resourceService).getResourceBytes(descriptorCaptor.capture());

        ResourceDescriptor passed = descriptorCaptor.getValue();
        Assertions.assertEquals(ResourceTypes.CREDENTIALS, passed.getType());
        assertEquals("my-toolset", passed.getName());
        assertEquals(List.of("toolsets", "toolset-bucket-name", "folder1"), passed.getParentFolders());
        assertEquals("bucket-name", passed.getBucketName());
        assertEquals("bucket-location/", passed.getBucketLocation());
    }

    @Test
    void testGetAllResourceCredentials_returnsEmptyWhenNull() {
        CredentialsLocator credentialsLocator = createCredentialsLocator();
        when(resourceService.getResourceBytes(any(ResourceDescriptor.class))).thenReturn(null);

        List<ResourceCredentials> list = service.getAllResourceCredentials(credentialsLocator);

        assertNotNull(list);
        assertTrue(list.isEmpty());
    }

    @Test
    void testGetAllResourceCredentials_handlesSecurityException() {
        // Given
        CredentialsLocator credentialsLocator = Mockito.mock(CredentialsLocator.class);
        CredentialsDescriptor credentialsDescriptor = Mockito.mock(CredentialsDescriptor.class);
        when(credentialsDescriptor.getFullPath()).thenReturn("path");
        ResourceCredentials creds = createCredentials(CredentialsLevel.USER);

        ResourceDescriptor credentialsResourceDescriptor = Mockito.mock(ResourceDescriptor.class);
        byte[] body = JsonMapperUtil.convertToString(creds).getBytes(StandardCharsets.UTF_8);
        when(resourceService.getResourceBytes(credentialsResourceDescriptor)).thenReturn(body);

        when(credentialsDescriptor.toResourceDescriptor()).thenReturn(credentialsResourceDescriptor);
        when(credentialsLocator.getUniqueCredentialsDescriptors()).thenReturn(List.of(credentialsDescriptor));

        Mockito.doThrow(new EncryptionException("Decryption failed: authentication tag mismatch (AAD, key, or ciphertext wrong)"))
                .when(credentialEncryptionService)
                .decrypt(any(), any(), any());

        // When
        List<ResourceCredentials> result = service.getAllResourceCredentials(credentialsLocator);

        // Then
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void getResourceCredentials() {
        // Given
        ResourceCredentials creds = createCredentials(CredentialsLevel.USER);
        byte[] body = JsonMapperUtil.convertToString(creds).getBytes(StandardCharsets.UTF_8);
        CredentialsDescriptor credentialsDescriptor = createCredentialsDescriptor();

        when(resourceService.getResourceBytes(any(ResourceDescriptor.class))).thenReturn(ENCRYPTED_BODY);
        when(credentialEncryptionService.decrypt(any(), any(), any())).thenReturn(body);

        // When
        ResourceCredentials resourceCredentials = service.getResourceCredentials(credentialsDescriptor);

        // Then
        assertNotNull(resourceCredentials);
        assertEquals(CredentialsLevel.USER, resourceCredentials.getCredentialsLevel());
        assertEquals(TOOL_SET_NAME, resourceCredentials.getResourceId());

        ArgumentCaptor<ResourceDescriptor> descriptorCaptor = ArgumentCaptor.forClass(ResourceDescriptor.class);
        verify(resourceService).getResourceBytes(descriptorCaptor.capture());

        ResourceDescriptor passed = descriptorCaptor.getValue();
        Assertions.assertEquals(ResourceTypes.CREDENTIALS, passed.getType());
        assertEquals("my-toolset", passed.getName());
        assertEquals(List.of("toolsets", "toolset-bucket-name", "folder1"), passed.getParentFolders());
        assertEquals("bucket-name", passed.getBucketName());
        assertEquals("bucket-location/", passed.getBucketLocation());
    }

    @Test
    void testDeleteResourceCredentials_Success() {
        // Given
        CredentialsLocator credentialsLocator = Mockito.mock(CredentialsLocator.class);
        ResourceSignOutRequest resourceSignOutRequest = Mockito.mock(ResourceSignOutRequest.class);

        CredentialsDescriptor credentialsDescriptor = Mockito.mock(CredentialsDescriptor.class);
        when(resourceSignOutRequest.getCredentialsLevel()).thenReturn(CredentialsLevel.USER);
        Map<CredentialsLevel, CredentialsDescriptor> descriptors = new EnumMap<>(CredentialsLevel.class);
        descriptors.put(CredentialsLevel.USER, credentialsDescriptor);
        when(credentialsLocator.getCredentialsDescriptors()).thenReturn(descriptors);

        ResourceCredentials resourceCredentials = createCredentials(CredentialsLevel.USER);
        resourceCredentials.setUserId("userSub");
        byte[] resourceCredentialsBytes = JsonMapperUtil.convertToString(resourceCredentials).getBytes();

        ResourceDescriptor resourceDescriptor = Mockito.mock(ResourceDescriptor.class);
        when(credentialsDescriptor.toResourceDescriptor()).thenReturn(resourceDescriptor);
        when(credentialsDescriptor.getFullPath()).thenReturn("path");
        when(credentialEncryptionService.decrypt(any(), any(), any())).thenReturn(resourceCredentialsBytes);

        doAnswer(invocation -> {
            Function<byte[], byte[]> function = invocation.getArgument(1);
            byte[] encryptedBytes = "mockEncryptedData".getBytes();
            function.apply(encryptedBytes);
            return null;
        }).when(resourceService).computeResourceBytes(any(), any());

        // When
        boolean result = service.deleteResourceCredentials(credentialsLocator, resourceSignOutRequest, "userSub");

        // Then
        assertTrue(result);
        verify(resourceService).computeResourceBytes(eq(resourceDescriptor), any());
    }

    @Test
    void testDeleteResourceCredentials_ValidationFailed() {
        // Given
        CredentialsLocator credentialsLocator = Mockito.mock(CredentialsLocator.class);
        ResourceSignOutRequest resourceSignOutRequest = Mockito.mock(ResourceSignOutRequest.class);

        CredentialsDescriptor credentialsDescriptor = Mockito.mock(CredentialsDescriptor.class);
        when(resourceSignOutRequest.getCredentialsLevel()).thenReturn(CredentialsLevel.USER);
        Map<CredentialsLevel, CredentialsDescriptor> descriptors = new EnumMap<>(CredentialsLevel.class);
        descriptors.put(CredentialsLevel.USER, credentialsDescriptor);
        when(credentialsLocator.getCredentialsDescriptors()).thenReturn(descriptors);

        ResourceCredentials resourceCredentials = createCredentials(CredentialsLevel.USER);
        resourceCredentials.setUserId("userSub-1");
        byte[] resourceCredentialsBytes = JsonMapperUtil.convertToString(resourceCredentials).getBytes();

        ResourceDescriptor resourceDescriptor = Mockito.mock(ResourceDescriptor.class);
        when(credentialsDescriptor.toResourceDescriptor()).thenReturn(resourceDescriptor);
        when(credentialsDescriptor.getFullPath()).thenReturn("path");
        when(credentialEncryptionService.decrypt(any(), any(), any())).thenReturn(resourceCredentialsBytes);

        doAnswer(invocation -> {
            Function<byte[], byte[]> function = invocation.getArgument(1);
            byte[] encryptedBytes = "mockEncryptedData".getBytes();
            function.apply(encryptedBytes);
            return null;
        }).when(resourceService).computeResourceBytes(any(), any());

        // When & Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.deleteResourceCredentials(credentialsLocator, resourceSignOutRequest, "userSub-2")
        );
        assertEquals("Can't delete other user's personal credentials", exception.getMessage());
    }

    @Test
    void testGetRefreshedResourceCredentials_SuccessForNoneAuth() {
        // Given
        CredentialsLocator credentialsLocator = Mockito.mock(CredentialsLocator.class);
        ResourceAuthSettings authSettings = Mockito.mock(ResourceAuthSettings.class);
        when(authSettings.getAuthenticationType()).thenReturn(AuthenticationType.NONE);

        // When
        ResourceCredentials result = service.getRefreshedResourceCredentials(credentialsLocator, authSettings, "userSub");

        // Then
        Assertions.assertNull(result);
    }

    @Test
    void testGetRefreshedResourceCredentials_SuccessWithoutTokenRefresh() {
        // Given
        CredentialsLocator credentialsLocator = Mockito.mock(CredentialsLocator.class);
        CredentialsDescriptor credentialsDescriptor = Mockito.mock(CredentialsDescriptor.class);
        Map<CredentialsLevel, CredentialsDescriptor> descriptors = new EnumMap<>(CredentialsLevel.class);
        descriptors.put(CredentialsLevel.USER, credentialsDescriptor);
        when(credentialsLocator.getCredentialsDescriptors()).thenReturn(descriptors);

        ResourceAuthSettings authSettings = Mockito.mock(ResourceAuthSettings.class);
        when(authSettings.getAuthenticationType()).thenReturn(AuthenticationType.API_KEY);

        Mockito.when(credentialsDescriptor.getResourceId()).thenReturn("testResourceId");
        Mockito.when(credentialsDescriptor.getBucketName()).thenReturn("testBucket");
        Mockito.when(credentialsDescriptor.toResourceDescriptor()).thenReturn(Mockito.mock(ResourceDescriptor.class));

        byte[] encryptedBytes = "mockEncryptedData".getBytes(StandardCharsets.UTF_8);
        byte[] decryptedBytes = """
                {
                    "resourceId": "testResourceId",
                    "credentialsLevel": "USER",
                    "userSub": "userSub",
                    "authenticationType": "API_KEY"
                }
                """.getBytes(StandardCharsets.UTF_8);

        ResourceDescriptor resourceDescriptor = Mockito.mock(ResourceDescriptor.class);
        when(credentialsDescriptor.toResourceDescriptor()).thenReturn(resourceDescriptor);
        when(credentialsDescriptor.getFullPath()).thenReturn("path");

        Mockito.when(credentialEncryptionService.decrypt(any(), eq(encryptedBytes), any())).thenReturn(decryptedBytes);
        Mockito.doAnswer(invocation -> {
            Function<byte[], byte[]> callbackFunction = invocation.getArgument(1);
            callbackFunction.apply(encryptedBytes);
            return new ResourceItemMetadata();
        }).when(resourceService).computeResourceBytes(any(), any());

        when(tokenRefreshStrategyFactory.getTokenValidatorStrategy(AuthenticationType.API_KEY))
                .thenReturn(apiKeyRefreshStrategy);

        // When
        ResourceCredentials result = service.getRefreshedResourceCredentials(credentialsLocator, authSettings, "userSub");

        // Then
        Assertions.assertNotNull(result);
        Assertions.assertEquals(AuthenticationType.API_KEY, result.getAuthenticationType());
    }

    @Test
    void testGetAndRefreshCredentials_SuccessWithTokenRefresh() {
        // Given
        CredentialsLocator credentialsLocator = Mockito.mock(CredentialsLocator.class);
        CredentialsDescriptor credentialsDescriptor = Mockito.mock(CredentialsDescriptor.class);
        Map<CredentialsLevel, CredentialsDescriptor> descriptors = new EnumMap<>(CredentialsLevel.class);
        descriptors.put(CredentialsLevel.USER, credentialsDescriptor);
        when(credentialsLocator.getCredentialsDescriptors()).thenReturn(descriptors);
        ResourceAuthSettings authSettings = Mockito.mock(ResourceAuthSettings.class);
        when(authSettings.getAuthenticationType()).thenReturn(AuthenticationType.OAUTH);

        Mockito.when(credentialsDescriptor.getResourceId()).thenReturn("testResourceId");
        Mockito.when(credentialsDescriptor.getBucketName()).thenReturn("testBucket");
        Mockito.when(credentialsDescriptor.toResourceDescriptor()).thenReturn(Mockito.mock(ResourceDescriptor.class));

        byte[] encryptedBytes = "mockEncryptedData".getBytes(StandardCharsets.UTF_8);
        byte[] decryptedBytes = """
                {
                    "resourceId": "testResourceId",
                    "credentialsLevel": "USER",
                    "userSub": "userSub",
                    "authenticationType": "OAUTH",
                    "accessToken": "expiredAccessToken",
                    "refreshToken": "refreshTokenValue",
                    "updatedAt": "1",
                    "expiresInSeconds": "100"
                }
                """.getBytes(StandardCharsets.UTF_8);

        ResourceDescriptor resourceDescriptor = Mockito.mock(ResourceDescriptor.class);
        when(credentialsDescriptor.toResourceDescriptor()).thenReturn(resourceDescriptor);
        when(credentialsDescriptor.getFullPath()).thenReturn("path");

        Mockito.when(credentialEncryptionService.decrypt(any(), eq(encryptedBytes), any())).thenReturn(decryptedBytes);
        ResourceCredentials decryptedResourceCredentials = JsonMapperUtil.convertToObject(decryptedBytes, ResourceCredentials.class);
        when(tokenRefreshStrategyFactory.getTokenValidatorStrategy(AuthenticationType.OAUTH))
                .thenReturn(oauthTokenRefreshStrategy);
        when(oauthTokenRefreshStrategy.requiresTokenRefresh(decryptedResourceCredentials)).thenReturn(true);

        TokenResponse mockedTokenResponse = TokenResponse.builder().accessToken("newAccessToken").refreshToken("newRefreshToken").expiresIn(3600L).build();
        Mockito.when(tokenService.getToken("testResourceId", authSettings, "refreshTokenValue"))
                .thenReturn(mockedTokenResponse);
        Mockito.doAnswer(invocation -> {
            Function<byte[], byte[]> callbackFunction = invocation.getArgument(1);
            callbackFunction.apply(encryptedBytes);
            return new ResourceItemMetadata();
        }).when(resourceService).computeResourceBytes(any(), any());

        // When
        ResourceCredentials result = service.getRefreshedResourceCredentials(credentialsLocator, authSettings, "userSub");

        // Then
        Assertions.assertNotNull(result);
        Assertions.assertEquals("newAccessToken", result.getAccessToken());
        Assertions.assertEquals("newRefreshToken", result.getRefreshToken());
        Mockito.verify(tokenService).getToken("testResourceId", authSettings, "refreshTokenValue");
    }

    @Test
    void testGetAndRefreshCredentials_ResponseWithoutRefreshTokenKeepsTheExistingOne() {
        // RFC 6749 §6: a refresh response that omits refresh_token leaves the existing one in force. Providers
        // that do not rotate return exactly this, and dropping it would end offline access permanently.
        CredentialsLocator credentialsLocator = Mockito.mock(CredentialsLocator.class);
        CredentialsDescriptor credentialsDescriptor = Mockito.mock(CredentialsDescriptor.class);
        Map<CredentialsLevel, CredentialsDescriptor> descriptors = new EnumMap<>(CredentialsLevel.class);
        descriptors.put(CredentialsLevel.USER, credentialsDescriptor);
        when(credentialsLocator.getCredentialsDescriptors()).thenReturn(descriptors);
        ResourceAuthSettings authSettings = Mockito.mock(ResourceAuthSettings.class);
        when(authSettings.getAuthenticationType()).thenReturn(AuthenticationType.OAUTH);

        Mockito.when(credentialsDescriptor.getResourceId()).thenReturn("testResourceId");
        Mockito.when(credentialsDescriptor.getBucketName()).thenReturn("testBucket");
        Mockito.when(credentialsDescriptor.getFullPath()).thenReturn("path");
        Mockito.when(credentialsDescriptor.toResourceDescriptor()).thenReturn(Mockito.mock(ResourceDescriptor.class));

        byte[] encryptedBytes = "mockEncryptedData".getBytes(StandardCharsets.UTF_8);
        byte[] decryptedBytes = """
                {
                    "resourceId": "testResourceId",
                    "credentialsLevel": "USER",
                    "userSub": "userSub",
                    "authenticationType": "OAUTH",
                    "accessToken": "expiredAccessToken",
                    "refreshToken": "longLivedRefreshToken",
                    "updatedAt": "1",
                    "expiresInSeconds": "100"
                }
                """.getBytes(StandardCharsets.UTF_8);

        Mockito.when(credentialEncryptionService.decrypt(any(), eq(encryptedBytes), any())).thenReturn(decryptedBytes);
        ResourceCredentials decryptedResourceCredentials = JsonMapperUtil.convertToObject(decryptedBytes, ResourceCredentials.class);
        when(tokenRefreshStrategyFactory.getTokenValidatorStrategy(AuthenticationType.OAUTH))
                .thenReturn(oauthTokenRefreshStrategy);
        when(oauthTokenRefreshStrategy.requiresTokenRefresh(decryptedResourceCredentials)).thenReturn(true);

        Mockito.when(tokenService.getToken("testResourceId", authSettings, "longLivedRefreshToken"))
                .thenReturn(TokenResponse.builder().accessToken("newAccessToken").expiresIn(3600L).build());
        Mockito.doAnswer(invocation -> {
            Function<byte[], byte[]> callbackFunction = invocation.getArgument(1);
            callbackFunction.apply(encryptedBytes);
            return new ResourceItemMetadata();
        }).when(resourceService).computeResourceBytes(any(), any());

        ResourceCredentials result = service.getRefreshedResourceCredentials(credentialsLocator, authSettings, "userSub");

        Assertions.assertNotNull(result);
        Assertions.assertEquals("newAccessToken", result.getAccessToken());
        Assertions.assertEquals("longLivedRefreshToken", result.getRefreshToken());
    }

    @Test
    void testGetAndRefreshCredentials_RefreshFails_InvalidatesRefreshToken() {
        // Given
        CredentialsLocator credentialsLocator = Mockito.mock(CredentialsLocator.class);
        CredentialsDescriptor userCredentialsDescriptor = Mockito.mock(CredentialsDescriptor.class);
        CredentialsDescriptor globalCredentialsDescriptor = Mockito.mock(CredentialsDescriptor.class);
        Map<CredentialsLevel, CredentialsDescriptor> descriptors = new EnumMap<>(CredentialsLevel.class);
        descriptors.put(CredentialsLevel.USER, userCredentialsDescriptor);
        descriptors.put(CredentialsLevel.GLOBAL, globalCredentialsDescriptor);
        when(credentialsLocator.getCredentialsDescriptors()).thenReturn(descriptors);
        ResourceAuthSettings authSettings = Mockito.mock(ResourceAuthSettings.class);
        when(authSettings.getAuthenticationType()).thenReturn(AuthenticationType.OAUTH);

        Mockito.when(userCredentialsDescriptor.getResourceId()).thenReturn("testResourceId");
        Mockito.when(userCredentialsDescriptor.getBucketName()).thenReturn("testBucket");

        byte[] encryptedBytes = "mockEncryptedData".getBytes(StandardCharsets.UTF_8);
        byte[] decryptedBytes = """
                {
                    "resourceId": "testResourceId",
                    "credentialsLevel": "USER",
                    "userSub": "userSub",
                    "authenticationType": "OAUTH",
                    "accessToken": "expiredAccessToken",
                    "refreshToken": "expiredRefreshToken",
                    "updatedAt": "1",
                    "expiresInSeconds": "100"
                }
                """.getBytes(StandardCharsets.UTF_8);

        ResourceDescriptor userResourceDescriptor = Mockito.mock(ResourceDescriptor.class);
        when(userCredentialsDescriptor.toResourceDescriptor()).thenReturn(userResourceDescriptor);
        when(userCredentialsDescriptor.getFullPath()).thenReturn("path");

        Mockito.when(credentialEncryptionService.decrypt(any(), eq(encryptedBytes), any())).thenReturn(decryptedBytes);
        ResourceCredentials decryptedResourceCredentials = JsonMapperUtil.convertToObject(decryptedBytes, ResourceCredentials.class);
        when(tokenRefreshStrategyFactory.getTokenValidatorStrategy(AuthenticationType.OAUTH))
                .thenReturn(oauthTokenRefreshStrategy);
        when(oauthTokenRefreshStrategy.requiresTokenRefresh(decryptedResourceCredentials)).thenReturn(true);

        // Simulate refresh token expired - auth server returns 400 with invalid_grant
        String errorBody = """
                {"error": "invalid_grant", "error_description": "Token has been expired or revoked."}
                """;
        Mockito.when(tokenService.getToken("testResourceId", authSettings, "expiredRefreshToken"))
                .thenThrow(new HttpException(HttpStatus.BAD_REQUEST, "Authorization server returns error code",
                        Map.of(), errorBody));

        Mockito.doAnswer(invocation -> {
            Function<byte[], byte[]> callbackFunction = invocation.getArgument(1);
            byte[] result = callbackFunction.apply(encryptedBytes);
            // Lambda should return null to trigger credential deletion
            Assertions.assertNull(result);
            return new ResourceItemMetadata();
        }).when(resourceService).computeResourceBytes(eq(userResourceDescriptor), any());

        // When & Then - should throw 401 because refresh failed; GLOBAL fallback is skipped.
        // 401 (not 404) tells the client the toolset exists but its OAuth state needs refreshing.
        HttpException ex = assertThrows(HttpException.class, () -> {
            service.getRefreshedResourceCredentials(credentialsLocator, authSettings, "userSub");
        });
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());

        // Verify token service was called with the expired refresh token
        Mockito.verify(tokenService).getToken("testResourceId", authSettings, "expiredRefreshToken");
    }

    @Test
    void testGetAndRefreshCredentials_CredentialsNotFound() {
        // Given
        CredentialsDescriptor globalCredentialsDescriptor = Mockito.mock(CredentialsDescriptor.class);
        Mockito.when(globalCredentialsDescriptor.getResourceId()).thenReturn("testResourceId");
        Mockito.when(globalCredentialsDescriptor.toResourceDescriptor()).thenReturn(Mockito.mock(ResourceDescriptor.class));

        CredentialsDescriptor userCredentialsDescriptor = Mockito.mock(CredentialsDescriptor.class);
        Mockito.when(userCredentialsDescriptor.getResourceId()).thenReturn("testResourceId");
        Mockito.when(userCredentialsDescriptor.toResourceDescriptor()).thenReturn(Mockito.mock(ResourceDescriptor.class));

        Map<CredentialsLevel, CredentialsDescriptor> descriptors = new EnumMap<>(CredentialsLevel.class);
        descriptors.put(CredentialsLevel.USER, userCredentialsDescriptor);
        descriptors.put(CredentialsLevel.GLOBAL, globalCredentialsDescriptor);

        CredentialsLocator credentialsLocator = Mockito.mock(CredentialsLocator.class);
        when(credentialsLocator.getCredentialsDescriptors()).thenReturn(descriptors);
        ResourceAuthSettings authSettings = Mockito.mock(ResourceAuthSettings.class);

        Mockito.doAnswer(invocation -> {
            Function<byte[], byte[]> callbackFunction = invocation.getArgument(1);
            return callbackFunction.apply(null);
        }).when(resourceService).computeResourceBytes(any(), any());

        // When & Then
        ResourceCredentials result = service.getRefreshedResourceCredentials(credentialsLocator, authSettings, "userSub");
        Assertions.assertNull(result);
    }

    private CredentialsDescriptor createCredentialsDescriptor() {
        return new CredentialsDescriptor(TOOL_SET_NAME, "bucket-name", "bucket-location/");
    }

    private CredentialsLocator createCredentialsLocator() {
        return new CredentialsLocator(TOOL_SET_NAME, Map.of(
                CredentialsLevel.USER, new BucketInfo("bucket-name", "bucket-location/")
        ));
    }

    private ResourceCredentials createCredentials(CredentialsLevel credentialsLevel) {
        return ResourceCredentials.builder()
                .credentialsLevel(credentialsLevel)
                .resourceId(TOOL_SET_NAME)
                .build();
    }

}
