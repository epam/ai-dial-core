package com.epam.aidial.core.service;

import com.epam.aidial.core.data.Invitation;
import com.epam.aidial.core.data.InvitationLink;
import com.epam.aidial.core.data.ListSharedResourcesRequest;
import com.epam.aidial.core.data.MetadataBase;
import com.epam.aidial.core.data.ResourceAccessType;
import com.epam.aidial.core.data.ResourceFolderMetadata;
import com.epam.aidial.core.data.ResourceItemMetadata;
import com.epam.aidial.core.data.ResourceLink;
import com.epam.aidial.core.data.ResourceLinkCollection;
import com.epam.aidial.core.data.ResourceType;
import com.epam.aidial.core.data.ShareResourcesRequest;
import com.epam.aidial.core.data.SharedByMeDto;
import com.epam.aidial.core.data.SharedResource;
import com.epam.aidial.core.data.SharedResources;
import com.epam.aidial.core.data.SharedResourcesResponse;
import com.epam.aidial.core.security.EncryptionService;
import com.epam.aidial.core.storage.BlobStorage;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.ProxyUtil;
import com.epam.aidial.core.util.ResourceUtil;
import com.google.common.collect.Sets;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


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
            SharedResources sharedResources = ProxyUtil.convertToObject(sharedResource, SharedResources.class);
            if (sharedResources != null) {
                Map<String, Set<ResourceAccessType>> links = ResourceUtil.sharedResourcesToMap(sharedResources.getResources());
                resultMetadata.addAll(linksToMetadata(links));
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
            if (resourceToUsers != null) {
                Map<String, Set<ResourceAccessType>> links = resourceToUsers.getAggregatedPermissions();
                resultMetadata.addAll(linksToMetadata(links));
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
        Set<SharedResource> sharedResources = request.getResources();
        if (sharedResources.isEmpty()) {
            throw new IllegalArgumentException("No resources provided");
        }

        Set<String> uniqueLinks = new HashSet<>();
        List<SharedResource> normalizedResourceLinks = new ArrayList<>(sharedResources.size());
        for (SharedResource sharedResource : sharedResources) {
            ResourceDescription resource = getResourceFromLink(sharedResource.url());
            if (!bucket.equals(resource.getBucketName())) {
                throw new IllegalArgumentException("Resource %s does not belong to the user".formatted(resource.getUrl()));
            }
            if (!uniqueLinks.add(resource.getUrl())) {
                throw new IllegalArgumentException("Duplicated resource: %s".formatted(resource.getUrl()));
            }
            normalizedResourceLinks.add(sharedResource.withUrl(resource.getUrl()));
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

        List<SharedResource> resourceLinks = invitation.getResources();
        for (SharedResource link : resourceLinks) {
            String url = link.url();
            if (ResourceDescription.fromPrivateUrl(url, encryptionService).getBucketName().equals(bucket)) {
                throw new IllegalArgumentException("Resource %s already belong to you".formatted(url));
            }
        }

        // group resources with the same type to reduce resource transformations
        Map<ResourceType, List<SharedResource>> resourceGroups = resourceLinks.stream()
                .collect(Collectors.groupingBy(sharedResource -> ResourceUtil.getResourceType(sharedResource.url())));

        resourceGroups.forEach((resourceType, links) -> {
            String ownerBucket = ResourceUtil.getBucket(links.get(0).url());
            String ownerLocation = encryptionService.decrypt(ownerBucket);

            // write user location to the resource owner
            ResourceDescription sharedByMe = getShareResource(ResourceType.SHARED_BY_ME, resourceType, ownerBucket, ownerLocation);
            resourceService.computeResource(sharedByMe, state -> {
                SharedByMeDto dto = ProxyUtil.convertToObject(state, SharedByMeDto.class);
                if (dto == null) {
                    dto = new SharedByMeDto(new HashMap<>(), new HashMap<>());
                }

                // add user location for each link
                for (SharedResource resource : links) {
                    dto.addUserToResource(resource, location);
                }

                return ProxyUtil.convertToString(dto);
            });

            ResourceDescription sharedWithMe = getShareResource(ResourceType.SHARED_WITH_ME, resourceType, bucket, location);
            resourceService.computeResource(sharedWithMe, state -> {
                SharedResources sharedResources = ProxyUtil.convertToObject(state, SharedResources.class);
                if (sharedResources == null) {
                    sharedResources = new SharedResources(new ArrayList<>());
                }

                // add all links to the user
                sharedResources.addSharedResources(ResourceUtil.sharedResourcesToMap(links));

                return ProxyUtil.convertToString(sharedResources);
            });
        });
    }

    public Map<ResourceDescription, Set<ResourceAccessType>> getPermissions(
            String bucket, String location, Set<ResourceDescription> allResources) {
        Map<ResourceType, List<ResourceDescription>> privateResourcesByTypes = allResources.stream()
                .filter(ResourceDescription::isPrivate)
                .collect(Collectors.groupingBy(ResourceDescription::getType));
        Map<ResourceDescription, Set<ResourceAccessType>> result = new HashMap<>();
        privateResourcesByTypes.forEach((type, resources) -> {
            ResourceDescription shareResource = getShareResource(ResourceType.SHARED_WITH_ME, type, bucket, location);

            String state = resourceService.getResource(shareResource);
            SharedResources sharedResources = ProxyUtil.convertToObject(state, SharedResources.class);
            if (sharedResources == null) {
                log.debug("No state found for share access");
                return;
            }

            Map<String, Set<ResourceAccessType>> resourcePermissions =
                    ResourceUtil.sharedResourcesToMap(sharedResources.getResources());
            for (ResourceDescription resource : resources) {
                result.put(resource, lookupPermissions(resource, resourcePermissions, new HashMap<>()));
            }
        });

        return result;
    }

    private static Set<ResourceAccessType> lookupPermissions(
            ResourceDescription resource,
            Map<String, Set<ResourceAccessType>> resourcePermissions,
            Map<ResourceDescription, Set<ResourceAccessType>> cache) {
        if (resource == null) {
            return Set.of();
        }

        Set<ResourceAccessType> permissions = cache.get(resource);
        if (permissions == null) {
            permissions = Sets.union(
                    resourcePermissions.getOrDefault(resource.getUrl(), Set.of()),
                    lookupPermissions(resource.getParent(), resourcePermissions, cache));
            cache.put(resource, permissions);
        }

        return permissions;
    }

    /**
     * Revoke share access for provided resource. Only resource owner can perform this operation
     *
     * @param bucket - user bucket
     * @param location - storage location
     * @param resourceLink - the resource to revoke access
     */
    public void revokeSharedResource(
            String bucket, String location, ResourceDescription resourceLink) {
        revokeSharedAccess(bucket, location, Map.of(resourceLink, ResourceAccessType.ALL));
    }

    /**
     * Revoke share access for provided resources. Only resource owner can perform this operation
     *
     * @param bucket - user bucket
     * @param location - storage location
     * @param permissionsToRevoke - collection of resources and permissions to revoke access
     */
    public void revokeSharedAccess(
            String bucket, String location, Map<ResourceDescription, Set<ResourceAccessType>> permissionsToRevoke) {
        if (permissionsToRevoke.isEmpty()) {
            throw new IllegalArgumentException("No resources provided");
        }

        // validate that all resources belong to the user, who perform this action
        permissionsToRevoke.forEach((resource, permissions) -> {
            if (!resource.getBucketName().equals(bucket)) {
                throw new IllegalArgumentException("You are only allowed to revoke access from own resources");
            }
        });

        permissionsToRevoke.forEach((resource, permissionsToRemove) -> {
            ResourceType resourceType = resource.getType();
            String resourceUrl = resource.getUrl();
            ResourceDescription sharedByMeResource = getShareResource(ResourceType.SHARED_BY_ME, resourceType, bucket, location);
            String state = resourceService.getResource(sharedByMeResource);
            SharedByMeDto dto = ProxyUtil.convertToObject(state, SharedByMeDto.class);
            if (dto != null) {
                Set<String> userLocations = dto.collectUsersForPermissions(resourceUrl, permissionsToRemove);

                // if userLocations is empty - this means that provided resource wasn't shared
                if (userLocations.isEmpty()) {
                    return;
                }

                userLocations.forEach(user -> {
                    String userBucket = encryptionService.encrypt(user);
                    removeSharedResourcePermissions(userBucket, user, resourceUrl, resourceType, permissionsToRemove);
                });

                resourceService.computeResource(sharedByMeResource, ownerState -> {
                    SharedByMeDto sharedByMeDto = ProxyUtil.convertToObject(state, SharedByMeDto.class);
                    if (sharedByMeDto != null) {
                        sharedByMeDto.removePermissionsFromResource(resourceUrl, permissionsToRemove);
                    }

                    return ProxyUtil.convertToString(sharedByMeDto);
                });
            }
        });
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
            removeSharedResourcePermissions(bucket, location, resourceUrl, resourceType, ResourceAccessType.ALL);

            String ownerBucket = resource.getBucketName();
            String ownerLocation = encryptionService.decrypt(ownerBucket);

            ResourceDescription sharedWithMe = getShareResource(ResourceType.SHARED_BY_ME, resourceType, ownerBucket, ownerLocation);
            resourceService.computeResource(sharedWithMe, ownerState -> {
                SharedByMeDto sharedByMeDto = ProxyUtil.convertToObject(ownerState, SharedByMeDto.class);
                if (sharedByMeDto != null) {
                    sharedByMeDto.removeUserFromResource(resourceUrl, location);
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

        Map<String, Set<ResourceAccessType>> userPermissions = sharedByMeDto.getUserPermissions(source.getUrl());

        ResourceType destinationResourceType = destination.getType();
        String destinationResourceLink = destination.getUrl();
        // source and destination resource type might be different
        sharedByMeResource = getShareResource(ResourceType.SHARED_BY_ME, destinationResourceType, bucket, location);

        // copy user locations from source to destination
        resourceService.computeResource(sharedByMeResource, state -> {
            SharedByMeDto dto = ProxyUtil.convertToObject(state, SharedByMeDto.class);
            if (dto == null) {
                dto = new SharedByMeDto(new HashMap<>(), new HashMap<>());
            }

            // add shared access to the destination resource
            dto.addUserPermissionsToResource(destinationResourceLink, userPermissions);

            return ProxyUtil.convertToString(dto);
        });

        // add each user shared access to the destination resource
        userPermissions.forEach((userLocation, permissions) -> {
            String userBucket = encryptionService.encrypt(userLocation);
            addSharedResource(userBucket, userLocation, destinationResourceLink, destinationResourceType, permissions);
        });
    }

    public void moveSharedAccess(String bucket, String location, ResourceDescription source, ResourceDescription destination) {
        // copy shared access from source to destination
        copySharedAccess(bucket, location, source, destination);
        // revoke shared access from source
        revokeSharedAccess(bucket, location, Map.of(source, ResourceAccessType.ALL));
    }

    private void removeSharedResourcePermissions(
            String bucket, String location, String link, ResourceType resourceType, Set<ResourceAccessType> permissionsToRemove) {
        ResourceDescription sharedByMeResource = getShareResource(ResourceType.SHARED_WITH_ME, resourceType, bucket, location);
        resourceService.computeResource(sharedByMeResource, state -> {
            SharedResources sharedWithMe = ProxyUtil.convertToObject(state, SharedResources.class);
            if (sharedWithMe != null) {
                Set<ResourceAccessType> permissions = EnumSet.noneOf(ResourceAccessType.class);
                permissions.addAll(sharedWithMe.findPermissions(link));
                permissions.removeAll(permissionsToRemove);
                sharedWithMe.getResources().removeIf(resource -> link.equals(resource.url()));
                if (!permissions.isEmpty()) {
                    sharedWithMe.getResources().add(new SharedResource(link, permissions));
                }
            }

            return ProxyUtil.convertToString(sharedWithMe);
        });
    }

    private void addSharedResource(
            String bucket,
            String location,
            String link,
            ResourceType resourceType,
            Set<ResourceAccessType> permissionsToAdd) {
        ResourceDescription sharedByMeResource = getShareResource(ResourceType.SHARED_WITH_ME, resourceType, bucket, location);
        resourceService.computeResource(sharedByMeResource, state -> {
            SharedResources sharedWithMe = ProxyUtil.convertToObject(state, SharedResources.class);
            if (sharedWithMe == null) {
                sharedWithMe = new SharedResources(new ArrayList<>());
            }
            Set<ResourceAccessType> permissions = EnumSet.noneOf(ResourceAccessType.class);
            permissions.addAll(sharedWithMe.findPermissions(link));
            permissions.addAll(permissionsToAdd);
            sharedWithMe.getResources().removeIf(resource -> link.equals(resource.url()));
            sharedWithMe.getResources().add(new SharedResource(link, permissions));

            return ProxyUtil.convertToString(sharedWithMe);
        });
    }

    private List<MetadataBase> linksToMetadata(Map<String, Set<ResourceAccessType>> links) {
        return links.entrySet().stream()
                .map(entry -> {
                    String link = entry.getKey();
                    Set<ResourceAccessType> permissions = entry.getValue();
                    ResourceDescription resource = ResourceDescription.fromPrivateUrl(link, encryptionService);
                    MetadataBase metadata = resource.isFolder()
                            ? new ResourceFolderMetadata(resource)
                            : new ResourceItemMetadata(resource);
                    metadata.setPermissions(permissions);
                    return metadata;
                }).toList();
    }

    private ResourceDescription getResourceFromLink(String url) {
        return ResourceUtil.resourceFromUrl(url, encryptionService);
    }

    private boolean hasResource(ResourceDescription resource) {
        return ResourceUtil.hasResource(resource, resourceService, storage);
    }

    private ResourceDescription getShareResource(ResourceType shareResourceType, ResourceType requestedResourceType, String bucket, String location) {
        return ResourceDescription.fromDecoded(shareResourceType, bucket, location,
                requestedResourceType.getGroup() + BlobStorageUtil.PATH_SEPARATOR + SHARE_RESOURCE_FILENAME);
    }
}
