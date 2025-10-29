package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.credentials.data.credentials.CredentialsDescriptor;
import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;
import com.epam.aidial.core.credentials.mapper.CredentialsDescriptorToResourceDescriptorMapper;
import com.epam.aidial.core.credentials.service.ResourceCredentialsService;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.Invitation;
import com.epam.aidial.core.server.data.InvitationLink;
import com.epam.aidial.core.server.data.ShareResourcesRequest;
import com.epam.aidial.core.server.data.SharedResource;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


@ExtendWith(MockitoExtension.class)
class ShareServiceTest {

    @Mock
    private ResourceService resourceService;

    @Mock
    private InvitationService invitationService;

    @Mock
    private EncryptionService encryptionService;

    @Mock
    private ResourceCredentialsService resourceCredentialsService;

    @Mock
    private CredentialsDescriptorToResourceDescriptorMapper credentialsDescriptorToResourceDescriptorMapper;

    @InjectMocks
    private ShareService shareService;

    @Test
    void initializeShare_whenRequestIsValid_shouldReturnInvitationLink() {
        // Given
        ShareResourcesRequest request = createValidShareResourcesRequest();

        when(encryptionService.encrypt(anyString())).thenReturn("encryptedBucket");
        when(encryptionService.decrypt(anyString())).thenReturn("decryptedBucket/");

        ProxyContext context = mock(ProxyContext.class, RETURNS_DEEP_STUBS);
        when(context.getUserSub()).thenReturn("userSub");
        when(context.getUserDisplayName()).thenReturn("userDisplayName");

        Invitation invitation = new Invitation();
        invitation.setId("invitationId");
        List<SharedResource> expectedSharedResources = request.getResources().stream().toList();
        when(invitationService.createInvitation(anyString(), anyString(),
                eq(expectedSharedResources),
                anyString(), anyInt(), anyLong()))
                .thenReturn(invitation);

        // When
        InvitationLink invitationLink = shareService.initializeShare(context, request);

        // Then
        assertNotNull(invitationLink);
        assertEquals(new InvitationLink("/v1/invitations/invitationId"), invitationLink);
    }


    private ShareResourcesRequest createValidShareResourcesRequest() {
        Set<SharedResource> resources = new HashSet<>();
        resources.add(new SharedResource("toolsets/encryptedBucket/toolset-1", "userDisplayName", null, ResourceAccessType.READ_ONLY));
        resources.add(new SharedResource("toolsets/encryptedBucket/toolset-2", "userDisplayName", null, ResourceAccessType.READ_ONLY));

        ShareResourcesRequest request = new ShareResourcesRequest();
        request.setResources(resources);
        request.setMaxAcceptedUsers(5);

        return request;
    }

    @Test
    void initializeShare_whenRequestIsValidWithCreds_shouldReturnInvitationLink() {
        // Given
        ShareResourcesRequest request = createValidShareWithCredsResourcesRequest();

        when(encryptionService.encrypt(anyString())).thenReturn("encryptedBucket");
        when(encryptionService.decrypt(anyString())).thenReturn("decryptedBucket/");

        ProxyContext proxyContext = mock(ProxyContext.class, RETURNS_DEEP_STUBS);
        Proxy proxy = mock(Proxy.class);
        when(proxyContext.getUserSub()).thenReturn("userSub");
        when(proxyContext.getUserDisplayName()).thenReturn("userDisplayName");
        when(proxyContext.getProxy()).thenReturn(proxy);
        when(proxy.getEncryptionService()).thenReturn(encryptionService);

        ResourceDescriptor credentialsResourceDescriptor = mock(ResourceDescriptor.class);
        when(credentialsResourceDescriptor.getUrl())
                .thenReturn("credentials/encryptedBucket/toolsets/encryptedBucket/toolset-1");
        when(credentialsDescriptorToResourceDescriptorMapper.map(any())).thenReturn(credentialsResourceDescriptor);

        ResourceCredentials resourceCredentials = mock(ResourceCredentials.class);
        when(resourceCredentials.getCredentialsLevel()).thenReturn(CredentialsLevel.GLOBAL);
        when(resourceCredentialsService.getResourceCredentials(any(CredentialsDescriptor.class))).thenReturn(resourceCredentials);

        Invitation invitation = new Invitation();
        invitation.setId("invitationId");
        List<SharedResource> expectedSharedResources = List.of(
                new SharedResource("toolsets/encryptedBucket/toolset-1",
                        "userDisplayName", null, ResourceAccessType.READ_ONLY, true),
                new SharedResource("credentials/encryptedBucket/toolsets/encryptedBucket/toolset-1",
                        "userDisplayName", null, ResourceAccessType.READ_ONLY, false)
        );
        when(invitationService.createInvitation(anyString(), anyString(), anyList(), anyString(), anyInt(), anyLong()))
                .thenReturn(invitation);

        // When
        InvitationLink invitationLink = shareService.initializeShare(proxyContext, request);

        // Then
        assertNotNull(invitationLink);
        assertEquals(new InvitationLink("/v1/invitations/invitationId"), invitationLink);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SharedResource>> captor = ArgumentCaptor.forClass(List.class);
        verify(invitationService).createInvitation(anyString(), anyString(), captor.capture(), anyString(), anyInt(), anyLong());

        Set<SharedResource> actualSharedResourcesSet = new HashSet<>(captor.getValue());
        Set<SharedResource> expectedSharedResourcesSet = new HashSet<>(expectedSharedResources);

        assertEquals(expectedSharedResourcesSet, actualSharedResourcesSet);
    }


