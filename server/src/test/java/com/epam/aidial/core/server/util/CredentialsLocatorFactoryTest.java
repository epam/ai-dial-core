package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class CredentialsLocatorFactoryTest {

    @Mock
    private ProxyContext proxyContext;
    @Mock
    private Proxy proxy;
    @Mock
    private EncryptionService encryptionService;
    @Mock
    private ApiKeyData apiKeyData;
    @Mock
    private Config config;

    private static final String USER_SUB = "userSub";
    private static final String PROJECT = "project";
    private static final String USER_BUCKET = "userBucket";
    private static final String RESOURCE_OWNER_BUCKET = "resourceOwnerBucket";
    private static final String RESOURCE_OWNER_BUCKET_LOCATION = "Users/resourceOwner/";
    private static final String USER_SUB_LOCATION = "Users/" + USER_SUB + "/";
    private static final String PROJECT_LOCATION = "Keys/" + PROJECT + "/";

    @BeforeEach
    public void beforeEach() {
        when(proxyContext.getProxy()).thenReturn(proxy);
        when(proxy.getEncryptionService()).thenReturn(encryptionService);
        when(proxyContext.getApiKeyData()).thenReturn(apiKeyData);
    }

    private static Stream<Arguments> testCases() {
        return Stream.of(
                Arguments.of("toolsets/resourceOwnerBucket/id", RESOURCE_OWNER_BUCKET, RESOURCE_OWNER_BUCKET_LOCATION),
                Arguments.of("toolsets/public/id", ResourceDescriptor.PUBLIC_BUCKET, ResourceDescriptor.PUBLIC_LOCATION)
        );
    }

    @ParameterizedTest
    @MethodSource("testCases")
    public void fromAnyUrl_withUserSub(String resourceId, String expectedGlobalBucket, String expectedGlobalLocation) {
        // Given
        when(encryptionService.encrypt(eq(USER_SUB_LOCATION))).thenReturn(USER_BUCKET);
        when(proxyContext.getUserSub()).thenReturn(USER_SUB);
        when(proxyContext.getConfig()).thenReturn(config);
        if (resourceId.contains(RESOURCE_OWNER_BUCKET)) {
            when(encryptionService.decrypt(eq(RESOURCE_OWNER_BUCKET))).thenReturn(RESOURCE_OWNER_BUCKET_LOCATION);
        }

        // When
        CredentialsLocator credentialsLocator = CredentialsLocatorFactory.fromAnyUrl(resourceId, proxyContext, ResourceTypes.TOOL_SET);

        // Then
        assertNotNull(credentialsLocator);
        assertEquals(resourceId, credentialsLocator.getResourceId());

        Map<CredentialsLevel, BucketInfo> buckets = credentialsLocator.getBuckets();
        assertEquals(2, buckets.size());

        assertBucketInfo(buckets.get(CredentialsLevel.USER), USER_BUCKET, USER_SUB_LOCATION);
        assertBucketInfo(buckets.get(CredentialsLevel.GLOBAL), expectedGlobalBucket, expectedGlobalLocation);
    }

    @ParameterizedTest
    @MethodSource("testCases")
    public void fromAnyUrl_withProject(String resourceId, String expectedGlobalBucket, String expectedGlobalLocation) {
        // Given
        when(encryptionService.encrypt(eq(PROJECT_LOCATION))).thenReturn(USER_BUCKET);
        when(proxyContext.getProject()).thenReturn(PROJECT);
        when(proxyContext.getConfig()).thenReturn(config);
        if (resourceId.contains(RESOURCE_OWNER_BUCKET)) {
            when(encryptionService.decrypt(eq(RESOURCE_OWNER_BUCKET))).thenReturn(RESOURCE_OWNER_BUCKET_LOCATION);
        }

        // When
        CredentialsLocator credentialsLocator = CredentialsLocatorFactory.fromAnyUrl(resourceId, proxyContext, ResourceTypes.TOOL_SET);

        // Then
        assertNotNull(credentialsLocator);
        assertEquals(resourceId, credentialsLocator.getResourceId());

        Map<CredentialsLevel, BucketInfo> buckets = credentialsLocator.getBuckets();
        assertEquals(2, buckets.size());

        assertBucketInfo(buckets.get(CredentialsLevel.USER), USER_BUCKET, PROJECT_LOCATION);
        assertBucketInfo(buckets.get(CredentialsLevel.GLOBAL), expectedGlobalBucket, expectedGlobalLocation);
    }

    @Test
    void fromAnyUrl_ResourceFromConfig() {
        // Given
        String resourceId = "my-toolset";
        when(encryptionService.encrypt(USER_SUB_LOCATION)).thenReturn(USER_BUCKET);
        when(proxyContext.getUserSub()).thenReturn(USER_SUB);
        when(proxyContext.getConfig()).thenReturn(config);
        when(config.isDeploymentExists(resourceId)).thenReturn(true);

        // When
        CredentialsLocator credentialsLocator = CredentialsLocatorFactory.fromAnyUrl(resourceId, proxyContext, ResourceTypes.TOOL_SET);

        // Then
        assertNotNull(credentialsLocator);
        assertEquals("toolsets/config/my-toolset", credentialsLocator.getResourceId());

        Map<CredentialsLevel, BucketInfo> buckets = credentialsLocator.getBuckets();
        assertEquals(2, buckets.size());

        assertBucketInfo(buckets.get(CredentialsLevel.USER), USER_BUCKET, USER_SUB_LOCATION);
        assertBucketInfo(buckets.get(CredentialsLevel.GLOBAL), ResourceDescriptor.PUBLIC_BUCKET, ResourceDescriptor.PUBLIC_LOCATION);
    }

    private void assertBucketInfo(BucketInfo bucketInfo, String expectedName, String expectedLocation) {
        assertEquals(expectedName, bucketInfo.name());
        assertEquals(expectedLocation, bucketInfo.location());
    }

}