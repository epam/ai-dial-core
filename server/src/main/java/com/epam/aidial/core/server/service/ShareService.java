package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.config.Role;
import com.epam.aidial.core.config.ShareResourceLimit;
import com.epam.aidial.core.credentials.data.credentials.CredentialsDescriptor;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;
import com.epam.aidial.core.credentials.service.ResourceCredentialsService;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.Invitation;
import com.epam.aidial.core.server.data.InvitationLink;
import com.epam.aidial.core.server.data.ListSharedResourcesRequest;
import com.epam.aidial.core.server.data.ResourceLink;
import com.epam.aidial.core.server.data.ResourceLinkCollection;
import com.epam.aidial.core.server.data.ShareResourcesRequest;
import com.epam.aidial.core.server.data.SharedByMeDto;
import com.epam.aidial.core.server.data.SharedResource;
import com.epam.aidial.core.server.data.SharedResources;
import com.epam.aidial.core.server.data.SharedResourcesResponse;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.server.util.CredentialsLocatorFactory;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.data.MetadataBase;
import com.epam.aidial.core.storage.data.ResourceFolderMetadata;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.data.ShareMetadata;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceType;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.google.common.collect.Sets;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;


@Slf4j
@AllArgsConstructor
public class ShareService {

    private static final String SHARE_RESOURCE_FILENAME = "share";

    private final ResourceService resourceService;
    private final InvitationService invitationService;
    private final EncryptionService encryptionService;
    private final ApplicationService applicationService;
    private final LockService lockService;
    private final ApplicationSchemaService applicationSchemaService;
    private final LongSupplier clock;
    private final ResourceCredentialsService resourceCredentialsService;

    private static final Map<ResourceType, ShareResourceLimit> DEFAULT_LIMITS = Map.of(
            ResourceTypes.APPLICATION, new ShareResourceLimit(10, 72),
            ResourceTypes.CONVERSATION, new ShareResourceLimit(Integer.MAX_VALUE, 72),
            ResourceTypes.FILE, new ShareResourceLimit(Integer.MAX_VALUE, 72),
            ResourceTypes.PROMPT, new ShareResourceLimit(Integer.MAX_VALUE, 72),
            ResourceTypes.TOOL_SET, new ShareResourceLimit(10, 72),
            ResourceTypes.CREDENTIALS, new ShareResourceLimit(10, 72),
            ResourceTypes.SKILL, new ShareResourceLimit(10, 72));

    private static final Set<ResourceType> CREDS_SHARABLE_RESOURCE_TYPES = Set.of(ResourceTypes.TOOL_SET);

    /**
     * Returns a list of resources shared with user.
     *
     * @param bucket   - user bucket
     * @param location - storage location
     * @param request  - request body
     * @return list of shared with user resources
     */
    public SharedResourcesResponse listSharedWithMe(String bucket, String location, ListSharedResourcesRequest request) {
        Set<ResourceTypes> requestedResourceType = request.getResourceTypes();

        Set<ResourceDescriptor> shareResources = new HashSet<>();
        for (ResourceType resourceType : requestedResourceType) {
            ResourceDescriptor sharedResource = getShareResource(ResourceTypes.SHARED_WITH_ME, resourceType, bucket, location);
            shareResources.add(sharedResource);
        }

        Set<MetadataBase> resultMetadata = new HashSet<>();
        for (ResourceDescriptor resource : shareResources) {
            String state = resourceService.getResource(resource);
            SharedResources sharedResources = ProxyUtil.convertToObject(state, SharedResources.class);
            if (sharedResources != null) {
                for (SharedResource sharedResource : sharedResources.getResources()) {
                    ResourceDescriptor sharedResourceDescriptor = ResourceDescriptorFactory.fromPrivateUrl(sharedResource.getUrl(), encryptionService);
                    MetadataBase metadata = sharedResourceDescriptor.isFolder()
                            ? new ResourceFolderMetadata(sharedResourceDescriptor)
                            : new ResourceItemMetadata(sharedResourceDescriptor);
                    metadata.setPermissions(sharedResource.getPermissions());
                    if (request.isIncludeUserInfo()) {
                        if (metadata instanceof ResourceItemMetadata itemMetadata) {
                            itemMetadata.setAuthor(sharedResource.getAuthor());
                        }
                        metadata.setSharedBy(sharedResource.getSharedBy());
                    }
                    resultMetadata.add(metadata);
                }
            }
        }

        return new SharedResourcesResponse(resultMetadata);
    }

