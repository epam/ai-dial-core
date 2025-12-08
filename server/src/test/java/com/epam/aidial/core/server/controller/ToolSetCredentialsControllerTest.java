package com.epam.aidial.core.server.controller;

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

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolSetCredentialsControllerTest {

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

    private ToolSetCredentialsController controller;

    @BeforeEach
    void setup() {
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor);
        when(proxy.getResourceCredentialsService()).thenReturn(resourceCredentialsService);
        when(proxy.getAccessService()).thenReturn(accessService);
        when(proxy.getEncryptionService()).thenReturn(encryptionService);
        when(proxy.getDeploymentService()).thenReturn(deploymentService);
        when(proxy.getResourceAuthSettingsEncryptionService()).thenReturn(resourceAuthSettingsEncryptionService);
        when(context.getConfig()).thenReturn(mock(Config.class));

        doAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            try {
                return Future.succeededFuture(callable.call());
            } catch (Exception e) {
                return Future.failedFuture(e);
            }
        }).when(taskExecutor).submit(any(Callable.class));

        controller = new ToolSetCredentialsController(proxy, context);
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
        byte[] requestBody = """
                {
                    "url": "toolsets/encrypted-user-bucket/%s",
                    "credentialsLevel": "%s",
                    "authenticationType": "OAUTH"
                }
                """
                .formatted(resourceName, credentialsLevel)
                .getBytes(StandardCharsets.UTF_8);
        when(request.body()).thenReturn(Future.succeededFuture(Buffer.buffer(requestBody)));
        when(context.getRequest()).thenReturn(request);
        when(context.getProxy()).thenReturn(proxy);
        when(context.getApiKeyData()).thenReturn(mock(ApiKeyData.class));

        ToolSet mockToolSet = mock(ToolSet.class);
        when(mockToolSet.getAuthSettings()).thenReturn(new ResourceAuthSettings());

        String resourceId = "toolsets/encrypted-user-bucket/%s".formatted(resourceName);
        when(deploymentService.findDeployment(context, resourceId)).thenReturn(mockToolSet);
        when(encryptionService.decrypt("encrypted-user-bucket")).thenReturn("Users/userSub/");
        when(context.getUserSub()).thenReturn("userSub");

        ResourceDescriptor resourceDescriptor = createResourceDescriptor(resourceName);
        Map<ResourceDescriptor, Set<ResourceAccessType>> permissions = Map.of(resourceDescriptor, userPermissions);
        when(accessService.lookupPermissions(any(), eq(context))).thenReturn(permissions);

        // When
        controller.signIn();

        // Then
        verify(resourceCredentialsService).addResourceCredentials(any(), any(), any(), any());
        verify(context).respond(expectedResponseStatus, true);
    }

    private static Stream<Arguments> testDataForTestSignInPermissionDenied() {
        return Stream.of(
                Arguments.of(
                        "toolset-1",
                        CredentialsLevel.GLOBAL,
                        Set.of(ResourceAccessType.READ),
                        HttpStatus.FORBIDDEN,
                        "No read and write access to ToolSet resource"),

                Arguments.of(
                        "toolset-1",
                        CredentialsLevel.USER,
                        Set.of(),
                        HttpStatus.FORBIDDEN,
                        "No read access to ToolSet resource")
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
        byte[] requestBody = """
                {
                    "url": "toolsets/encrypted-user-bucket/%s",
                    "credentialsLevel": "%s",
                    "authenticationType": "OAUTH"
                }
                """
                .formatted(resourceName, credentialsLevel)
                .getBytes(StandardCharsets.UTF_8);
        when(request.body()).thenReturn(Future.succeededFuture(Buffer.buffer(requestBody)));
        when(context.getRequest()).thenReturn(request);

        ToolSet mockToolSet = mock(ToolSet.class);
        String resourceId = "toolsets/encrypted-user-bucket/%s".formatted(resourceName);
        when(deploymentService.findDeployment(context, resourceId))
                .thenReturn(mockToolSet);
        when(encryptionService.decrypt("encrypted-user-bucket")).thenReturn("Users/userSub/");

        ResourceDescriptor resourceDescriptor = createResourceDescriptor(resourceName);
        Map<ResourceDescriptor, Set<ResourceAccessType>> permissions = Map.of(resourceDescriptor, userPermissions);
        when(accessService.lookupPermissions(any(), eq(context))).thenReturn(permissions);

        // When
        controller.signIn();

        // Then
        verify(context).respond(expectedResponseStatus, expectedResponseBody);
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

    @Test
    void testSignIn_EncryptionException() {
        // Given
        String resourceName = "toolset-1";
        CredentialsLevel credentialsLevel = CredentialsLevel.GLOBAL;

        byte[] requestBody = """
                {
                    "url": "toolsets/encrypted-user-bucket/%s",
                    "credentialsLevel": "%s",
                    "authenticationType": "OAUTH"
                }
                """
                .formatted(resourceName, credentialsLevel)
                .getBytes(StandardCharsets.UTF_8);
        when(request.body()).thenReturn(Future.succeededFuture(Buffer.buffer(requestBody)));
        when(context.getRequest()).thenReturn(request);

        ToolSet mockToolSet = mock(ToolSet.class);
        when(mockToolSet.getAuthSettings()).thenReturn(new ResourceAuthSettings());

        String resourceId = "toolsets/encrypted-user-bucket/%s".formatted(resourceName);
        when(deploymentService.findDeployment(context, resourceId))
                .thenReturn(mockToolSet);
        when(encryptionService.decrypt("encrypted-user-bucket")).thenReturn("Users/userSub/");

        ResourceDescriptor resourceDescriptor = createResourceDescriptor(resourceName);
        Map<ResourceDescriptor, Set<ResourceAccessType>> permissions = Map.of(resourceDescriptor, ResourceAccessType.ALL);
        when(accessService.lookupPermissions(any(), eq(context))).thenReturn(permissions);

        doAnswer(invocation -> {
            throw new EncryptionException("Failed to decrypt auth settings");
        }).when(resourceAuthSettingsEncryptionService)
                .decrypt(any(), any(), any());

        // When
        controller.signIn();

        // Then
        verify(context).respond(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to decrypt auth settings");
    }

    @ParameterizedTest
    @MethodSource("testDataForTestSignOut")
    void testSignOut(String resourceName,
                     CredentialsLevel credentialsLevel,
                     Set<ResourceAccessType> userPermissions,
                     HttpStatus expectedResponseStatus,
                     boolean expextedResonse) {
        // Given
        byte[] requestBody = """
                {
                    "url": "toolsets/encrypted-user-bucket/%s",
                    "credentialsLevel": "%s",
                    "authenticationType": "OAUTH"
                }
                """
                .formatted(resourceName, credentialsLevel)
                .getBytes(StandardCharsets.UTF_8);
        when(request.body()).thenReturn(Future.succeededFuture(Buffer.buffer(requestBody)));
        when(context.getRequest()).thenReturn(request);
        when(context.getProxy()).thenReturn(proxy);

        when(encryptionService.decrypt("encrypted-user-bucket")).thenReturn("Users/userSub/");
        when(context.getUserSub()).thenReturn("userSub");

        when(context.getApiKeyData()).thenReturn(mock(ApiKeyData.class));

        ResourceDescriptor resourceDescriptor = createResourceDescriptor(resourceName);
        Map<ResourceDescriptor, Set<ResourceAccessType>> permissions = Map.of(resourceDescriptor, userPermissions);
        when(accessService.lookupPermissions(any(), eq(context))).thenReturn(permissions);

        when(resourceCredentialsService.deleteResourceCredentials(any(), any(), any())).thenReturn(true);

        // When
        controller.signOut();

        // Then
        verify(context).respond(expectedResponseStatus, expextedResonse);
    }

    private static Stream<Arguments> testDataForTestSignOutPermissionDenied() {
        return Stream.of(
                Arguments.of(
                        "toolset-1",
                        CredentialsLevel.GLOBAL,
                        Set.of(ResourceAccessType.READ),
                        HttpStatus.FORBIDDEN,
                        "No read and write access to ToolSet resource"),

                Arguments.of(
                        "toolset-1",
                        CredentialsLevel.USER,
                        Set.of(),
                        HttpStatus.FORBIDDEN,
                        "No read access to ToolSet resource")
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
        byte[] requestBody = """
                {
                    "url": "toolsets/encrypted-user-bucket/%s",
                    "credentialsLevel": "%s",
                    "authenticationType": "OAUTH"
                }
                """
                .formatted(resourceName, credentialsLevel)
                .getBytes(StandardCharsets.UTF_8);
        when(request.body()).thenReturn(Future.succeededFuture(Buffer.buffer(requestBody)));
        when(context.getRequest()).thenReturn(request);
        when(context.getConfig()).thenReturn(mock(Config.class));
        when(encryptionService.decrypt("encrypted-user-bucket")).thenReturn("Users/userSub/");

        ResourceDescriptor resourceDescriptor = createResourceDescriptor(resourceName);
        Map<ResourceDescriptor, Set<ResourceAccessType>> permissions = Map.of(resourceDescriptor, userPermissions);
        when(accessService.lookupPermissions(any(), eq(context))).thenReturn(permissions);

        // When
        controller.signOut();

        // Then
        verify(context).respond(expectedResponseStatus, expectedResponseBody);
    }

    private ResourceDescriptor createResourceDescriptor(String resourceName) {
        return new ResourceDescriptor(ResourceTypes.TOOL_SET, resourceName, List.of(),
                "encrypted-user-bucket", "Users/userSub/", false);
    }

}
