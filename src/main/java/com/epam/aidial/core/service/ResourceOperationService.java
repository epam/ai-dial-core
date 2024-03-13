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

        if (!resourceService.hasResource(source)) {
            throw new IllegalArgumentException("Can't find resource %s".formatted(sourceResourceUrl));
        }

        if (!overwriteIfExists && resourceService.hasResource(destination)) {
            throw new IllegalArgumentException("Can't move resource %s to %s, because destination resource already exists"
                    .formatted(sourceResourceUrl, destinationResourceUrl));
        }

        ResourceType resourceType = source.getType();
        switch (resourceType) {
            case FILE -> {
                storage.copy(sourceResourcePath, destinationResourcePath);
                storage.delete(sourceResourcePath);
            }
            case CONVERSATION, PROMPT -> {
                resourceService.copyResource(source, destination);
                resourceService.deleteResource(source);
            }
            default -> throw new IllegalArgumentException("Unsupported resource type " + resourceType);
        }
        // move source links to destination if any
        invitationService.moveResource(bucket, location, source, destination);
        // move shared access if any
        shareService.moveSharedAccess(bucket, location, source, destination);
    }

}
