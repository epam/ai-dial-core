package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.data.FileMetadataBase;
import com.epam.aidial.core.storage.BlobStorage;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.storage.ResourceType;
import com.epam.aidial.core.util.HttpStatus;
import io.vertx.core.Future;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@AllArgsConstructor
@Slf4j
public class FileMetadataController {
    private final Proxy proxy;
    private final ProxyContext context;

    /**
     * Lists all files and folders that belong to the provided path.
     *
     * @param path relative path, for example: inputs/
     */
    public Future<?> list(String bucket, String path) {
        String expectedUserBucket = BlobStorageUtil.buildUserBucket(context);
        String decryptedBucket = proxy.getEncryptionService().decrypt(bucket);

        if (!expectedUserBucket.equals(decryptedBucket)) {
            return context.respond(HttpStatus.FORBIDDEN, "You don't have an access to the bucket " + bucket);
        }

        BlobStorage storage = proxy.getStorage();
        return proxy.getVertx().executeBlocking(() -> {
            try {
                String filePath = path.isEmpty() ? BlobStorageUtil.PATH_SEPARATOR : path;
                ResourceDescription resource = ResourceDescription.from(ResourceType.FILES, bucket, decryptedBucket, filePath);
                FileMetadataBase metadata = storage.listMetadata(resource);
                if (metadata != null) {
                    context.respond(HttpStatus.OK, metadata);
                } else {
                    context.respond(HttpStatus.NOT_FOUND);
                }
            } catch (Exception ex) {
                log.error("Failed to list files", ex);
                context.respond(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to list files by path %s".formatted(path));
            }

            return null;
        });
    }
}
