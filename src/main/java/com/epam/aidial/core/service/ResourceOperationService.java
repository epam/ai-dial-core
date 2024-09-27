package com.epam.aidial.core.service;

import com.epam.aidial.core.data.ResourceAccessType;
import com.epam.aidial.core.data.ResourceEvent;
import com.epam.aidial.core.data.ResourceType;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.EtagHeader;
import lombok.AllArgsConstructor;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

@AllArgsConstructor
public class ResourceOperationService {
    private static final Set<ResourceType> ALLOWED_RESOURCES = Set.of(ResourceType.FILE, ResourceType.CONVERSATION,
            ResourceType.PROMPT, ResourceType.APPLICATION);

    private final ApplicationService applicationService;
    private final ResourceService resourceService;
    private final InvitationService invitationService;
    private final ShareService shareService;

    public ResourceTopic.Subscription subscribeResources(Collection<ResourceDescription> resources,
                                                         Consumer<ResourceEvent> subscriber) {
        return resourceService.subscribeResources(resources, subscriber);
    }

    public void moveResource(ResourceDescription source, ResourceDescription destination, boolean overwriteIfExists) {
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

        if (destination.getType() == ResourceType.APPLICATION) {
            applicationService.copyApplication(source, destination, overwriteIfExists);
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
                Map<ResourceDescription, Set<ResourceAccessType>> resources = Map.of(source, ResourceAccessType.ALL);
                invitationService.cleanUpPermissions(bucketName, bucketLocation, resources);
                shareService.revokeSharedAccess(bucketName, bucketLocation, resources);
            }
        }

        if (destination.getType() == ResourceType.APPLICATION) {
            applicationService.deleteApplication(source,  EtagHeader.ANY);
        } else {
            resourceService.deleteResource(source, EtagHeader.ANY);
        }
    }
}