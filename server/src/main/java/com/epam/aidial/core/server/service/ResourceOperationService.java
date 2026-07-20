package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.service.resource.ComplexResourceService;
import com.epam.aidial.core.storage.data.ResourceEvent;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceType;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.service.ResourceTopic;
import com.epam.aidial.core.storage.util.EtagHeader;
import lombok.AllArgsConstructor;
import org.apache.commons.lang3.mutable.MutableObject;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static com.epam.aidial.core.storage.resource.ResourceTypes.APPLICATION;
import static com.epam.aidial.core.storage.resource.ResourceTypes.CONVERSATION;
import static com.epam.aidial.core.storage.resource.ResourceTypes.FILE;
import static com.epam.aidial.core.storage.resource.ResourceTypes.PROMPT;
import static com.epam.aidial.core.storage.resource.ResourceTypes.SKILL;
import static com.epam.aidial.core.storage.resource.ResourceTypes.TOOL_SET;

@AllArgsConstructor
public class ResourceOperationService {
    private static final Set<ResourceTypes> ALLOWED_RESOURCES = Set.of(FILE, CONVERSATION,
            PROMPT, APPLICATION, TOOL_SET, SKILL);

    private final ApplicationService applicationService;
    private final ToolSetService toolSetService;
    private final ResourceService resourceService;
    private final InvitationService invitationService;
    private final ShareService shareService;
    private final LockService lockService;
    private final ComplexResourceService complexResourceService;

    public ResourceTopic.Subscription subscribeResources(Collection<ResourceDescriptor> resources,
                                                         Consumer<ResourceEvent> subscriber) {
        return resourceService.subscribeResources(resources, subscriber);
    }

    public void moveResource(ProxyContext context, ResourceDescriptor source, ResourceDescriptor destination,
                             boolean overwriteIfExists) {
        if (source.isFolder() || destination.isFolder()) {
            throw new IllegalArgumentException("Moving folders is not supported");
        }

        String sourceResourceUrl = source.getUrl();
        String destinationResourceUrl = destination.getUrl();

        if (!hasResource(source)) {
            throw new IllegalArgumentException("Source resource %s does not exist".formatted(sourceResourceUrl));
        }

        if (!ALLOWED_RESOURCES.contains(source.getType())) {
            throw new IllegalStateException("Unsupported type: " + source.getType());
        }

        if (destination.getType() == APPLICATION) {
            applicationService.copyApplication(source, destination, null, overwriteIfExists, app -> {
                if (ApplicationService.isActive(app)) {
                    throw new HttpException(HttpStatus.CONFLICT, "Application must be stopped: " + source.getUrl());
                }
            });
        } else if (destination.getType() == TOOL_SET) {
            Map<CredentialsLevel, Boolean> credentialsToCopy = Map.of(
                    CredentialsLevel.USER, false,
                    CredentialsLevel.GLOBAL, false
            );
            // TODO: support move for USER and APP credentials for public toolsets
            // TODO: support move for USER and APP credentials for shared toolsets (?)
            toolSetService.copyToolSet(context, source, destination, null, overwriteIfExists, credentialsToCopy);
        } else if (destination.getType() == SKILL) {
            if (!complexResourceService.copyResource(source, destination, null, overwriteIfExists)) {
                throw new IllegalArgumentException("Can't move resource %s to %s, because destination resource already exists"
                        .formatted(sourceResourceUrl, destinationResourceUrl));
            }
        } else {
            boolean copied = resourceService.copyResource(source, destination, null, overwriteIfExists);
            if (!copied) {
                throw new IllegalArgumentException("Can't move resource %s to %s, because destination resource already exists"
                        .formatted(sourceResourceUrl, destinationResourceUrl));
            }
        }

        if (source.isPrivate()) {
            String bucketName = source.getBucketName();
            String bucketLocation = source.getBucketLocation();
            boolean isSameBucket = source.getBucketName().equals(destination.getBucketName());

            if (isSameBucket) {
                invitationService.moveResource(bucketName, bucketLocation, source, destination);
                shareService.moveSharedAccess(bucketName, bucketLocation, source, destination);
            } else {
                Map<ResourceDescriptor, Set<ResourceAccessType>> resources = Map.of(source, ResourceAccessType.ALL);
                invitationService.cleanUpPermissions(bucketName, bucketLocation, resources);
                shareService.revokeSharedAccess(bucketName, bucketLocation, resources);
            }
        }

        if (destination.getType() == APPLICATION) {
            applicationService.deleteApplication(source, EtagHeader.ANY);
        } else if (destination.getType() == TOOL_SET) {
            toolSetService.deleteToolset(context, source, EtagHeader.ANY);
        } else if (destination.getType() == SKILL) {
            complexResourceService.delete(source, EtagHeader.ANY);
        } else {
            resourceService.deleteResource(source, EtagHeader.ANY);
        }
    }

