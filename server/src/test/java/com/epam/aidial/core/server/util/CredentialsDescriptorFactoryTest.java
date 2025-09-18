package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.credentials.data.credentials.CredentialsDescriptor;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class CredentialsDescriptorFactoryTest {

    @Mock
    private ProxyContext proxyContext;
    @Mock
    private Proxy proxy;
    @Mock
    private EncryptionService encryptionService;

    private static final String USER_SUB = "userSub";
    private static final String PROJECT = "project";
    private static final String USER_BUCKET = "userBucket";
    private static final String RESOURCE_OWNER_BUCKET = "resourceOwnerBucket";
    private static final String RESOURCE_OWNER_BUCKET_LOCATION = "Users/resourceOwner/";
    private static final String USER_SUB_LOCATION = "Users/" + USER_SUB + "/";
    private static final String PROJECT_LOCATION = "Keys/" + PROJECT + "/";

    @BeforeEach
    public void beforeEach() {
        ApiKeyData apiKeyData = mock(ApiKeyData.class);
        when(proxyContext.getApiKeyData()).thenReturn(apiKeyData);
        when(proxyContext.getProxy()).thenReturn(proxy);
        when(proxy.getEncryptionService()).thenReturn(encryptionService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"toolsets/public/id", "toolsets/someBucket/id", "another/resource"})
    public void fromAnyUrl_userLevel_withUserSub(String resourceId) {
        // Given: The user context is defined by a user subscription
        when(proxyContext.getUserSub()).thenReturn(USER_SUB);
        when(encryptionService.encrypt(eq(USER_SUB_LOCATION))).thenReturn(USER_BUCKET);

        // When: We request credentials at the USER level
        CredentialsDescriptor descriptor = CredentialsDescriptorFactory.fromAnyUrl(resourceId, CredentialsLevel.USER, proxyContext);

        // Then: The descriptor should point to the user's bucket
        assertNotNull(descriptor);
        assertEquals(resourceId, descriptor.getResourceId());
        assertEquals(USER_BUCKET, descriptor.getBucketName());
        assertEquals(USER_SUB_LOCATION, descriptor.getBucketLocation());
    }

    @ParameterizedTest
    @ValueSource(strings = {"toolsets/public/id", "toolsets/someBucket/id", "another/resource"})
    public void fromAnyUrl_userLevel_withProject(String resourceId) {
        // Given: The user context is defined by a project
        when(proxyContext.getProject()).thenReturn(PROJECT);
        when(encryptionService.encrypt(eq(PROJECT_LOCATION))).thenReturn(USER_BUCKET);

        // When: We request credentials at the USER level
        CredentialsDescriptor descriptor = CredentialsDescriptorFactory.fromAnyUrl(resourceId, CredentialsLevel.USER, proxyContext);

        // Then: The descriptor should point to the project's bucket
        assertNotNull(descriptor);
        assertEquals(resourceId, descriptor.getResourceId());
        assertEquals(USER_BUCKET, descriptor.getBucketName());
        assertEquals(PROJECT_LOCATION, descriptor.getBucketLocation());
    }

    @ParameterizedTest
    @ValueSource(strings = {"toolsets/public/id", "another/resource"})
    public void fromAnyUrl_globalLevel_forPublicResources(String resourceId) {
        // Given: The user context is defined by a user subscription
        when(proxyContext.getUserSub()).thenReturn(USER_SUB);

        // When: We request credentials at the GLOBAL level for a public or static resource
        CredentialsDescriptor descriptor = CredentialsDescriptorFactory.fromAnyUrl(resourceId, CredentialsLevel.GLOBAL, proxyContext);

        // Then: The descriptor should point to the public bucket
        assertNotNull(descriptor);
        assertEquals(resourceId, descriptor.getResourceId());
        assertEquals(ResourceDescriptor.PUBLIC_BUCKET, descriptor.getBucketName());
        assertEquals(ResourceDescriptor.PUBLIC_LOCATION, descriptor.getBucketLocation());
    }

    @Test
    public void fromAnyUrl_globalLevel_currentUserResource_withUserSub() {
        // Given: A resource owned by the current user (context is userSub)
        String resourceId = "toolsets/" + USER_BUCKET + "/id";
        when(proxyContext.getUserSub()).thenReturn(USER_SUB);
        when(encryptionService.encrypt(eq(USER_SUB_LOCATION))).thenReturn(USER_BUCKET);
        when(encryptionService.decrypt(eq(USER_BUCKET))).thenReturn(USER_SUB_LOCATION);

        // When: We request credentials at the GLOBAL level
        CredentialsDescriptor descriptor = CredentialsDescriptorFactory.fromAnyUrl(resourceId, CredentialsLevel.GLOBAL, proxyContext);

        // Then: The descriptor should resolve to the user's own bucket
        assertNotNull(descriptor);
        assertEquals(USER_BUCKET, descriptor.getBucketName());
        assertEquals(USER_SUB_LOCATION, descriptor.getBucketLocation());
    }

    @Test
    public void fromAnyUrl_globalLevel_currentUserResource_withProject() {
        // Given: A resource owned by the current user (context is project)
        String resourceId = "toolsets/" + USER_BUCKET + "/id";
        when(proxyContext.getProject()).thenReturn(PROJECT);
        when(encryptionService.encrypt(eq(PROJECT_LOCATION))).thenReturn(USER_BUCKET);
        when(encryptionService.decrypt(eq(USER_BUCKET))).thenReturn(PROJECT_LOCATION);

        // When: We request credentials at the GLOBAL level
        CredentialsDescriptor descriptor = CredentialsDescriptorFactory.fromAnyUrl(resourceId, CredentialsLevel.GLOBAL, proxyContext);

        // Then: The descriptor should resolve to the user's own bucket (project bucket in this case)
        assertNotNull(descriptor);
        assertEquals(USER_BUCKET, descriptor.getBucketName());
        assertEquals(PROJECT_LOCATION, descriptor.getBucketLocation());
    }

    @Test
    public void fromAnyUrl_globalLevel_otherUserResource_throwsException() {
        // Given: A resource owned by a different user
        String resourceId = "toolsets/" + RESOURCE_OWNER_BUCKET + "/id";
        when(proxyContext.getUserSub()).thenReturn(USER_SUB);
        when(encryptionService.encrypt(eq(USER_SUB_LOCATION))).thenReturn(USER_BUCKET);
        when(encryptionService.decrypt(eq(RESOURCE_OWNER_BUCKET))).thenReturn(RESOURCE_OWNER_BUCKET_LOCATION);

        // When & Then: Requesting GLOBAL credentials should be disallowed
        Exception exception = assertThrows(IllegalArgumentException.class, () ->
                CredentialsDescriptorFactory.fromAnyUrl(resourceId, CredentialsLevel.GLOBAL, proxyContext)
        );

        assertEquals("Cannot modify global credentials of other users: " + resourceId, exception.getMessage());
    }
}