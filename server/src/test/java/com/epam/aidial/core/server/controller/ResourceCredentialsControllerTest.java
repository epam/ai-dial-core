package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.credentials.exception.EncryptionException;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsEncryptionService;
import com.epam.aidial.core.credentials.service.ResourceCredentialsService;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.DeploymentService;
import com.epam.aidial.core.server.service.ToolSetService;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResourceCredentialsControllerTest {

    @Mock
    private ProxyContext context;
    @Mock
    private AsyncTaskExecutor taskExecutor;
    @Mock
    private ResourceCredentialsService resourceCredentialsService;
    @Mock
    private AccessService accessService;
    @Mock
    private EncryptionService encryptionService;
    @Mock
    private DeploymentService deploymentService;
    @Mock
    private Proxy proxy;
    @Mock
    private HttpServerRequest request;
    @Mock
    private ResourceAuthSettingsEncryptionService resourceAuthSettingsEncryptionService;

    private ResourceCredentialsController controller;

    @BeforeEach
    void setup() {
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor);
        when(proxy.getResourceCredentialsService()).thenReturn(resourceCredentialsService);
        when(proxy.getAccessService()).thenReturn(accessService);
        when(proxy.getEncryptionService()).thenReturn(encryptionService);
        when(proxy.getDeploymentService()).thenReturn(deploymentService);
        when(proxy.getResourceAuthSettingsEncryptionService()).thenReturn(resourceAuthSettingsEncryptionService);
        when(proxy.getToolSetService()).thenReturn(new ToolSetService(null, null, null, resourceCredentialsService));

        doAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            try {
                return Future.succeededFuture(callable.call());
            } catch (Exception e) {
                return Future.failedFuture(e);
            }
        }).when(taskExecutor).submit(any(Callable.class));

        controller = new ResourceCredentialsController(proxy, context);
    }

    private static Stream<Arguments> testDataForTestSignIn() {
        return Stream.of(
                Arguments.of(
                        "toolset-1",
                        CredentialsLevel.GLOBAL,
                        ResourceAccessType.ALL,
                        HttpStatus.OK),

                Arguments.of(
                        "toolset 1",
                        CredentialsLevel.GLOBAL,
                        ResourceAccessType.ALL,
                        HttpStatus.OK)
        );
    }

    @ParameterizedTest
    @MethodSource("testDataForTestSignIn")
    void testSignIn(String resourceName,
                    CredentialsLevel credentialsLevel,
                    Set<ResourceAccessType> userPermissions,
                    HttpStatus expectedResponseStatus) {
        // Given
        mockRequest(resourceName, credentialsLevel, AuthenticationType.OAUTH);

        when(context.getApiKeyData()).thenReturn(mock(ApiKeyData.class));
        when(encryptionService.decrypt("encrypted-user-bucket")).thenReturn("Users/userSub/");
        when(context.getUserId()).thenReturn("userSub");
        when(context.getInitiatorId()).thenReturn("userSub");
        when(context.getConfig()).thenReturn(mock(Config.class));
        when(context.getProxy()).thenReturn(proxy);

        mockToolset();
        setPermissions(resourceName, userPermissions);

        // When
        controller.signIn();

        // Then
        verify(resourceCredentialsService).addResourceCredentials(any(), any(), any(), eq("userSub"));
        verify(context).respond(expectedResponseStatus, true);
    }

    private static Stream<Arguments> testDataForTestSignInPermissionDenied() {
        return Stream.of(
                Arguments.of(
                        "toolset-1",
                        CredentialsLevel.GLOBAL,
                        Set.of(ResourceAccessType.READ),
                        HttpStatus.FORBIDDEN,
                        "No read and write access to Resource"),

                Arguments.of(
                        "toolset-1",
                        CredentialsLevel.USER,
                        Set.of(),
                        HttpStatus.FORBIDDEN,
                        "No read access to Resource")
        );
    }

    @ParameterizedTest
    @MethodSource("testDataForTestSignInPermissionDenied")
    void testSignIn_PermissionDenied(String resourceName,
                                     CredentialsLevel credentialsLevel,
                                     Set<ResourceAccessType> userPermissions,
                                     HttpStatus expectedResponseStatus,
                                     String expectedResponseBody) {
        // Given
        mockRequest(resourceName, credentialsLevel, AuthenticationType.OAUTH);

        when(encryptionService.decrypt("encrypted-user-bucket")).thenReturn("Users/userSub/");
        when(context.getConfig()).thenReturn(mock(Config.class));

        mockToolset();
        setPermissions(resourceName, userPermissions);

        // When
        controller.signIn();

        // Then
        verify(context).respond(expectedResponseStatus, expectedResponseBody);
    }

    @Test
    void testSignIn_EncryptionException() {
        // Given
        String resourceName = "toolset-1";
        mockRequest(resourceName, CredentialsLevel.GLOBAL, AuthenticationType.OAUTH);

        when(encryptionService.decrypt("encrypted-user-bucket")).thenReturn("Users/userSub/");
        when(context.getConfig()).thenReturn(mock(Config.class));

        mockToolset();
        setPermissions(resourceName, ResourceAccessType.ALL);

        doAnswer(invocation -> {
            throw new EncryptionException("Failed to decrypt auth settings");
        }).when(resourceAuthSettingsEncryptionService)
                .decrypt(any(), any(), any());

        // When
        controller.signIn();

        // Then
        verify(context).respond(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to decrypt auth settings");
    }

    private static Stream<Arguments> testDataForTestSignOut() {
        return Stream.of(
                Arguments.of(
                        "toolset-1",
                        CredentialsLevel.GLOBAL,
                        ResourceAccessType.ALL,
                        HttpStatus.OK,
                        true),

                Arguments.of(
                        "toolset 1",
                        CredentialsLevel.GLOBAL,
                        ResourceAccessType.ALL,
                        HttpStatus.OK,
                        true)
        );
    }

    @ParameterizedTest
    @MethodSource("testDataForTestSignOut")
    void testSignOut(String resourceName,
                     CredentialsLevel credentialsLevel,
                     Set<ResourceAccessType> userPermissions,
                     HttpStatus expectedResponseStatus,
                     boolean expectedResponse) {
        // Given
        mockRequest(resourceName, credentialsLevel, AuthenticationType.OAUTH);

        when(context.getProxy()).thenReturn(proxy);
        when(encryptionService.decrypt("encrypted-user-bucket")).thenReturn("Users/userSub/");
        when(context.getUserId()).thenReturn("userSub");
        when(context.getInitiatorId()).thenReturn("userSub");
        when(context.getApiKeyData()).thenReturn(mock(ApiKeyData.class));
        when(context.getConfig()).thenReturn(mock(Config.class));

        mockToolset();
        setPermissions(resourceName, userPermissions);

        when(resourceCredentialsService.deleteResourceCredentials(any(), any(), any())).thenReturn(true);

        // When
        controller.signOut();

        // Then
        verify(resourceCredentialsService).deleteResourceCredentials(any(), any(), eq("userSub"));
        verify(context).respond(expectedResponseStatus, expectedResponse);
    }

    private static Stream<Arguments> testDataForTestSignOutPermissionDenied() {
        return Stream.of(
                Arguments.of(
                        "toolset-1",
                        CredentialsLevel.GLOBAL,
                        Set.of(ResourceAccessType.READ),
                        HttpStatus.FORBIDDEN,
                        "No read and write access to Resource"),

                Arguments.of(
                        "toolset-1",
                        CredentialsLevel.USER,
                        Set.of(),
                        HttpStatus.FORBIDDEN,
                        "No read access to Resource")
        );
    }

    @ParameterizedTest
    @MethodSource("testDataForTestSignOutPermissionDenied")
    void testSignOut_PermissionDenied(String resourceName,
                                      CredentialsLevel credentialsLevel,
                                      Set<ResourceAccessType> userPermissions,
                                      HttpStatus expectedResponseStatus,
                                      String expectedResponseBody) {
        // Given
        mockRequest(resourceName, credentialsLevel, AuthenticationType.OAUTH);

        when(context.getConfig()).thenReturn(mock(Config.class));
        when(encryptionService.decrypt("encrypted-user-bucket")).thenReturn("Users/userSub/");

        mockToolset();
        setPermissions(resourceName, userPermissions);

        // When
        controller.signOut();

        // Then
        verify(context).respond(expectedResponseStatus, expectedResponseBody);
    }

    @Test
    void testSignIn_UserLevelWithApiKeyAuthentication() {
        // Given - request is authenticated by API Key (no JWT), so userId is null and project name is the initiator.
        String resourceName = "toolset-1";
        mockRequest(resourceName, CredentialsLevel.USER, AuthenticationType.OAUTH);

        when(context.getApiKeyData()).thenReturn(mock(ApiKeyData.class));
        when(encryptionService.decrypt("encrypted-user-bucket")).thenReturn("Keys/api-key-project/");
        when(context.getUserId()).thenReturn(null);
        when(context.getProject()).thenReturn("api-key-project");
        when(context.getInitiatorId()).thenReturn("api-key-project");
        when(context.getConfig()).thenReturn(mock(Config.class));
        when(context.getProxy()).thenReturn(proxy);

        mockToolset();
        setPermissions(resourceName, "Keys/api-key-project/", ResourceAccessType.ALL);

        // When
        controller.signIn();

        // Then - project name is persisted as the owner so subsequent lookups do not NPE.
        verify(resourceCredentialsService).addResourceCredentials(any(), any(), any(), eq("api-key-project"));
        verify(context).respond(HttpStatus.OK, true);
    }

    @Test
    void testSignOut_UserLevelWithApiKeyAuthentication() {
        // Given - API Key authenticated sign-out must pass the project name as the owner to avoid NPE.
        String resourceName = "toolset-1";
        mockRequest(resourceName, CredentialsLevel.USER, AuthenticationType.OAUTH);

        when(context.getProxy()).thenReturn(proxy);
        when(encryptionService.decrypt("encrypted-user-bucket")).thenReturn("Keys/api-key-project/");
        when(context.getUserId()).thenReturn(null);
        when(context.getProject()).thenReturn("api-key-project");
        when(context.getInitiatorId()).thenReturn("api-key-project");
        when(context.getApiKeyData()).thenReturn(mock(ApiKeyData.class));
        when(context.getConfig()).thenReturn(mock(Config.class));

        mockToolset();
        setPermissions(resourceName, "Keys/api-key-project/", ResourceAccessType.ALL);

        when(resourceCredentialsService.deleteResourceCredentials(any(), any(), any())).thenReturn(true);

        // When
        controller.signOut();

        // Then
        verify(resourceCredentialsService).deleteResourceCredentials(any(), any(), eq("api-key-project"));
        verify(context).respond(HttpStatus.OK, true);
    }

    @Test
    void testSignIn_WrongAuthenticationType() {
        // Given
        mockRequest("toolset 1", CredentialsLevel.USER, AuthenticationType.API_KEY);
        mockToolset();

        // When
        controller.signIn();

        // Then
        verifyNoInteractions(resourceCredentialsService);
        verify(context).respond(HttpStatus.BAD_REQUEST, "Wrong authentication_type. Expected type: OAUTH, provided: API_KEY");
    }

    @Test
    void testSignOut_WrongAuthenticationType() {
        // Given
        mockRequest("toolset 1", CredentialsLevel.USER, AuthenticationType.API_KEY);
        mockToolset();

        // When
        controller.signOut();

        // Then
        verifyNoInteractions(resourceCredentialsService);
        verify(context).respond(HttpStatus.BAD_REQUEST, "Wrong authentication_type. Expected type: OAUTH, provided: API_KEY");
    }

    private ResourceDescriptor createResourceDescriptor(String resourceName) {
        return createResourceDescriptor(resourceName, "Users/userSub/");
    }

    private ResourceDescriptor createResourceDescriptor(String resourceName, String bucketLocation) {
        return new ResourceDescriptor(ResourceTypes.TOOL_SET, resourceName, List.of(),
                "encrypted-user-bucket", bucketLocation, false);
    }

    private void setPermissions(String resourceName, Set<ResourceAccessType> userPermissions) {
        setPermissions(resourceName, "Users/userSub/", userPermissions);
    }

    private void setPermissions(String resourceName, String bucketLocation, Set<ResourceAccessType> userPermissions) {
        ResourceDescriptor resourceDescriptor = createResourceDescriptor(resourceName, bucketLocation);
        Map<ResourceDescriptor, Set<ResourceAccessType>> permissions = Map.of(resourceDescriptor, userPermissions);
        when(accessService.lookupPermissions(any(), eq(context))).thenReturn(permissions);
    }

    private void mockToolset() {
        ToolSet mockToolSet = mock(ToolSet.class);
        ResourceAuthSettings resourceAuthSettings = mock(ResourceAuthSettings.class);
        when(resourceAuthSettings.getAuthenticationType()).thenReturn(AuthenticationType.OAUTH);
        when(mockToolSet.getAuthSettings()).thenReturn(resourceAuthSettings);
        when(deploymentService.findDeployment(eq(context), anyString())).thenReturn(mockToolSet);
    }

    private void mockRequest(String resourceName, CredentialsLevel credentialsLevel, AuthenticationType authenticationType) {
        byte [] requestBody = """
                {
                    "url": "toolsets/encrypted-user-bucket/%s",
                    "credentialsLevel": "%s",
                    "authenticationType": "%s"
                }
                """
                .formatted(resourceName, credentialsLevel, authenticationType)
                .getBytes(StandardCharsets.UTF_8);
        when(request.body()).thenReturn(Future.succeededFuture(Buffer.buffer(requestBody)));
        when(context.getRequest()).thenReturn(request);
    }

}
