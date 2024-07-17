package com.epam.aidial.core.service;

import com.epam.aidial.core.data.ResourceEvent;
import com.epam.aidial.core.data.ResourceType;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.EtagHeader;
import lombok.AllArgsConstructor;

import java.util.Collection;
import java.util.function.Consumer;

@AllArgsConstructor
public class ResourceOperationService {

    private final ResourceService resourceService;
    private final InvitationService invitationService;
    private final ShareService shareService;

    public ResourceTopic.Subscription subscribeResources(Collection<ResourceDescription> resources,
                                                         Consumer<ResourceEvent> subscriber) {
        return resourceService.subscribeResources(resources, subscriber);
    }

    public void moveResource(String bucket, String location, ResourceDescription source, ResourceDescription destination, boolean overwriteIfExists) {
        if (source.isFolder() || destination.isFolder()) {
            throw new IllegalArgumentException("Moving folders is not supported");
        }

        String sourceResourceUrl = source.getUrl();
        String destinationResourceUrl = destination.getUrl();

        if (!resourceService.hasResource(source)) {
            throw new IllegalArgumentException("Source resource %s do not exists".formatted(sourceResourceUrl));
        }

        ResourceType resourceType = source.getType();
        switch (resourceType) {
            case FILE, CONVERSATION, PROMPT, APPLICATION -> {
                boolean copied = resourceService.copyResource(source, destination, overwriteIfExists);
                if (!copied) {
                    throw new IllegalArgumentException("Can't move resource %s to %s, because destination resource already exists"
                            .formatted(sourceResourceUrl, destinationResourceUrl));
                }
            }
            default -> throw new IllegalArgumentException("Unsupported resource type " + resourceType);
        }
        // move source links to destination if any
        invitationService.moveResource(bucket, location, source, destination);
        // move shared access if any
        shareService.moveSharedAccess(bucket, location, source, destination);

        deleteResource(source);
    }

    private void deleteResource(ResourceDescription resource) {
        switch (resource.getType()) {
            case FILE, CONVERSATION, PROMPT, APPLICATION -> resourceService.deleteResource(resource, EtagHeader.ANY);
            default -> throw new IllegalArgumentException("Unsupported resource type " + resource.getType());
        }
    }
}
