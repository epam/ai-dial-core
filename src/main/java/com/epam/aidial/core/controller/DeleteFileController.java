package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.storage.BlobStorage;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.HttpStatus;
import io.vertx.core.Future;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DeleteFileController extends AccessControlBaseController {

    public DeleteFileController(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    protected Future<?> handle(ResourceDescription resource) {
        if (resource.isFolder()) {
            return context.respond(HttpStatus.BAD_REQUEST, "Can't delete a folder");
        }

        String absoluteFilePath = resource.getAbsoluteFilePath();

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
