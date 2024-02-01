package com.epam.aidial.core.service;

import com.epam.aidial.core.data.Invitation;
import com.epam.aidial.core.data.InvitationLink;
import com.epam.aidial.core.data.ListSharedResourcesRequest;
import com.epam.aidial.core.data.MetadataBase;
import com.epam.aidial.core.data.ResourceFolderMetadata;
import com.epam.aidial.core.data.ResourceItemMetadata;
import com.epam.aidial.core.data.ResourceLink;
import com.epam.aidial.core.data.ResourceLinkCollection;
import com.epam.aidial.core.data.ResourceType;
import com.epam.aidial.core.data.ShareResourcesRequest;
import com.epam.aidial.core.data.SharedByMeDto;
import com.epam.aidial.core.data.SharedResourcesResponse;
import com.epam.aidial.core.security.EncryptionService;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.ProxyUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@AllArgsConstructor
@Slf4j
public class ShareService {

    private static final String SHARE_RESOURCE_FILENAME = "share";

    private final ResourceService resourceService;
    private final InvitationService invitationService;
    private final EncryptionService encryptionService;

    public SharedResourcesResponse listSharedWithMe(String bucket, String location, ListSharedResourcesRequest request) {
        Set<ResourceType> resourceTypes = request.getResourceTypes();

        Set<ResourceDescription> shareResources = new HashSet<>();
        for (ResourceType resourceType : resourceTypes) {
            ResourceDescription shareResource = getShareResource(ResourceType.SHARED_WITH_ME, resourceType, bucket, location);
            shareResources.add(shareResource);
        }

        Set<MetadataBase> metadata = new HashSet<>();
        for (ResourceDescription resource : shareResources) {
            String sharedResource = resourceService.getResource(resource);
            if (sharedResource != null) {
                ResourceLinkCollection resourceCollection = ProxyUtil.convertToObject(sharedResource, ResourceLinkCollection.class);
                metadata.addAll(linksToMetadata(resourceCollection.getResources().stream().map(ResourceLink::url)));
            }
        }

        return new SharedResourcesResponse(metadata);
    }

    public SharedResourcesResponse listSharedByMe(String bucket, String location, ListSharedResourcesRequest request) {
        Set<ResourceType> resourceTypes = request.getResourceTypes();

        Set<ResourceDescription> resources = new HashSet<>();
        for (ResourceType resourceType : resourceTypes) {
            ResourceDescription resource = getShareResource(ResourceType.SHARED_BY_ME, resourceType, bucket, location);
            resources.add(resource);
        }

        Set<MetadataBase> metadata = new HashSet<>();
        for (ResourceDescription resourceDescription : resources) {
            String sharedResource = resourceService.getResource(resourceDescription);
            if (sharedResource != null) {
                SharedByMeDto resourceToUsers = ProxyUtil.convertToObject(sharedResource, SharedByMeDto.class);
                metadata.addAll(linksToMetadata(resourceToUsers.getResourceToUsers().keySet().stream()));
            }
        }

        return new SharedResourcesResponse(metadata);
    }

    public InvitationLink initializeShare(String userBucket, String userLocation, ShareResourcesRequest request) {
        // validate resources - owner must be current user
        Set<ResourceLink> resources = request.getResources();
        if (resources.isEmpty()) {
            throw new IllegalArgumentException("No resources provided");
        }

        for (ResourceLink resourceLink : resources) {
            if (!userBucket.equals(resourceLink.getBucket())) {
                throw new IllegalArgumentException("Requested share resource do not belong to the user " + resourceLink);
            }
        }

        Invitation invitation = invitationService.createInvitation(getUserId(userLocation), resources);

        return new InvitationLink("v1/invitations/" + invitation.getId());
    }

    public void acceptShare(String bucket, String location, String invitationId) {
        Invitation invitation = invitationService.getInvitation(invitationId);

        if (invitation == null) {
            throw new IllegalArgumentException("No invitation found for id: " + invitationId);
        }

        Set<ResourceLink> resourceLinks = invitation.getResources();

        Map<ResourceType, List<ResourceLink>> resourceGroups = resourceLinks.stream()
                .collect(Collectors.groupingBy(ResourceLink::getResourceType));

        for (Map.Entry<ResourceType, List<ResourceLink>> group : resourceGroups.entrySet()) {
            ResourceType resourceType = group.getKey();
            List<ResourceLink> links = group.getValue();
            String ownerBucket = links.get(0).getBucket();
            String ownerLocation = encryptionService.decrypt(ownerBucket);

            ResourceDescription sharedByMe = getShareResource(ResourceType.SHARED_BY_ME, resourceType, ownerBucket, ownerLocation);
            resourceService.computeResource(sharedByMe, state -> {
                // if no state - set current

                SharedByMeDto dto;
                if (state == null) {
                    Map<String, Set<String>> resourceToUsers = new HashMap<>();
                    dto = new SharedByMeDto(resourceToUsers);
                } else {
                    dto = ProxyUtil.convertToObject(state, SharedByMeDto.class);
                }

                links.forEach(resourceLink -> {
                    dto.addUserToResource(resourceLink.url(), location);
                });

                return ProxyUtil.convertToString(dto);
            });

            ResourceDescription sharedWithMe = getShareResource(ResourceType.SHARED_WITH_ME, resourceType, bucket, location);
            resourceService.computeResource(sharedWithMe, state -> {
                ResourceLinkCollection collection;

                if (state == null) {
                    collection = new ResourceLinkCollection(new HashSet<>());
                } else {
                    collection = ProxyUtil.convertToObject(state, ResourceLinkCollection.class);
                }

                collection.getResources().addAll(links);

                return ProxyUtil.convertToString(collection);
            });
        }
    }