    /**
     * Returns list of resources shared by user.
     *
     * @param bucket   - user bucket
     * @param location - storage location
     * @param request  - request body
     * @return list of shared with user resources
     */
    public SharedResourcesResponse listSharedByMe(String bucket, String location, ListSharedResourcesRequest request) {
        Set<ResourceTypes> requestedResourceTypes = request.getResourceTypes();

        Set<ResourceDescriptor> shareResources = new HashSet<>();
        for (ResourceType resourceType : requestedResourceTypes) {
            ResourceDescriptor shareResource = getShareResource(ResourceTypes.SHARED_BY_ME, resourceType, bucket, location);
            shareResources.add(shareResource);
        }

        Set<MetadataBase> resultMetadata = new HashSet<>();
        for (ResourceDescriptor resource : shareResources) {
            String sharedResource = resourceService.getResource(resource);
            SharedByMeDto resourceToUsers = ProxyUtil.convertToObject(sharedResource, SharedByMeDto.class);
            if (resourceToUsers != null) {
                resultMetadata.addAll(toMetadata(resourceToUsers, request.isIncludeUserInfo()));
            }
        }

        return new SharedResourcesResponse(resultMetadata);
    }


    private void addCustomApplicationRelatedFiles(String bucket, ShareResourcesRequest request) {
        List<String> filesFromRequest = request.getResources().stream()
                .map(SharedResource::getUrl).toList();
        Set<SharedResource> newSharedResources = new HashSet<>(request.getResources());
        for (SharedResource sharedResource : request.getResources()) {
            ResourceDescriptor resource = getResourceFromLink(sharedResource.getUrl());
            if (resource.getType() == ResourceTypes.APPLICATION) {
                Application application = applicationService.getApplication(resource).getValue();
                List<ResourceDescriptor> files = applicationSchemaService.getFiles(application);
                for (ResourceDescriptor file : files) {
                    if (file.isPublic() || !file.getBucketName().equals(bucket)) {
                        throw new IllegalArgumentException("All files in the application %s should belong to a requester".formatted(resource.getUrl()));
                    }
                    if (!filesFromRequest.contains(file.getUrl())) {
                        newSharedResources.add(new SharedResource(file.getUrl(), null, null, sharedResource.getPermissions()));
                    }
                }
            }
        }
        request.setResources(newSharedResources);
    }

    /**
     * Initialize share request by creating invitation object
     *
     * @param request  - request body
     * @return invitation link
     */
    public InvitationLink initializeShare(ProxyContext context, ShareResourcesRequest request) {
        validateShareResourcesRequest(request);
        String bucketLocation = BucketBuilder.buildInitiatorBucket(context);
        String bucket = encryptionService.encrypt(bucketLocation);

        // validate resources - owner must be current user
        addCustomApplicationRelatedFiles(bucket, request);
        addCredentialsSharing(context, request);

        Set<SharedResource> sharedResources = request.getResources();
        Set<String> uniqueLinks = new HashSet<>();
        List<SharedResource> normalizedResourceLinks = new ArrayList<>(sharedResources.size());
        Map<ResourceType, List<SharedResource>> resourceGroups = sharedResources.stream()
                .collect(Collectors.groupingBy(sharedResource -> getResourceFromLink(sharedResource.getUrl()).getType()));

        long invitationTtl = Long.MAX_VALUE;
        for (Map.Entry<ResourceType, List<SharedResource>> entry : resourceGroups.entrySet()) {
            ResourceType resourceType = entry.getKey();
            List<SharedResource> links = entry.getValue();
            ShareResourceLimit limit = getLimit(context, resourceType);
            invitationTtl = Math.min(invitationTtl, limit.getInvitationTtl());
            ResourceDescriptor sharedWithMe = getShareResource(ResourceTypes.SHARED_WITH_ME, resourceType, bucket, bucketLocation);
            String state = resourceService.getResource(sharedWithMe);
            SharedResources existingSharedResources = ProxyUtil.convertToObject(state, SharedResources.class);
            if (existingSharedResources == null) {
                existingSharedResources = new SharedResources(new ArrayList<>());
            }
            Map<String, SharedResource> reshareableResourceUrls = new HashMap<>();
            for (SharedResource sharedResource : existingSharedResources.getResources()) {
                if (sharedResource.getPermissions().contains(ResourceAccessType.SHARE)) {
                    reshareableResourceUrls.put(sharedResource.getUrl(), sharedResource);
                }
            }
            List<SharedResource> ownerResources = new ArrayList<>();
            for (SharedResource sharedResource : links) {
                ResourceDescriptor resource = getResourceFromLink(sharedResource.getUrl());
                canShare(sharedResource, bucket, resource, reshareableResourceUrls.keySet());
                if (!uniqueLinks.add(resource.getUrl())) {
                    throw new IllegalArgumentException("Duplicated resource: %s".formatted(resource.getUrl()));
                }
                if (resource.getBucketName().equals(bucket)) {
                    ownerResources.add(sharedResource.withUrl(resource.getUrl()));
                }
                String author = reshareableResourceUrls.containsKey(resource.getUrl())
                        ? reshareableResourceUrls.get(resource.getUrl()).getAuthor()
                        : context.getUserDisplayName();
                normalizedResourceLinks.add(sharedResource.withUrl(resource.getUrl()).withAuthor(author));
            }
            updateLimits(context, resourceType, limit, ownerResources);
        }

        Invitation invitation = invitationService.createInvitation(bucket, bucketLocation, normalizedResourceLinks,
                context.getUserDisplayName(), request.getMaxAcceptedUsers(), invitationTtl);
        return new InvitationLink(InvitationService.INVITATION_PATH_BASE + ResourceDescriptor.PATH_SEPARATOR + invitation.getId());
    }

