package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.data.MetadataBase;
import com.epam.aidial.core.storage.BlobStorage;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.HttpStatus;
import io.vertx.core.Future;
import io.vertx.core.http.HttpHeaders;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class FileMetadataController extends AccessControlBaseController {

    public FileMetadataController(Proxy proxy, ProxyContext context) {
        super(proxy, context, false);
    }

    private String getContentType() {
        String acceptType = context.getRequest().getHeader(HttpHeaders.ACCEPT);
        return acceptType != null && acceptType.contains(MetadataBase.MIME_TYPE)
                ? MetadataBase.MIME_TYPE
                : "application/json";
    }

    @Override
    protected Future<?> handle(ResourceDescription resource) {
        BlobStorage storage = proxy.getStorage();
        boolean recursive = Boolean.parseBoolean(context.getRequest().getParam("recursive", "false"));
        String token = context.getRequest().getParam("token");
        int limit = Integer.parseInt(context.getRequest().getParam("limit", "100"));
        if (limit < 0 || limit > 1000) {
            context.respond(HttpStatus.BAD_REQUEST, "Limit is out of allowed range: [0, 1000]");
            return Future.succeededFuture();
        }
        return proxy.getVertx().executeBlocking(() -> {
            try {
                MetadataBase metadata = storage.listMetadata(resource, token, limit, recursive);
                if (metadata != null) {
                    proxy.getAccessService().filterForbidden(context, resource, metadata);
                    context.respond(HttpStatus.OK, getContentType(), metadata);
                } else {
                    context.respond(HttpStatus.NOT_FOUND);
                }
            } catch (Exception ex) {
                log.error("Failed to list files", ex);
                context.respond(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Failed to list files by path %s/%s".formatted(resource.getBucketName(), resource.getOriginalPath()));
            }

            return null;
        }, false);
    }
}