    public boolean hasReadAccess(String bucket, String location, ResourceDescription resource) {
        ResourceDescription shareResource = getShareResource(ResourceType.SHARED_WITH_ME, resource.getType(), bucket, location);

        String state = resourceService.getResource(shareResource);
        if (state == null) {
            log.info("No state found for share access");
            return false;
        } else {
            ResourceLinkCollection sharedResources = ProxyUtil.convertToObject(state, ResourceLinkCollection.class);
            // add parent folder search
            return sharedResources.getResources().contains(new ResourceLink(resource.getUrl()));
        }
    }

    public void revokeSharedAccess(String bucket, String location, ResourceLinkCollection resources) {
        for (ResourceLink link : resources.getResources()) {
            if (!link.getBucket().equals(bucket)) {
                throw new IllegalArgumentException("You are only allowed to revoke access from own resources");
            }
        }

        for (ResourceLink link : resources.getResources()) {
            ResourceType resourceType = link.getResourceType();
            ResourceDescription sharedByMeResource = getShareResource(ResourceType.SHARED_BY_ME, resourceType, bucket, location);
            String state = resourceService.getResource(sharedByMeResource);
            SharedByMeDto dto = ProxyUtil.convertToObject(state, SharedByMeDto.class);
            if (dto != null) {
                Set<String> userLocations = dto.getResourceToUsers().get(link.url());

                for (String userLocation : userLocations) {
                    String userBucket = encryptionService.encrypt(userLocation);
                    ResourceDescription sharedWithMeResource = getShareResource(ResourceType.SHARED_WITH_ME, resourceType, userBucket, userLocation);
                    resourceService.computeResource(sharedWithMeResource, userState -> {
                        ResourceLinkCollection userLinks = ProxyUtil.convertToObject(userState, ResourceLinkCollection.class);
                        if (userLinks != null) {
                            userLinks.getResources().remove(link);
                        }

                        return ProxyUtil.convertToString(userLinks);
                    });
                }

                resourceService.computeResource(sharedByMeResource, ownerState -> {
                    SharedByMeDto sharedByMeDto = ProxyUtil.convertToObject(state, SharedByMeDto.class);
                    if (sharedByMeDto != null) {
                        sharedByMeDto.getResourceToUsers().remove(link.url());
                    }

                    return ProxyUtil.convertToString(sharedByMeDto);
                });
            }
        }
    }

    public void discardSharedAccess(String bucket, String location, ResourceLinkCollection resources) {
        for (ResourceLink link : resources.getResources()) {
            ResourceType resourceType = link.getResourceType();
            ResourceDescription sharedByMeResource = getShareResource(ResourceType.SHARED_WITH_ME, resourceType, bucket, location);
            resourceService.computeResource(sharedByMeResource, state -> {
                ResourceLinkCollection sharedWithMe = ProxyUtil.convertToObject(state, ResourceLinkCollection.class);
                if (sharedWithMe != null) {
                    sharedWithMe.getResources().remove(link);
                }

                return ProxyUtil.convertToString(sharedWithMe);
            });

            String ownerBucket = link.getBucket();
            String ownerLocation = encryptionService.decrypt(ownerBucket);

            ResourceDescription sharedWithMe = getShareResource(ResourceType.SHARED_WITH_ME, resourceType, ownerBucket, ownerLocation);
            resourceService.computeResource(sharedWithMe, ownerState -> {
                SharedByMeDto sharedByMeDto = ProxyUtil.convertToObject(ownerState, SharedByMeDto.class);
                if (sharedByMeDto != null) {
                    sharedByMeDto.getResourceToUsers().get(link.url()).remove(location);
                }

                return ProxyUtil.convertToString(sharedByMeDto);
            });
        }
    }

    private static String getUserId(String location) {
        return location.split(BlobStorageUtil.PATH_SEPARATOR)[1];
    }

    private static ResourceDescription getShareResource(ResourceType shareResourceType, ResourceType requestedResourceType, String bucket, String location) {
        return ResourceDescription.fromDecoded(shareResourceType, bucket, location,
                requestedResourceType.getGroup() + BlobStorageUtil.PATH_SEPARATOR + SHARE_RESOURCE_FILENAME);
    }

    private static List<MetadataBase> linksToMetadata(Stream<String> links) {
        return links
                .map(ResourceDescription::fromLink)
                .map(resource -> {
                    if (resource.isFolder()) {
                        return new ResourceFolderMetadata(resource);
                    } else {
                        return new ResourceItemMetadata(resource);
                    }
                }).toList();
    }

}
