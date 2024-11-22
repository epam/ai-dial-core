package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.util.EtagHeader;
import io.vertx.core.Future;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DeleteFileController extends AccessControlBaseController {


    public DeleteFileController(Proxy proxy, ProxyContext context) {
        super(proxy, context, true);
    }

    @Override
    protected Future<?> handle(ResourceDescriptor resource, boolean hasWriteAccess) {
        if (resource.isFolder()) {
            return context.respond(HttpStatus.BAD_REQUEST, "Can't delete a folder");
        }

        EtagHeader etag = ProxyUtil.etag(context.getRequest());
        proxy.getVertx().executeBlocking(() -> proxy.getResourceOperationService().deleteResource(resource, etag), false)
                .onSuccess(success -> context.respond(HttpStatus.OK))
                .onFailure(error -> {
                    log.error("Failed to delete file  {}", resource.getUrl(), error);
                    context.respond(error, error.getMessage());
                });

        return Future.succeededFuture();
    }
}
