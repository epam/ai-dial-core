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
import com.epam.aidial.core.storage.BlobStorage;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.ProxyUtil;
import com.epam.aidial.core.util.ResourceUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@AllArgsConstructor
public class ShareService {

    private static final String SHARE_RESOURCE_FILENAME = "share";

    private final ResourceService resourceService;
    private final InvitationService invitationService;
    private final EncryptionService encryptionService;
    private final BlobStorage storage;

    /**
     * Returns a list of resources shared with user.
     *
     * @param bucket - user bucket
     * @param location - storage location
     * @param request - request body
     * @return list of shared with user resources
     */
    public SharedResourcesResponse listSharedWithMe(String bucket, String location, ListSharedResourcesRequest request) {
        Set<ResourceType> requestedResourceType = request.getResourceTypes();

        Set<ResourceDescription> shareResources = new HashSet<>();
        for (ResourceType resourceType : requestedResourceType) {
            ResourceDescription sharedResource = getShareResource(ResourceType.SHARED_WITH_ME, resourceType, bucket, location);
            shareResources.add(sharedResource);
        }

        Set<MetadataBase> resultMetadata = new HashSet<>();
        for (ResourceDescription resource : shareResources) {
            String sharedResource = resourceService.getResource(resource);
            ResourceLinkCollection resourceLinksCollection = ProxyUtil.convertToObject(sharedResource, ResourceLinkCollection.class);
            if (resourceLinksCollection != null && resourceLinksCollection.getResources() != null) {
                resultMetadata.addAll(linksToMetadata(resourceLinksCollection.getResources().stream().map(ResourceLink::url)));
            }
        }

        return new SharedResourcesResponse(resultMetadata);
    }

    /**
     * Returns list of resources shared by user.
     *
     * @param bucket - user bucket
     * @param location - storage location
     * @param request - request body
     * @return list of shared with user resources
     */
    public SharedResourcesResponse listSharedByMe(String bucket, String location, ListSharedResourcesRequest request) {
        Set<ResourceType> requestedResourceTypes = request.getResourceTypes();

        Set<ResourceDescription> shareResources = new HashSet<>();
        for (ResourceType resourceType : requestedResourceTypes) {
            ResourceDescription shareResource = getShareResource(ResourceType.SHARED_BY_ME, resourceType, bucket, location);
            shareResources.add(shareResource);
        }

        Set<MetadataBase> resultMetadata = new HashSet<>();
        for (ResourceDescription resource : shareResources) {
            String sharedResource = resourceService.getResource(resource);
            SharedByMeDto resourceToUsers = ProxyUtil.convertToObject(sharedResource, SharedByMeDto.class);
            if (resourceToUsers != null && resourceToUsers.getResourceToUsers() != null) {
                resultMetadata.addAll(linksToMetadata(resourceToUsers.getResourceToUsers().keySet().stream()));
            }
        }

        return new SharedResourcesResponse(resultMetadata);
    }

    /**
     * Initialize share request by creating invitation object
     *
     * @param bucket - user bucket
     * @param location - storage location
     * @param request - request body
     * @return invitation link
     */
    public InvitationLink initializeShare(String bucket, String location, ShareResourcesRequest request) {
        // validate resources - owner must be current user
        Set<ResourceLink> resourceLinks = request.getResources();
        if (resourceLinks.isEmpty()) {
            throw new IllegalArgumentException("No resources provided");
        }

        Set<ResourceLink> normalizedResourceLinks = new HashSet<>();
        for (ResourceLink resourceLink : resourceLinks) {
            String url = resourceLink.url();
            ResourceDescription resource = getResourceFromLink(url);
            if (!bucket.equals(resource.getBucketName())) {
                throw new IllegalArgumentException("Resource %s does not belong to the user".formatted(url));
            }
            normalizedResourceLinks.add(new ResourceLink(resource.getUrl()));
        }

        Invitation invitation = invitationService.createInvitation(bucket, location, normalizedResourceLinks);
        return new InvitationLink(InvitationService.INVITATION_PATH_BASE + BlobStorageUtil.PATH_SEPARATOR + invitation.getId());
    }

