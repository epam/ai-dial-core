package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.storage.BlobStorage;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.util.HttpStatus;
import io.vertx.core.Future;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AllArgsConstructor
public class DeleteFileController {
    private final Proxy proxy;
    private final ProxyContext context;

    public Future<?> delete(String filePath) {
        String absoluteFilePath = BlobStorageUtil.buildAbsoluteFilePath(context, filePath);
        BlobStorage storage = proxy.getStorage();
        Future<Void> result = proxy.getVertx().executeBlocking(() -> {
            try {
                storage.delete(absoluteFilePath);
                return null;
            } catch (Exception ex) {
                log.error("Failed to delete file " + absoluteFilePath, ex);
                throw new RuntimeException(ex);
            }
        });

        return result
                .onSuccess(success -> context.respond(HttpStatus.OK))
                .onFailure(error -> context.respond(HttpStatus.INTERNAL_SERVER_ERROR, error.getMessage()));
    }
}
