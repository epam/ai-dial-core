package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.data.ResourceTypes;
import com.epam.aidial.core.storage.data.ResourceAccessType;
import com.epam.aidial.core.storage.data.ResourceEvent;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceType;
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

import static com.epam.aidial.core.server.data.ResourceTypes.APPLICATION;
import static com.epam.aidial.core.server.data.ResourceTypes.CONVERSATION;
import static com.epam.aidial.core.server.data.ResourceTypes.FILE;
import static com.epam.aidial.core.server.data.ResourceTypes.PROMPT;

@AllArgsConstructor
public class ResourceOperationService {
    private static final Set<ResourceTypes> ALLOWED_RESOURCES = Set.of(FILE, CONVERSATION,
            PROMPT, APPLICATION);

    private final ApplicationService applicationService;
    private final ResourceService resourceService;
    private final InvitationService invitationService;
    private final ShareService shareService;
    private final LockService lockService;

    public ResourceTopic.Subscription subscribeResources(Collection<ResourceDescriptor> resources,
                                                         Consumer<ResourceEvent> subscriber) {
        return resourceService.subscribeResources(resources, subscriber);
    }

    public void moveResource(ResourceDescriptor source, ResourceDescriptor destination, boolean overwriteIfExists) {
        if (source.isFolder() || destination.isFolder()) {
            throw new IllegalArgumentException("Moving folders is not supported");
        }

        String sourceResourceUrl = source.getUrl();
        String destinationResourceUrl = destination.getUrl();

        if (!resourceService.hasResource(source)) {
            throw new IllegalArgumentException("Source resource %s does not exist".formatted(sourceResourceUrl));
        }

        if (!ALLOWED_RESOURCES.contains(source.getType())) {
            throw new IllegalStateException("Unsupported type: " + source.getType());
        }

        if (destination.getType() == APPLICATION) {
            applicationService.copyApplication(source, destination, overwriteIfExists, app -> {
                if (ApplicationService.isActive(app)) {
                    throw new HttpException(HttpStatus.CONFLICT, "Application must be stopped: " + source.getUrl());
                }
            });
        } else {
            boolean copied = resourceService.copyResource(source, destination, overwriteIfExists);
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
        } else {
            resourceService.deleteResource(source, EtagHeader.ANY);
        }
    }

    public boolean deleteResource(ResourceDescriptor resource, EtagHeader etag) {
        verifyResourceToDelete(resource);
        MutableObject<Boolean> deleted = new MutableObject<>();
        if (resource.isPrivate()) {
            String bucketName = resource.getBucketName();
            String bucketLocation = resource.getBucketLocation();
            // links to dependent resources should be removed under user's bucket lock
            lockService.underBucketLock(bucketLocation, () -> {
                invitationService.cleanUpResourceLink(bucketName, bucketLocation, resource);
                shareService.revokeSharedResource(bucketName, bucketLocation, resource);
                deleted.setValue(deleteResourceInternally(resource, etag));
                return null;
            });
        } else {
            deleted.setValue(deleteResourceInternally(resource, etag));
        }
        return deleted.getValue();
    }

    private static void verifyResourceToDelete(ResourceDescriptor resource) {
        ResourceType type = resource.getType();
        if (!(APPLICATION == type || FILE == type
                || CONVERSATION == type || type == PROMPT)) {
            throw new IllegalArgumentException("Unsupported resource type to delete: " + type.name());
        }
    }

    private boolean deleteResourceInternally(ResourceDescriptor resource, EtagHeader etag) {
        if (resource.getType() == APPLICATION) {
            try {
                applicationService.deleteApplication(resource, etag);
            } catch (ResourceNotFoundException e) {
                return false;
            }
            return true;
        } else {
            return resourceService.deleteResource(resource, etag);
        }
    }

}