    private void canShare(SharedResource sharedResource, String bucket, ResourceDescriptor resource, Set<String> resharedResourceUrls) {
        if (bucket.equals(resource.getBucketName())) {
            return;
        }
        while (resource != null) {
            if (resharedResourceUrls.contains(resource.getUrl())) {
                if (sharedResource.getPermissions().contains(ResourceAccessType.WRITE)) {
                    throw new IllegalArgumentException("Re-shared resource %s is not allowed to be shared with the permission WRITE"
                            .formatted(sharedResource.getUrl()));
                }
                return;
            }
            resource = resource.getParent();
        }
        throw new IllegalArgumentException("Resource %s is not allowed to be shared by the user".formatted(sharedResource.getUrl()));
    }

    private void validateShareResourcesRequest(ShareResourcesRequest request) {
        Set<SharedResource> sharedResources = request.getResources();
        if (sharedResources.isEmpty()) {
            throw new IllegalArgumentException("No resources provided");
        }
        Set<String> owners = new HashSet<>();
        for (SharedResource resource : sharedResources) {
            if (resource.getPermissions() == null) {
                resource.setPermissions(ResourceAccessType.READ_ONLY);
            } else if (resource.getPermissions().size() == 1 && resource.getPermissions().contains(ResourceAccessType.SHARE)) {
                throw new IllegalArgumentException("You're not allowed to share a resource with the permission SHARE only."
                        + " This permission is allowed along with READ and/or WRITE");
            }
            ResourceDescriptor descriptor = resourceFromUrl(resource.getUrl(), encryptionService);
            owners.add(descriptor.getBucketName());
        }
        if (owners.size() > 1) {
            throw new IllegalArgumentException("You're not allowed to share resources of different owners in a single request");
        }
    }

    private void updateLimits(ProxyContext context, ResourceType resourceType, ShareResourceLimit limit, List<SharedResource> resources) {
        String ownerLocation = BucketBuilder.buildInitiatorBucket(context);
        String ownerBucket = encryptionService.encrypt(ownerLocation);
        ResourceDescriptor sharedByMe = getShareResource(ResourceTypes.SHARED_BY_ME, resourceType, ownerBucket, ownerLocation);
        resourceService.computeResource(sharedByMe, state -> {
            SharedByMeDto dto = ProxyUtil.convertToObject(state, SharedByMeDto.class);
            if (dto == null) {
                dto = new SharedByMeDto(new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>());
            }
            for (SharedResource resource : resources) {
                dto.updateLimit(resource.getUrl(), limit);
            }
            return ProxyUtil.convertToString(dto);
        });
    }

