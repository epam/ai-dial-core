package com.epam.aidial.core.server.service.schemarichapps;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.data.NodeType;
import com.epam.aidial.core.storage.data.ResourceFolderMetadata;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ResourceListingService {

    private final EncryptionService encryption;
    private final ResourceService resourceService;

    public ResourceListingService(EncryptionService encryption, ResourceService resourceService) {
        this.encryption = encryption;
        this.resourceService = resourceService;
    }

    public Stream<ResourceDescriptor> listFilesFromFolderWithSubFolders(ResourceDescriptor folderDescriptor) {
        if (!folderDescriptor.isFolder()) {
            return Stream.empty();
        }

        List<ResourceDescriptor> fileDescriptors = new ArrayList<>();
        String nextToken = null;

        do {
            try {
                ResourceFolderMetadata folderMetadata =
                        resourceService.getFolderMetadata(folderDescriptor, nextToken, 1000, true);

                if (folderMetadata == null || folderMetadata.getItems() == null) {
                    break;
                }

                // Process all files in this page
                folderMetadata.getItems().stream()
                        .filter(item -> item.getNodeType() != NodeType.FOLDER)
                        .map(item -> ResourceDescriptorFactory.fromPrivateUrl(item.getUrl(), encryption))
                        .forEach(fileDescriptors::add);

                nextToken = folderMetadata.getNextToken();
            } catch (Exception e) {
                log.warn("Failed to list files in folder while publishing: {}", folderDescriptor.getUrl(), e);
                throw new RuntimeException(e);
            }
        } while (nextToken != null);

        return fileDescriptors.stream();
    }
}