    public void copyResource(ProxyContext context, ResourceDescriptor source, ResourceDescriptor destination,
                             boolean overwriteIfExists) {
        if (source.isFolder() || destination.isFolder()) {
            throw new IllegalArgumentException("Copying folders is not supported");
        }

        String sourceResourceUrl = source.getUrl();
        String destinationResourceUrl = destination.getUrl();

        if (!hasResource(source)) {
            throw new IllegalArgumentException("Source resource does not exist: " + sourceResourceUrl);
        }

        if (!ALLOWED_RESOURCES.contains(source.getType())) {
            throw new IllegalArgumentException("Source resource type is not supported: " + source.getType());
        }

        if (!source.getType().equals(destination.getType())) {
            throw new IllegalArgumentException("Source and destination resources must have same type");
        }

        if (destination.getType() == APPLICATION) {
            applicationService.copyApplication(source, destination, null, overwriteIfExists, app -> {
                // do nothing
            });
        } else if (destination.getType() == TOOL_SET) {
            toolSetService.copyToolSet(context, source, destination, null, overwriteIfExists, Map.of());
        } else if (destination.getType() == SKILL) {
            if (!complexResourceService.copyResource(source, destination, null, overwriteIfExists)) {
                throw new IllegalArgumentException("Can't copy resource %s to %s, because destination resource already exists"
                        .formatted(sourceResourceUrl, destinationResourceUrl));
            }
        } else {
            boolean copied = resourceService.copyResource(source, destination, null, overwriteIfExists);
            if (!copied) {
                throw new IllegalArgumentException("Can't copy resource %s to %s, because destination resource already exists"
                        .formatted(sourceResourceUrl, destinationResourceUrl));
            }
        }
    }

    private boolean hasResource(ResourceDescriptor resource) {
        return resource.getType() == SKILL
                ? complexResourceService.getMarker(resource) != null
                : resourceService.hasResource(resource);
    }

    public boolean deleteResource(ProxyContext context, ResourceDescriptor resource, EtagHeader etag) {
        verifyResourceToDelete(resource);
        MutableObject<Boolean> deleted = new MutableObject<>();
        if (resource.isPrivate()) {
            String bucketName = resource.getBucketName();
            String bucketLocation = resource.getBucketLocation();
            // links to dependent resources should be removed under user's bucket lock
            lockService.underBucketLock(bucketLocation, () -> {
                invitationService.cleanUpResourceLink(bucketName, bucketLocation, resource);
                shareService.revokeSharedResource(bucketName, bucketLocation, resource);
                deleted.setValue(deleteResourceInternally(context, resource, etag));
                return null;
            });
        } else {
            deleted.setValue(deleteResourceInternally(context, resource, etag));
        }
        return deleted.getValue();
    }

    private static void verifyResourceToDelete(ResourceDescriptor resource) {
        ResourceType type = resource.getType();
        if (!(APPLICATION == type || FILE == type
                || CONVERSATION == type || type == PROMPT || type == TOOL_SET || type == SKILL)) {
            throw new IllegalArgumentException("Unsupported resource type to delete: " + type.name());
        }
    }

    private boolean deleteResourceInternally(ProxyContext context, ResourceDescriptor resource, EtagHeader etag) {
        if (resource.getType() == APPLICATION) {
            try {
                applicationService.deleteApplication(resource, etag);
            } catch (ResourceNotFoundException e) {
                return false;
            }
            return true;
        }
        if (resource.getType() == TOOL_SET) {
            return toolSetService.deleteToolset(context, resource, etag);
        }
        if (resource.getType() == SKILL) {
            try {
                complexResourceService.delete(resource, etag);
            } catch (HttpException e) {
                if (e.getStatus() == HttpStatus.NOT_FOUND) {
                    return false;
                }
                throw e;
            }
            return true;
        }
        return resourceService.deleteResource(resource, etag);
    }

}