    private ShareResourceLimit getLimit(ProxyContext context, ResourceType resourceType) {
        List<String> userRoles = context.getUserRoles();
        Map<String, Role> roles = context.getConfig().getRoles();
        ShareResourceLimit defaultLimit = DEFAULT_LIMITS.get(resourceType);
        ShareResourceLimit limit = null;
        for (String userRole : userRoles) {
            ShareResourceLimit candidate = Optional.ofNullable(roles.get(userRole)).map(Role::getShare).map(limits -> limits.get(resourceType.name())).orElse(null);
            if (candidate != null) {
                if (limit == null) {
                    limit = new ShareResourceLimit(candidate.getMaxAcceptedUsers(), candidate.getInvitationTtl());
                } else {
                    limit.setInvitationTtl(Math.max(limit.getInvitationTtl(), candidate.getInvitationTtl()));
                    limit.setMaxAcceptedUsers(Math.max(limit.getMaxAcceptedUsers(), candidate.getMaxAcceptedUsers()));
                }
                if (limit.getMaxAcceptedUsers() == -1) {
                    limit.setMaxAcceptedUsers(defaultLimit.getMaxAcceptedUsers());
                }
                if (limit.getInvitationTtl() == -1) {
                    limit.setInvitationTtl(defaultLimit.getInvitationTtl());
                }
            }
        }
        if (limit != null) {
            return limit;
        }
        if (defaultLimit == null) {
            throw new IllegalArgumentException("Unsupported resource type: " + resourceType);
        }
        return defaultLimit;
    }

    private void addCredentialsSharing(ProxyContext context,
                                       ShareResourcesRequest request) {
        Set<SharedResource> newSharedResources = new HashSet<>(request.getResources());
        for (SharedResource sharedResource : request.getResources()) {
            ResourceDescriptor resource = getResourceFromLink(sharedResource.getUrl());
            if (CREDS_SHARABLE_RESOURCE_TYPES.contains(resource.getType()) && sharedResource.isShareCredentials()) {
                log.debug("Credential sharing started - User: {}, Resource: {}.", context.getUserId(), sharedResource.getUrl());
                CredentialsDescriptor globalCredentialsDescriptor = getGlobalCredentialsDescriptor(resource, context, resource.getType());
                ResourceCredentials globalResourceCredentials =
                        resourceCredentialsService.getResourceCredentials(globalCredentialsDescriptor);

                if (globalResourceCredentials != null
                        && globalResourceCredentials.getCredentialsLevel().equals(CredentialsLevel.GLOBAL)) {
                    ResourceDescriptor resourceDescriptor = globalCredentialsDescriptor.toResourceDescriptor();
                    SharedResource sharedCredentials = new SharedResource(
                            resourceDescriptor.getUrl(), null, null, ResourceAccessType.READ_ONLY);
                    newSharedResources.add(sharedCredentials);
                } else {
                    throw new IllegalArgumentException("Global credentials for resource: %s not found".formatted(sharedResource.getUrl()));
                }
                log.debug("Credential sharing finished - User: {}, Resource: {}.", context.getUserId(), sharedResource.getUrl());
            }
        }
        request.setResources(newSharedResources);
    }

    /**
     * Accept an invitation to grand share access for provided resources
     *
     * @param invitationId - invitation ID
     */
    public void acceptSharedResources(ProxyContext context, String invitationId) {
        String location = BucketBuilder.buildInitiatorBucket(context);
        String bucket = encryptionService.encrypt(location);
        String userDisplayName = context.getUserDisplayName();
        invitationService.acceptInvitation(
                invitationId, location, invitation -> acceptInvitation(bucket, location, invitation, userDisplayName));
    }