    /**
     * Accept an invitation to grand share access for provided resources
     *
     * @param bucket - user bucket
     * @param location - storage location
     * @param invitationId - invitation ID
     */
    public void acceptSharedResources(String bucket, String location, String invitationId) {
        Invitation invitation = invitationService.getInvitation(invitationId);

        if (invitation == null) {
            throw new ResourceNotFoundException("No invitation found with id: " + invitationId);
        }

        Set<ResourceLink> resourceLinks = invitation.getResources();
        for (ResourceLink link : resourceLinks) {
            String url = link.url();
            if (ResourceDescription.fromPrivateUrl(url, encryptionService).getBucketName().equals(bucket)) {
                throw new IllegalArgumentException("Resource %s already belong to you".formatted(url));
            }
        }

        // group resources with the same type to reduce resource transformations
        Map<ResourceType, List<ResourceLink>> resourceGroups = resourceLinks.stream()
                .collect(Collectors.groupingBy(ResourceLink::getResourceType));

        for (Map.Entry<ResourceType, List<ResourceLink>> group : resourceGroups.entrySet()) {
            ResourceType resourceType = group.getKey();
            List<ResourceLink> links = group.getValue();
            String ownerBucket = links.get(0).getBucket();
            String ownerLocation = encryptionService.decrypt(ownerBucket);

            // write user location to the resource owner
            ResourceDescription sharedByMe = getShareResource(ResourceType.SHARED_BY_ME, resourceType, ownerBucket, ownerLocation);
            resourceService.computeResource(sharedByMe, state -> {
                SharedByMeDto dto = ProxyUtil.convertToObject(state, SharedByMeDto.class);
                if (dto == null) {
                    dto = new SharedByMeDto(new HashMap<>());
                }

                // add user location for each link
                for (ResourceLink resourceLink : links) {
                    dto.addUserToResource(resourceLink.url(), location);
                }

                return ProxyUtil.convertToString(dto);
            });

            ResourceDescription sharedWithMe = getShareResource(ResourceType.SHARED_WITH_ME, resourceType, bucket, location);
            resourceService.computeResource(sharedWithMe, state -> {
                ResourceLinkCollection collection = ProxyUtil.convertToObject(state, ResourceLinkCollection.class);
                if (collection == null) {
                    collection = new ResourceLinkCollection(new HashSet<>());
                }

                // add all links to the user
                collection.getResources().addAll(links);

                return ProxyUtil.convertToString(collection);
            });
        }
    }

    public boolean hasReadAccess(String bucket, String location, ResourceDescription resource) {
        ResourceDescription shareResource = getShareResource(ResourceType.SHARED_WITH_ME, resource.getType(), bucket, location);

        String state = resourceService.getResource(shareResource);
        ResourceLinkCollection sharedResources = ProxyUtil.convertToObject(state, ResourceLinkCollection.class);
        if (sharedResources == null) {
            log.debug("No state found for share access");
            return false;
        }

        Set<ResourceLink> sharedLinks = sharedResources.getResources();
        if (sharedLinks.contains(new ResourceLink(resource.getUrl()))) {
            return true;
        }

        // check if you have shared access to the parent folder
        ResourceDescription parentFolder = resource.getParent();
        while (parentFolder != null) {
            if (sharedLinks.contains(new ResourceLink(parentFolder.getUrl()))) {
                return true;
            }
            parentFolder = parentFolder.getParent();
        }

        return false;
    }

