package com.epam.aidial.core.service;

import com.epam.aidial.core.data.ResourceType;
import com.epam.aidial.core.storage.BlobStorage;
import com.epam.aidial.core.storage.ResourceDescription;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public class ResourceOperationService {

    private final ResourceService resourceService;
    private final BlobStorage storage;
    private final InvitationService invitationService;
    private final ShareService shareService;

    public void moveResource(String bucket, String location, ResourceDescription source, ResourceDescription destination, boolean overwriteIfExists) {
        if (source.isFolder() || destination.isFolder()) {
            throw new IllegalArgumentException("Moving folders is not supported");
        }

        String sourceResourcePath = source.getAbsoluteFilePath();
        String sourceResourceUrl = source.getUrl();
        String destinationResourcePath = destination.getAbsoluteFilePath();
        String destinationResourceUrl = destination.getUrl();

        if (!hasResource(source)) {
            throw new IllegalArgumentException("Source resource %s do not exists".formatted(sourceResourceUrl));
        }

        ResourceType resourceType = source.getType();
        switch (resourceType) {
            case FILE -> {
                if (!overwriteIfExists && storage.exists(destinationResourcePath)) {
                    throw new IllegalArgumentException("Can't move resource %s to %s, because destination resource already exists"
                            .formatted(sourceResourceUrl, destinationResourceUrl));
                }
                storage.copy(sourceResourcePath, destinationResourcePath);
                storage.delete(sourceResourcePath);
            }
            case CONVERSATION, PROMPT -> {
                boolean copied = resourceService.copyResource(source, destination, overwriteIfExists);
                if (!copied) {
                    throw new IllegalArgumentException("Can't move resource %s to %s, because destination resource already exists"
                            .formatted(sourceResourceUrl, destinationResourceUrl));
                }
                resourceService.deleteResource(source);
            }
            default -> throw new IllegalArgumentException("Unsupported resource type " + resourceType);
        }
        // move source links to destination if any
        invitationService.moveResource(bucket, location, source, destination);
        // move shared access if any
        shareService.moveSharedAccess(bucket, location, source, destination);
    }

    private boolean hasResource(ResourceDescription resource) {
        return switch (resource.getType()) {
            case FILE -> storage.exists(resource.getAbsoluteFilePath());
            case CONVERSATION, PROMPT -> resourceService.hasResource(resource);
            default -> throw new IllegalArgumentException("Unsupported resource type " + resource.getType());
        };
    }

}