    private void acceptInvitation(String bucket, String location, Invitation invitation, String userDisplayName) {
        List<SharedResource> resourceLinks = invitation.getResources();
        String resourceOwnerLocation = null;
        for (SharedResource link : resourceLinks) {
            String url = link.getUrl();
            ResourceDescriptor resourceDescriptor = ResourceDescriptorFactory.fromPrivateUrl(url, encryptionService);
            if (resourceDescriptor.getBucketName().equals(bucket)) {
                throw new IllegalArgumentException("Resource %s already belong to you".formatted(url));
            }
            if (resourceOwnerLocation == null) {
                resourceOwnerLocation = resourceDescriptor.getBucketLocation();
            }
        }

        // group resources with the same type to reduce resource transformations
        Map<ResourceType, List<SharedResource>> resourceGroups = resourceLinks.stream()
                .collect(Collectors.groupingBy(sharedResource -> getResourceType(sharedResource.getUrl())));
        lockService.underBucketLock(resourceOwnerLocation, () -> {
            List<Pair<ResourceDescriptor, SharedByMeDto>> resourcesToBeUpdated = new ArrayList<>();
            resourceGroups.forEach((resourceType, links) -> {
                String ownerBucket = getBucket(links.get(0).getUrl());
                String ownerLocation = encryptionService.decrypt(ownerBucket);

                // write user location to the resource owner
                ResourceDescriptor sharedByMe = getShareResource(ResourceTypes.SHARED_BY_ME, resourceType, ownerBucket, ownerLocation);
                String state = resourceService.getResource(sharedByMe);
                SharedByMeDto dto = ProxyUtil.convertToObject(state, SharedByMeDto.class);
                if (dto == null) {
                    dto = new SharedByMeDto(new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>());
                }

                // add user location for each link
                for (SharedResource resource : links) {
                    int numOfAcceptedUsers = dto.getNumOfAcceptedUsers(resource.getUrl());
                    ShareResourceLimit limit = dto.getLimits().getOrDefault(resource.getUrl(), DEFAULT_LIMITS.get(resourceType));
                    if (limit == null) {
                        throw new IllegalArgumentException("Limit is not found for the resource:" + resource.getUrl());
                    }
                    if (numOfAcceptedUsers >= limit.getMaxAcceptedUsers()) {
                        throw new IllegalArgumentException("Limit is exceeded on the number of accepted users for the resource: " + resource.getUrl());
                    }
                    dto.addUserToResource(resource, location, userDisplayName);
                }
                resourcesToBeUpdated.add(Pair.of(sharedByMe, dto));
            });
            for (Pair<ResourceDescriptor, SharedByMeDto> pair : resourcesToBeUpdated) {
                resourceService.putResource(pair.getKey(), ProxyUtil.convertToString(pair.getValue()), EtagHeader.ANY);
            }
            return null;
        });
        lockService.underBucketLock(location, () -> {
            long time = clock.getAsLong();
            resourceGroups.forEach((resourceType, links) -> {
                ResourceDescriptor sharedWithMe = getShareResource(ResourceTypes.SHARED_WITH_ME, resourceType, bucket, location);
                String state = resourceService.getResource(sharedWithMe);
                SharedResources sharedResources = ProxyUtil.convertToObject(state, SharedResources.class);
                if (sharedResources == null) {
                    sharedResources = new SharedResources(new ArrayList<>());
                }

                // add all links to the user
                sharedResources.addSharedResources(links, invitation.getAuthor(), time);

                resourceService.putResource(sharedWithMe, ProxyUtil.convertToString(sharedResources), EtagHeader.ANY);
            });
            return null;
        });
    }

    public Map<ResourceDescriptor, Set<ResourceAccessType>> getPermissions(
            String bucket, String location, Set<ResourceDescriptor> allResources) {
        Map<ResourceType, List<ResourceDescriptor>> privateResourcesByTypes = allResources.stream()
                .filter(ResourceDescriptor::isPrivate)
                .collect(Collectors.groupingBy(ResourceDescriptor::getType));
        Map<ResourceDescriptor, Set<ResourceAccessType>> result = new HashMap<>();
        privateResourcesByTypes.forEach((type, resources) -> {
            ResourceDescriptor shareResource = getShareResource(ResourceTypes.SHARED_WITH_ME, type, bucket, location);

            String state = resourceService.getResource(shareResource);
            SharedResources sharedResources = ProxyUtil.convertToObject(state, SharedResources.class);
            if (sharedResources == null) {
                log.debug("No state found for share access");
                return;
            }

            Map<String, Set<ResourceAccessType>> resourcePermissions =
                    sharedResourcesToMap(sharedResources.getResources());
            for (ResourceDescriptor resource : resources) {
                result.put(resource, lookupPermissions(resource, resourcePermissions, new HashMap<>()));
            }
        });

        return result;
    }