    /**
     * Revoke share access for provided resources. Only resource owner can perform this operation
     *
     * @param bucket - user bucket
     * @param location - storage location
     * @param request - collection of links to revoke access
     */
    public void revokeSharedAccess(String bucket, String location, ResourceLinkCollection request) {
        Set<ResourceLink> resourceLinks = request.getResources();
        if (resourceLinks.isEmpty()) {
            throw new IllegalArgumentException("No resources provided");
        }

        // validate that all resources belong to the user, who perform this action
        Set<ResourceDescription> resources = new HashSet<>();
        for (ResourceLink link : resourceLinks) {
            ResourceDescription resource = getResourceFromLink(link.url());
            if (!resource.getBucketName().equals(bucket)) {
                throw new IllegalArgumentException("You are only allowed to revoke access from own resources");
            }
            resources.add(resource);
        }

        for (ResourceDescription resource : resources) {
            ResourceType resourceType = resource.getType();
            String resourceUrl = resource.getUrl();
            ResourceDescription sharedByMeResource = getShareResource(ResourceType.SHARED_BY_ME, resourceType, bucket, location);
            String state = resourceService.getResource(sharedByMeResource);
            SharedByMeDto dto = ProxyUtil.convertToObject(state, SharedByMeDto.class);
            if (dto != null) {
                Set<String> userLocations = dto.getResourceToUsers().get(resourceUrl);

                // if userLocations is NULL - this means that provided resource wasn't shared
                if (userLocations == null) {
                    continue;
                }

                for (String userLocation : userLocations) {
                    String userBucket = encryptionService.encrypt(userLocation);
                    removeSharedResource(userBucket, userLocation, resourceUrl, resourceType);
                }

                resourceService.computeResource(sharedByMeResource, ownerState -> {
                    SharedByMeDto sharedByMeDto = ProxyUtil.convertToObject(state, SharedByMeDto.class);
                    if (sharedByMeDto != null) {
                        sharedByMeDto.getResourceToUsers().remove(resourceUrl);
                    }

                    return ProxyUtil.convertToString(sharedByMeDto);
                });
            }
        }
    }

    public void discardSharedAccess(String bucket, String location, ResourceLinkCollection request) {
        Set<ResourceLink> resourceLinks = request.getResources();
        if (resourceLinks.isEmpty()) {
            throw new IllegalArgumentException("No resources provided");
        }

        Set<ResourceDescription> resources = new HashSet<>();
        for (ResourceLink link : resourceLinks) {
            resources.add(getResourceFromLink(link.url()));
        }

        for (ResourceDescription resource : resources) {
            ResourceType resourceType = resource.getType();
            String resourceUrl = resource.getUrl();
            removeSharedResource(bucket, location, resourceUrl, resourceType);

            String ownerBucket = resource.getBucketName();
            String ownerLocation = encryptionService.decrypt(ownerBucket);

            ResourceDescription sharedWithMe = getShareResource(ResourceType.SHARED_BY_ME, resourceType, ownerBucket, ownerLocation);
            resourceService.computeResource(sharedWithMe, ownerState -> {
                SharedByMeDto sharedByMeDto = ProxyUtil.convertToObject(ownerState, SharedByMeDto.class);
                if (sharedByMeDto != null) {
                    Set<String> userLocations = sharedByMeDto.getResourceToUsers().get(resourceUrl);
                    // if userLocations is NULL - this means that provided resource wasn't shared
                    if (userLocations != null) {
                        userLocations.remove(location);

                        // clean up shared resource
                        if (userLocations.isEmpty()) {
                            sharedByMeDto.getResourceToUsers().remove(resourceUrl);
                        }
                    }
                }

                return ProxyUtil.convertToString(sharedByMeDto);
            });
        }
    }

