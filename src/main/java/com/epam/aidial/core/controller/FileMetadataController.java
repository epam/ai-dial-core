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
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class FileMetadataController extends AccessControlBaseController {

    public FileMetadataController(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    protected Future<?> handle(String bucketName, String bucketLocation, String filePath) {
        BlobStorage storage = proxy.getStorage();
        return proxy.getVertx().executeBlocking(() -> {
            try {
                String path = filePath.isEmpty() ? BlobStorageUtil.PATH_SEPARATOR : filePath;
                ResourceDescription resource = ResourceDescription.from(ResourceType.FILE, bucketName, bucketLocation, path);
                FileMetadataBase metadata = storage.listMetadata(resource);
                if (metadata != null) {
                    context.respond(HttpStatus.OK, metadata);
                } else {
                    context.respond(HttpStatus.NOT_FOUND);
                }
            } catch (Exception ex) {
                log.error("Failed to list files", ex);
                context.respond(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to list files by path %s/%s".formatted(bucketName, filePath));
            }

            return null;
        });
    }
}
