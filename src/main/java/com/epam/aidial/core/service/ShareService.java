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
import java.util.Collection;
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
                Map<String, Set<ResourceAccessType>> links = sharedResources.getResources().stream()
                                .collect(Collectors.toUnmodifiableMap(
                                        SharedResource::url, SharedResource::permissions, Sets::union));
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
                Map<String, Set<ResourceAccessType>> links =
                        resourceToUsers.getResourcesWithPermissions().entrySet().stream()
                                .collect(Collectors.toUnmodifiableMap(
                                        Map.Entry::getKey,
                                        // Directly assigned (non-inherited) aggregated permissions
                                        entry -> entry.getValue().values().stream()
                                                .flatMap(Collection::stream)
                                                .collect(Collectors.toUnmodifiableSet())));
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

        Set<SharedResource> normalizedResourceLinks = new HashSet<>();
        for (SharedResource sharedResource : sharedResources) {
            String url = sharedResource.url();
            if (url.startsWith(ProxyUtil.METADATA_PREFIX)) {
                url = url.substring(ProxyUtil.METADATA_PREFIX.length());
            }
            ResourceDescription resource = getResourceFromLink(url);
            if (!bucket.equals(resource.getBucketName())) {
                throw new IllegalArgumentException("Resource %s does not belong to the user".formatted(url));
            }
            normalizedResourceLinks.add(sharedResource.permissions() == null
                    ? new SharedResource(resource.getUrl(), Set.of(ResourceAccessType.READ))
                    : sharedResource.withUrl(resource.getUrl()));
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

        Set<SharedResource> resourceLinks = invitation.getResources();
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
                Map<String, Set<ResourceAccessType>> existingPermissions = new HashMap<>();
                for (SharedResource sharedResource : sharedResources.getResources()) {
                    Set<ResourceAccessType> permissions = existingPermissions
                            .computeIfAbsent(sharedResource.url(), k -> EnumSet.noneOf(ResourceAccessType.class));
                    permissions.addAll(sharedResource.permissions());
                }

                // add all links to the user
                for (SharedResource sharedResource : links) {
                    Set<ResourceAccessType> permissions = existingPermissions
                            .computeIfAbsent(sharedResource.url(), k -> EnumSet.noneOf(ResourceAccessType.class));
                    permissions.addAll(sharedResource.permissions());
                }
                sharedResources.setResources(existingPermissions.entrySet().stream()
                        .map(entry -> new SharedResource(entry.getKey(), entry.getValue()))
                        .toList());

                return ProxyUtil.convertToString(sharedResources);
            });
        });
    }

    public Set<ResourceAccessType> getPermissions(String bucket, String location, ResourceDescription resource) {
        ResourceDescription shareResource = getShareResource(ResourceType.SHARED_WITH_ME, resource.getType(), bucket, location);

        String state = resourceService.getResource(shareResource);
        SharedResources sharedResources = ProxyUtil.convertToObject(state, SharedResources.class);
        if (sharedResources == null) {
            log.debug("No state found for share access");
            return Set.of();
        }

        // check if you have shared access to the parent folder
        Set<ResourceAccessType> result = EnumSet.noneOf(ResourceAccessType.class);
        ResourceDescription next = resource;
        while (next != null) {
            result.addAll(sharedResources.lookupPermissions(next.getUrl()));
            next = next.getParent();
        }

        return result;
    }

    /**
     * Revoke share access for provided resources. Only resource owner can perform this operation
     *
     * @param bucket - user bucket
     * @param location - storage location
     * @param permissionsToRevoke - collection of links and permissions to revoke access
     */
    public void revokeSharedAccess(
            String bucket, String location, Map<String, Set<ResourceAccessType>> permissionsToRevoke) {
        if (permissionsToRevoke.isEmpty()) {
            throw new IllegalArgumentException("No resources provided");
        }

        // validate that all resources belong to the user, who perform this action
        Map<ResourceDescription, Set<ResourceAccessType>> resources = new HashMap<>();
        permissionsToRevoke.forEach((link, permissions) -> {
            ResourceDescription resource = getResourceFromLink(link);
            if (!resource.getBucketName().equals(bucket)) {
                throw new IllegalArgumentException("You are only allowed to revoke access from own resources");
            }
            resources.put(resource, permissions);
        });

        resources.forEach((resource, permissionsToRemove) -> {
            ResourceType resourceType = resource.getType();
            String resourceUrl = resource.getUrl();
            ResourceDescription sharedByMeResource = getShareResource(ResourceType.SHARED_BY_ME, resourceType, bucket, location);
            String state = resourceService.getResource(sharedByMeResource);
            SharedByMeDto dto = ProxyUtil.convertToObject(state, SharedByMeDto.class);
            if (dto != null) {
                Map<String, Set<ResourceAccessType>> userLocations = dto.getResourcesWithPermissions().get(resourceUrl);

                // if userLocations is NULL - this means that provided resource wasn't shared
                if (userLocations == null) {
                    return;
                }

                userLocations.keySet().forEach(user -> {
                    String userBucket = encryptionService.encrypt(user);
                    removeSharedResourcePermissions(userBucket, user, resourceUrl, resourceType, permissionsToRemove);
                });

                resourceService.computeResource(sharedByMeResource, ownerState -> {
                    SharedByMeDto sharedByMeDto = ProxyUtil.convertToObject(state, SharedByMeDto.class);
                    if (sharedByMeDto != null) {
                        if (permissionsToRemove.contains(ResourceAccessType.READ)) {
                            sharedByMeDto.getReadableResourceToUsers().remove(resourceUrl);
                        }
                        Map<String, Set<ResourceAccessType>> userPermissions =
                                sharedByMeDto.getResourcesWithPermissions().get(resourceUrl);
                        if (userPermissions != null) {
                            Set<String> usersToRemove = new HashSet<>();
                            userPermissions.forEach((user, permissions) -> {
                                permissions.removeAll(permissionsToRemove);
                                if (permissions.isEmpty()) {
                                    usersToRemove.add(user);
                                }
                            });
                            usersToRemove.forEach(userPermissions::remove);
                            if (userPermissions.isEmpty()) {
                                sharedByMeDto.getResourcesWithPermissions().remove(resourceUrl);
                            }
                        }
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
                    Set<String> userLocations = sharedByMeDto.getReadableResourceToUsers().get(resourceUrl);
                    // if userLocations is NULL - this means that provided resource wasn't shared
                    if (userLocations != null) {
                        userLocations.remove(location);

                        // clean up shared resource
                        if (userLocations.isEmpty()) {
                            sharedByMeDto.getReadableResourceToUsers().remove(resourceUrl);
                        }
                    }

                    Map<String, Set<ResourceAccessType>> permissions =
                            sharedByMeDto.getResourcesWithPermissions().get(resourceUrl);
                    if (permissions != null) {
                        permissions.remove(location);

                        if (permissions.isEmpty()) {
                            sharedByMeDto.getResourcesWithPermissions().remove(resourceUrl);
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

        Map<String, Set<ResourceAccessType>> userPermissions = sharedByMeDto.getResourcesWithPermissions() != null
                ? new HashMap<>(
                        sharedByMeDto.getResourcesWithPermissions().getOrDefault(source.getUrl(), Map.of()))
                : new HashMap<>();
        for (String user : sharedByMeDto.getReadableResourceToUsers().get(source.getUrl())) {
            Set<ResourceAccessType> otherPermissions =
                    userPermissions.computeIfAbsent(user, k -> EnumSet.noneOf(ResourceAccessType.class));
            otherPermissions.add(ResourceAccessType.READ);
        }

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
            dto.addUsersToResource(destinationResourceLink, userPermissions);

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
        revokeSharedAccess(bucket, location, Map.of(source.getUrl(), ResourceAccessType.ALL));
    }

    private void removeSharedResourcePermissions(
            String bucket, String location, String link, ResourceType resourceType, Set<ResourceAccessType> permissionsToRemove) {
        ResourceDescription sharedByMeResource = getShareResource(ResourceType.SHARED_WITH_ME, resourceType, bucket, location);
        resourceService.computeResource(sharedByMeResource, state -> {
            SharedResources sharedWithMe = ProxyUtil.convertToObject(state, SharedResources.class);
            if (sharedWithMe != null) {
                Set<ResourceAccessType> permissions = sharedWithMe.lookupPermissions(link);
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
            Set<ResourceAccessType> newPermissions) {
        ResourceDescription sharedByMeResource = getShareResource(ResourceType.SHARED_WITH_ME, resourceType, bucket, location);
        resourceService.computeResource(sharedByMeResource, state -> {
            SharedResources sharedWithMe = ProxyUtil.convertToObject(state, SharedResources.class);
            if (sharedWithMe == null) {
                sharedWithMe = new SharedResources(new ArrayList<>());
            }
            Set<ResourceAccessType> permissions = sharedWithMe.lookupPermissions(link);
            permissions.addAll(newPermissions);
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
                    if (resource.isFolder()) {
                        return new ResourceFolderMetadata(resource, permissions);
                    } else {
                        return new ResourceItemMetadata(resource, permissions);
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