    private static Set<ResourceAccessType> lookupPermissions(
            ResourceDescriptor resource,
            Map<String, Set<ResourceAccessType>> resourcePermissions,
            Map<ResourceDescriptor, Set<ResourceAccessType>> cache) {
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
     * @param bucket       - user bucket
     * @param location     - storage location
     * @param resourceLink - the resource to revoke access
     */
    public void revokeSharedResource(
            String bucket, String location, ResourceDescriptor resourceLink) {
        revokeSharedAccess(bucket, location, Map.of(resourceLink, ResourceAccessType.ALL));
    }

    /**
     * Revoke share access for provided resources. Only resource owner can perform this operation
     *
     * @param bucket              - user bucket
     * @param location            - storage location
     * @param permissionsToRevoke - collection of resources and permissions to revoke access
     */
    public void revokeSharedAccess(String bucket, String location,
                                   Map<ResourceDescriptor, Set<ResourceAccessType>> permissionsToRevoke) {
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
            ResourceDescriptor sharedByMeResource = getShareResource(ResourceTypes.SHARED_BY_ME, resourceType, bucket, location);
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
                    removeSharedResourcePermissions(userBucket, user, resource, permissionsToRemove);
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

    public Map<ResourceDescriptor, Set<ResourceAccessType>> addSharedCredentialsToRevoke(Map<ResourceDescriptor, Set<ResourceAccessType>> permissionsToRevoke,
                                                                                         ProxyContext context) {
        Map<ResourceDescriptor, Set<ResourceAccessType>> newPermissionsToRevoke = new HashMap<>(permissionsToRevoke);
        permissionsToRevoke.forEach((resource, permissionsToRemove) -> {
            if (CREDS_SHARABLE_RESOURCE_TYPES.contains(resource.getType())
                    && permissionsToRemove.contains(ResourceAccessType.READ)) {
                log.debug("Credential revocation started - User: {}, Resource: {}", context.getUserId(), resource.getUrl());
                CredentialsDescriptor globalCredentialsDescriptor = getGlobalCredentialsDescriptor(resource, context, resource.getType());
                ResourceCredentials globalResourceCredentials = resourceCredentialsService.getResourceCredentials(globalCredentialsDescriptor);
                if (globalResourceCredentials != null) {
                    ResourceDescriptor resourceDescriptor = globalCredentialsDescriptor.toResourceDescriptor();
                    newPermissionsToRevoke.put(resourceDescriptor, ResourceAccessType.ALL);
                }
                log.debug("Credential revocation finished - User: {}, Resource: {}", context.getUserId(), resource.getUrl());
            }
        });

        return newPermissionsToRevoke;
    }

    private CredentialsDescriptor getGlobalCredentialsDescriptor(ResourceDescriptor resourceDescriptor,
                                                                 ProxyContext context,
                                                                 ResourceType resourceType) {
        CredentialsLocator credentialsLocator = CredentialsLocatorFactory.fromAnyUrl(resourceDescriptor.getUrl(), context, resourceType);
        return credentialsLocator.getCredentialsDescriptors().get(CredentialsLevel.GLOBAL);
    }

    public void discardSharedAccess(String bucket, String location, ResourceLinkCollection request) {
        Set<ResourceLink> resourceLinks = request.getResources();
        if (resourceLinks.isEmpty()) {
            throw new IllegalArgumentException("No resources provided");
        }

        Set<ResourceDescriptor> resources = new HashSet<>();
        for (ResourceLink link : resourceLinks) {
            resources.add(getResourceFromLink(link.url()));
        }

        for (ResourceDescriptor resource : resources) {
            ResourceType resourceType = resource.getType();
            String resourceUrl = resource.getUrl();
            removeSharedResourcePermissions(bucket, location, resource, ResourceAccessType.ALL);

            String ownerBucket = resource.getBucketName();
            String ownerLocation = encryptionService.decrypt(ownerBucket);

            ResourceDescriptor sharedWithMe = getShareResource(ResourceTypes.SHARED_BY_ME, resourceType, ownerBucket, ownerLocation);
            resourceService.computeResource(sharedWithMe, ownerState -> {
                SharedByMeDto sharedByMeDto = ProxyUtil.convertToObject(ownerState, SharedByMeDto.class);
                if (sharedByMeDto != null) {
                    sharedByMeDto.removeUserFromResource(resourceUrl, location);
                }

                return ProxyUtil.convertToString(sharedByMeDto);
            });
        }
    }

    public void copySharedAccess(String bucket, String location, ResourceDescriptor source, ResourceDescriptor destination) {
        if (!resourceService.hasResource(source)) {
            throw new IllegalArgumentException("source resource %s does not exists".formatted(source.getUrl()));
        }

        if (!resourceService.hasResource(destination)) {
            throw new IllegalArgumentException("destination resource %s dos not exists".formatted(destination.getUrl()));
        }

        ResourceType sourceResourceType = source.getType();
        ResourceDescriptor sharedByMeResource = getShareResource(ResourceTypes.SHARED_BY_ME, sourceResourceType, bucket, location);
        SharedByMeDto sharedByMeDto = ProxyUtil.convertToObject(resourceService.getResource(sharedByMeResource), SharedByMeDto.class);
        if (sharedByMeDto == null) {
            return;
        }

        Map<String, Set<ResourceAccessType>> userPermissions = sharedByMeDto.getUserPermissions(source.getUrl());

        ResourceType destinationResourceType = destination.getType();
        String destinationResourceLink = destination.getUrl();
        // source and destination resource type might be different
        sharedByMeResource = getShareResource(ResourceTypes.SHARED_BY_ME, destinationResourceType, bucket, location);

        // copy user locations from source to destination
        resourceService.computeResource(sharedByMeResource, state -> {
            SharedByMeDto dto = ProxyUtil.convertToObject(state, SharedByMeDto.class);
            if (dto == null) {
                dto = new SharedByMeDto(new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>());
            }

            // add shared access to the destination resource
            dto.addUserPermissionsToResource(
                    destinationResourceLink, userPermissions, sharedByMeDto.getUserIdToDisplayName());

            return ProxyUtil.convertToString(dto);
        });

        // add each user shared access to the destination resource
        userPermissions.forEach((userLocation, permissions) -> {
            String userBucket = encryptionService.encrypt(userLocation);
            copySharedResource(
                    userBucket, userLocation, source.getUrl(), source.getType(), destinationResourceLink, destinationResourceType);
        });
    }

    public void moveSharedAccess(String bucket, String location, ResourceDescriptor source, ResourceDescriptor destination) {
        // copy shared access from source to destination
        copySharedAccess(bucket, location, source, destination);
        // revoke shared access from source
        revokeSharedAccess(bucket, location, Map.of(source, ResourceAccessType.ALL));
    }

    private void removeSharedResourcePermissions(
            String bucket, String location, ResourceDescriptor resource, Set<ResourceAccessType> permissionsToRemove) {
        ResourceDescriptor sharedWithMeResource = getShareResource(ResourceTypes.SHARED_WITH_ME, resource.getType(), bucket, location);
        String link = resource.getUrl();
        MutableObject<Boolean> wasReshared = new MutableObject<>();
        resourceService.computeResource(sharedWithMeResource, state -> {
            SharedResources sharedWithMe = ProxyUtil.convertToObject(state, SharedResources.class);
            if (sharedWithMe != null) {
                for (Iterator<SharedResource> iterator = sharedWithMe.getResources().iterator(); iterator.hasNext();) {
                    SharedResource sharedResource = iterator.next();
                    if (sharedResource.getUrl().equals(link)) {
                        wasReshared.setValue(sharedResource.getPermissions().contains(ResourceAccessType.SHARE));
                        if (sharedResource.getSharedBy() != null) {
                            sharedResource.getSharedBy().forEach(
                                    shareMetadata -> shareMetadata.getPermissions().removeAll(permissionsToRemove));
                            sharedResource.getSharedBy().removeIf(
                                    shareMetadata -> shareMetadata.getPermissions().isEmpty());
                        }
                        sharedResource.getPermissions().removeAll(permissionsToRemove);
                        if (sharedResource.getPermissions().isEmpty()) {
                            iterator.remove();
                            break;
                        }
                    }
                }
            }

            return ProxyUtil.convertToString(sharedWithMe);
        });
        if (Boolean.TRUE.equals(wasReshared.getValue())) {
            invitationService.cleanUpResourceLink(bucket, location, resource);
        }
    }

    private void copySharedResource(
            String bucket,
            String location,
            String source,
            ResourceType sourceType,
            String destination,
            ResourceType destinationType) {
        SharedResources toCopy = sourceType != destinationType
                ? getSharedResources(bucket, location, source, sourceType)
                : null;
        ResourceDescriptor destinationResource = getShareResource(ResourceTypes.SHARED_WITH_ME, destinationType, bucket, location);
        resourceService.computeResource(destinationResource, state -> {
            SharedResources sharedWithMe = ProxyUtil.convertToObject(state, SharedResources.class);
            if (sharedWithMe == null) {
                sharedWithMe = new SharedResources(new ArrayList<>());
            }

            List<SharedResource> resources = (toCopy == null ? sharedWithMe : toCopy).getResources().stream()
                    .filter(resource -> source.equals(resource.getUrl()))
                    .map(resource -> resource.withUrl(destination))
                    .toList();
            sharedWithMe.getResources().addAll(resources);

            return ProxyUtil.convertToString(sharedWithMe);
        });
    }

    private SharedResources getSharedResources(String bucket, String location, String link, ResourceType type) {
        ResourceDescriptor descriptor = getShareResource(ResourceTypes.SHARED_WITH_ME, type, bucket, location);
        SharedResources sharedResources = ProxyUtil.convertToObject(
                resourceService.getResource(descriptor), SharedResources.class);
        if (sharedResources == null) {
            throw new IllegalArgumentException("No shared access found for the resource: " + link);
        }

        return sharedResources;
    }

    private List<MetadataBase> toMetadata(SharedByMeDto dto, boolean includeUserInfo) {
        Map<String, Set<ResourceAccessType>> links = dto.getAggregatedPermissions();
        return links.entrySet().stream()
                .map(entry -> {
                    String link = entry.getKey();
                    Set<ResourceAccessType> permissions = entry.getValue();
                    ResourceDescriptor resource = ResourceDescriptorFactory.fromPrivateUrl(link, encryptionService);
                    MetadataBase metadata = resource.isFolder()
                            ? new ResourceFolderMetadata(resource)
                            : new ResourceItemMetadata(resource);
                    metadata.setPermissions(permissions);
                    if (includeUserInfo) {
                        List<ShareMetadata> perUserPermissions = new ArrayList<>();
                        dto.getUserPermissions(link).forEach((user, access) -> {
                            String userDisplayName = dto.getUserIdToDisplayName().get(user);
                            if (StringUtils.isNotBlank(userDisplayName)) {
                                ShareMetadata shareMetadata = new ShareMetadata();
                                shareMetadata.setUser(userDisplayName);
                                shareMetadata.setPermissions(access);
                                perUserPermissions.add(shareMetadata);
                            }
                        });

                        metadata.setSharedWith(perUserPermissions);
                    }
                    return metadata;
                }).toList();
    }

    private ResourceDescriptor getResourceFromLink(String url) {
        return resourceFromUrl(url, encryptionService);
    }

    private ResourceDescriptor getShareResource(ResourceType shareResourceType, ResourceType requestedResourceType, String bucket, String location) {
        return ResourceDescriptorFactory.fromDecoded(shareResourceType, bucket, location,
                requestedResourceType.group() + ResourceDescriptor.PATH_SEPARATOR + SHARE_RESOURCE_FILENAME);
    }

    private ResourceType getResourceType(String url) {
        if (url == null) {
            throw new IllegalStateException("Resource link can not be null");
        }

        String[] paths = url.split(ResourceDescriptor.PATH_SEPARATOR);

        if (paths.length < 2) {
            throw new IllegalStateException("Invalid resource link provided: " + url);
        }

        return ResourceTypes.of(paths[0]);
    }

    private String getBucket(String url) {
        if (url == null) {
            throw new IllegalStateException("Resource link can not be null");
        }

        String[] paths = url.split(ResourceDescriptor.PATH_SEPARATOR);

        if (paths.length < 2) {
            throw new IllegalStateException("Invalid resource link provided: " + url);
        }

        return paths[1];
    }

    public static Map<String, Set<ResourceAccessType>> sharedResourcesToMap(List<SharedResource> sharedResources) {
        return sharedResources.stream()
                .collect(Collectors.toUnmodifiableMap(SharedResource::getUrl, SharedResource::getPermissions));
    }

    public static ResourceDescriptor resourceFromUrl(String url, EncryptionService encryptionService) {
        try {
            if (url.startsWith(ProxyUtil.METADATA_PREFIX)) {
                url = url.substring(ProxyUtil.METADATA_PREFIX.length());
            }
            return ResourceDescriptorFactory.fromPrivateUrl(url, encryptionService);
        } catch (Exception e) {
            throw new IllegalArgumentException("Incorrect resource link provided " + url);
        }
    }
}
