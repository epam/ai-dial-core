package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.ResourceAuthStatus;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;
import com.epam.aidial.core.credentials.data.credentials.ResourceTypes;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.List;


class ResourceAuthSettingsServiceTest {

    private static final String USER_1 = "user1";
    private static final String USER_2 = "user2";

    @Mock
    private ResourceCredentialsManager resourceCredentialsManager;

    @InjectMocks
    private ResourceAuthSettingsService resourceAuthSettingsService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testSetResourceAuthStatuses_User1SignedIn() {
        // Given
        ToolSet toolSet = createToolSet();
        ResourceCredentials expiredUserCredentials = createUserLevelResourceCredentials(true, USER_1);
        ResourceCredentials nonExpiredUserCredentials = createUserLevelResourceCredentials(false, USER_1);
        Mockito.when(resourceCredentialsManager.getAllResourceCredentials(createResourceDescriptor()))
                .thenReturn(List.of(expiredUserCredentials, nonExpiredUserCredentials));

        // When
        resourceAuthSettingsService.setResourceAuthStatuses(createResourceDescriptor(), toolSet.getAuthSettings(), USER_1);

        // Then
        Assertions.assertEquals(ResourceAuthStatus.SIGNED_IN, toolSet.getAuthSettings().getUserLevelAuthStatus());
        Assertions.assertEquals(ResourceAuthStatus.SIGNED_OUT, toolSet.getAuthSettings().getGlobalAuthStatus());
    }

    @Test
    void testSetResourceAuthStatuses_User1SignedOut() {
        // Given
        ToolSet toolSet = createToolSet();
        ResourceCredentials expiredUserCredentials = createUserLevelResourceCredentials(true, USER_2);
        ResourceCredentials nonExpiredUserCredentials = createUserLevelResourceCredentials(false, USER_2);
        Mockito.when(resourceCredentialsManager.getAllResourceCredentials(createResourceDescriptor()))
                .thenReturn(List.of(expiredUserCredentials, nonExpiredUserCredentials));

        // When
        resourceAuthSettingsService.setResourceAuthStatuses(createResourceDescriptor(), toolSet.getAuthSettings(), USER_1);

        // Then
        Assertions.assertEquals(ResourceAuthStatus.SIGNED_OUT, toolSet.getAuthSettings().getUserLevelAuthStatus());
        Assertions.assertEquals(ResourceAuthStatus.SIGNED_OUT, toolSet.getAuthSettings().getGlobalAuthStatus());
    }

    @Test
    void testSetResourceAuthStatuses_User1WithExpiredCredsSignedOut() {
        // Given
        ToolSet toolSet = createToolSet();
        ResourceCredentials expiredUserCredentials = createUserLevelResourceCredentials(true, USER_1);
        Mockito.when(resourceCredentialsManager.getAllResourceCredentials(createResourceDescriptor()))
                .thenReturn(List.of(expiredUserCredentials));

        // When
        resourceAuthSettingsService.setResourceAuthStatuses(createResourceDescriptor(), toolSet.getAuthSettings(), USER_1);

        // Then
        Assertions.assertEquals(ResourceAuthStatus.SIGNED_OUT, toolSet.getAuthSettings().getUserLevelAuthStatus());
        Assertions.assertEquals(ResourceAuthStatus.SIGNED_OUT, toolSet.getAuthSettings().getGlobalAuthStatus());
    }

    @Test
    void testSetResourceAuthStatuses_GlobalSignedIn() {
        // Given
        ToolSet toolSet = createToolSet();
        ResourceCredentials globalCredentials = createGlobalLevelResourceCredentials(false);
        Mockito.when(resourceCredentialsManager.getAllResourceCredentials(createResourceDescriptor()))
                .thenReturn(List.of(globalCredentials));

        // When
        resourceAuthSettingsService.setResourceAuthStatuses(createResourceDescriptor(), toolSet.getAuthSettings(), USER_1);

        // Then
        Assertions.assertEquals(ResourceAuthStatus.SIGNED_OUT, toolSet.getAuthSettings().getUserLevelAuthStatus());
        Assertions.assertEquals(ResourceAuthStatus.SIGNED_IN, toolSet.getAuthSettings().getGlobalAuthStatus());
    }