    private ShareResourcesRequest createValidShareWithCredsResourcesRequest() {
        Set<SharedResource> resources = new HashSet<>();
        resources.add(new SharedResource("toolsets/encryptedBucket/toolset-1", null, null, ResourceAccessType.READ_ONLY, true));

        ShareResourcesRequest request = new ShareResourcesRequest();
        request.setResources(resources);
        request.setMaxAcceptedUsers(5);

        return request;
    }

    @Test
    void revokeSharedAccess_whenValidRequest_shouldRevokeAccess() {
        // Given
        String bucket = "encryptedUser1Bucket";
        String userLocation = "Users/user2/";

        ResourceDescriptor toolsetDescriptor = createResourceDescriptor(
                bucket, "toolsets/encryptedUser1Bucket/toolset-1", ResourceTypes.TOOL_SET);

        Map<ResourceDescriptor, Set<ResourceAccessType>> permissionsToRevoke = Map.of(
                toolsetDescriptor, Set.of(ResourceAccessType.READ)
        );

        String initialSharedByMeData = createSharedByMeJson();
        String initialSharedWithMeData = createSharedWithMeJson();

        when(resourceService.getResource(any(ResourceDescriptor.class)))
                .thenAnswer(invocation -> {
                    ResourceDescriptor descriptor = invocation.getArgument(0);
                    String url = descriptor.getUrl();
                    if (url != null && url.contains("shared/withMe")) {
                        return initialSharedWithMeData;
                    } else {
                        return initialSharedByMeData;
                    }
                });

        when(encryptionService.encrypt("Users/user2/")).thenReturn("encryptedUser2Bucket");

        ResourceItemMetadata mockMetadata = mock(ResourceItemMetadata.class);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Function<String, String>> functionCaptor = ArgumentCaptor.forClass(Function.class);
        when(resourceService.computeResource(any(ResourceDescriptor.class), functionCaptor.capture()))
                .thenReturn(mockMetadata);

        // When
        shareService.revokeSharedAccess(bucket, userLocation, permissionsToRevoke);

        // Then
        List<Function<String, String>> capturedFunctions = functionCaptor.getAllValues();
        for (Function<String, String> function : capturedFunctions) {
            try {
                String result = function.apply(initialSharedByMeData);
                if (result != null && result.contains("resourceToUsers")) {
                    assertFalse(result.contains("\"toolsets/encryptedUser1Bucket/toolset-1\": [\"Users/user2/\"]"),
                               "Toolset permissions for user2 should be removed");
                    assertFalse(result.contains("\"credentials/encryptedUser1Bucket/toolsets/encryptedUser1Bucket/toolset-1\": [\"Users/user2/\"]"),
                               "Credentials permissions for user2 should be removed");
                }
            } catch (Exception e) {
                String result = function.apply(initialSharedWithMeData);
                assertTrue(result.contains("resources"), "SharedWithMe data should be processed");
            }
        }
    }

    private ResourceDescriptor createResourceDescriptor(String bucket, String url, ResourceTypes type) {
        ResourceDescriptor descriptor = mock(ResourceDescriptor.class);
        when(descriptor.getBucketName()).thenReturn(bucket);
        when(descriptor.getUrl()).thenReturn(url);
        when(descriptor.getType()).thenReturn(type);
        return descriptor;
    }

    private String createSharedByMeJson() {
        return """
                {
                    "resourceToUsers": {
                        "credentials/encryptedUser1Bucket/toolsets/encryptedUser1Bucket/toolset-1": [
                            "Users/user2/"
                        ],
                        "toolsets/encryptedUser1Bucket/toolset-1": [
                            "Users/user2/"
                        ]
                    },
                    "writableResourcesToUsers": {},
                    "limits": {
                        "credentials/encryptedUser1Bucket/toolsets/encryptedUser1Bucket/toolset-1": {
                            "max_accepted_users": 2147483647,
                            "invitation_ttl": 259200
                        },
                        "toolsets/encryptedUser1Bucket/toolset-1": {
                            "max_accepted_users": 2147483647,
                            "invitation_ttl": 259200
                        }
                    },
                    "shareableResourcesToUsers": {},
                    "userIdToDisplayName": {
                        "Users/user2/": "user2DisplayName"
                    }
                }
                """;
    }

    private String createSharedWithMeJson() {
        return """
                {
                    "resources": [
                        {
                            "bucket": "encryptedUser2Bucket",
                            "url": "toolsets/encryptedUser2Bucket/toolset-1",
                            "nodeType": "ITEM",
                            "resourceType": "TOOL_SET",
                            "permissions": [
                                "READ"
                            ]
                        },
                        {
                            "bucket": "encryptedUser2Bucket",
                            "url": "credentials/encryptedUser1Bucket/toolsets/encryptedUser1Bucket/toolset-1",
                            "nodeType": "ITEM",
                            "resourceType": "CREDENTIALS",
                            "permissions": [
                                "READ"
                            ]
                        }
                    ]
                }
                """;
    }
}