    public void copySharedAccess(String bucket, String location, ResourceDescription source, ResourceDescription destination) {
        if (!hasResource(source)) {
            throw new IllegalArgumentException("source resource %s does not exists".formatted(source.getUrl()));
        }

        if (!hasResource(destination)) {
            throw new IllegalArgumentException("destination resource %s dos not exists".formatted(destination.getUrl()));
        }

        ResourceType sourceResourceType = source.getType();
        ResourceDescription sharedByMeResource = getShareResource(ResourceType.SHARED_BY_ME, sourceResourceType, bucket, location);
        SharedByMeDto sharedByMeDto = ProxyUtil.convertToObject(resourceService.getResource(sharedByMeResource), SharedByMeDto.class);
        if (sharedByMeDto == null) {
            return;
        }

        Set<String> userLocations = sharedByMeDto.getResourceToUsers().get(source.getUrl());

        ResourceType destinationResourceType = destination.getType();
        String destinationResourceLink = destination.getUrl();
        // source and destination resource type might be different
        sharedByMeResource = getShareResource(ResourceType.SHARED_BY_ME, destinationResourceType, bucket, location);

        // copy user locations from source to destination
        resourceService.computeResource(sharedByMeResource, state -> {
            SharedByMeDto dto = ProxyUtil.convertToObject(state, SharedByMeDto.class);
            if (dto == null) {
                dto = new SharedByMeDto(new HashMap<>());
            }

            // add shared access to the destination resource
            dto.addUsersToResource(destinationResourceLink, userLocations);

            return ProxyUtil.convertToString(dto);
        });

        // add each user shared access to the destination resource
        for (String userLocation : userLocations) {
            String userBucket = encryptionService.encrypt(userLocation);
            addSharedResource(userBucket, userLocation, destinationResourceLink, destinationResourceType);
        }
    }

    public void moveSharedAccess(String bucket, String location, ResourceDescription source, ResourceDescription destination) {
        // copy shared access from source to destination
        copySharedAccess(bucket, location, source, destination);
        // revoke shared access from source
        revokeSharedAccess(bucket, location, new ResourceLinkCollection(Set.of(new ResourceLink(source.getUrl()))));
    }

    private void removeSharedResource(String bucket, String location, String link, ResourceType resourceType) {
        ResourceDescription sharedByMeResource = getShareResource(ResourceType.SHARED_WITH_ME, resourceType, bucket, location);
        resourceService.computeResource(sharedByMeResource, state -> {
            ResourceLinkCollection sharedWithMe = ProxyUtil.convertToObject(state, ResourceLinkCollection.class);
            if (sharedWithMe != null) {
                sharedWithMe.getResources().remove(new ResourceLink(link));
            }

            return ProxyUtil.convertToString(sharedWithMe);
        });
    }

    private void addSharedResource(String bucket, String location, String link, ResourceType resourceType) {
        ResourceDescription sharedByMeResource = getShareResource(ResourceType.SHARED_WITH_ME, resourceType, bucket, location);
        resourceService.computeResource(sharedByMeResource, state -> {
            ResourceLinkCollection sharedWithMe = ProxyUtil.convertToObject(state, ResourceLinkCollection.class);
            if (sharedWithMe == null) {
                sharedWithMe = new ResourceLinkCollection(new HashSet<>());
            }

            sharedWithMe.getResources().add(new ResourceLink(link));

            return ProxyUtil.convertToString(sharedWithMe);
        });
    }

    private List<MetadataBase> linksToMetadata(Stream<String> links) {
        return links
                .map(link -> ResourceDescription.fromPrivateUrl(link, encryptionService))
                .map(resource -> {
                    if (resource.isFolder()) {
                        return new ResourceFolderMetadata(resource);
                    } else {
                        return new ResourceItemMetadata(resource);
                    }
                }).toList();
    }

    private ResourceDescription getResourceFromLink(String url) {
        try {
            return ResourceDescription.fromPrivateUrl(url, encryptionService);
        } catch (Exception e) {
            throw new IllegalArgumentException("Incorrect resource link provided " + url);
        }
    }

    private boolean hasResource(ResourceDescription resource) {
        return ResourceUtil.hasResource(resource, resourceService, storage);
    }

    private ResourceDescription getShareResource(ResourceType shareResourceType, ResourceType requestedResourceType, String bucket, String location) {
        return ResourceDescription.fromDecoded(shareResourceType, bucket, location,
                requestedResourceType.getGroup() + BlobStorageUtil.PATH_SEPARATOR + SHARE_RESOURCE_FILENAME);
    }
}