    @Test
    void testSetResourceAuthStatuses_GlobalSignedOut() {
        // Given
        ToolSet toolSet = createToolSet();
        ResourceCredentials globalCredentials = createGlobalLevelResourceCredentials(true);
        Mockito.when(resourceCredentialsManager.getAllResourceCredentials(createResourceDescriptor()))
                .thenReturn(List.of(globalCredentials));

        // When
        resourceAuthSettingsService.setResourceAuthStatuses(createResourceDescriptor(), toolSet.getAuthSettings(), USER_1);

        // Then
        Assertions.assertEquals(ResourceAuthStatus.SIGNED_OUT, toolSet.getAuthSettings().getUserLevelAuthStatus());
        Assertions.assertEquals(ResourceAuthStatus.SIGNED_OUT, toolSet.getAuthSettings().getGlobalAuthStatus());
    }

    @Test
    void testSetResourceAuthStatuses_GlobalAndUserSignedIn() {
        // Given
        ToolSet toolSet = createToolSet();
        ResourceCredentials userCredentials = createUserLevelResourceCredentials(false, USER_1);
        ResourceCredentials globalCredentials = createGlobalLevelResourceCredentials(false);
        Mockito.when(resourceCredentialsManager.getAllResourceCredentials(createResourceDescriptor()))
                .thenReturn(List.of(userCredentials, globalCredentials));

        // When
        resourceAuthSettingsService.setResourceAuthStatuses(createResourceDescriptor(), toolSet.getAuthSettings(), USER_1);

        // Then
        Assertions.assertEquals(ResourceAuthStatus.SIGNED_IN, toolSet.getAuthSettings().getUserLevelAuthStatus());
        Assertions.assertEquals(ResourceAuthStatus.SIGNED_IN, toolSet.getAuthSettings().getGlobalAuthStatus());
    }

    @Test
    void testSetResourceAuthStatuses_NoCredentials() {
        // Given
        ToolSet toolSet = createToolSet();
        Mockito.when(resourceCredentialsManager.getAllResourceCredentials(createResourceDescriptor())).thenReturn(List.of());

        // When
        resourceAuthSettingsService.setResourceAuthStatuses(createResourceDescriptor(), toolSet.getAuthSettings(), USER_1);

        // Then
        Assertions.assertEquals(ResourceAuthStatus.SIGNED_OUT, toolSet.getAuthSettings().getUserLevelAuthStatus());
        Assertions.assertEquals(ResourceAuthStatus.SIGNED_OUT, toolSet.getAuthSettings().getGlobalAuthStatus());
    }

    private ResourceDescriptor createResourceDescriptor() {
        return new ResourceDescriptor(
                ResourceTypes.TOOL_SET_CREDENTIALS,
                "my-toolset",
                List.of("folder1"),
                "bucket-name",
                "bucket-location/",
                false
        );
    }

    private ToolSet createToolSet() {
        ResourceAuthSettings authSettings = new ResourceAuthSettings();
        ToolSet toolSet = Mockito.mock(ToolSet.class);
        Mockito.when(toolSet.getName()).thenReturn("toolset-1");
        Mockito.when(toolSet.getAuthSettings()).thenReturn(authSettings);
        return toolSet;
    }

    private ResourceCredentials createGlobalLevelResourceCredentials(boolean isExpired) {
        return createResourceCredentials(CredentialsLevel.GLOBAL, AuthenticationType.OAUTH, isExpired, null);
    }

    private ResourceCredentials createUserLevelResourceCredentials(boolean isExpired, String userSub) {
        return createResourceCredentials(CredentialsLevel.USER, AuthenticationType.OAUTH, isExpired, userSub);
    }

    private ResourceCredentials createResourceCredentials(CredentialsLevel level,
                                                          AuthenticationType authenticationType,
                                                          boolean isExpired,
                                                          String userSub) {
        ResourceCredentials credentials = Mockito.mock(ResourceCredentials.class);
        Mockito.when(credentials.getCredentialsLevel()).thenReturn(level);
        Mockito.when(credentials.getAuthenticationType()).thenReturn(authenticationType);
        Mockito.when(credentials.isTokenExpired()).thenReturn(isExpired);
        Mockito.when(credentials.getUserSub()).thenReturn(userSub);
        return credentials;
    }
}
