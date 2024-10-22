package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.MetadataBase;
import com.epam.aidial.core.server.resource.ResourceDescriptor;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.service.ResourceService;
import com.epam.aidial.core.server.util.HttpStatus;
import io.vertx.core.Future;
import io.vertx.core.http.HttpHeaders;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class FileMetadataController extends AccessControlBaseController {
    private final ResourceService resourceService;
    private final AccessService accessService;

    public FileMetadataController(Proxy proxy, ProxyContext context) {
        super(proxy, context, false);
        this.resourceService = proxy.getResourceService();
        this.accessService = proxy.getAccessService();
    }

    private String getContentType() {
        String acceptType = context.getRequest().getHeader(HttpHeaders.ACCEPT);
        return acceptType != null && acceptType.contains(MetadataBase.MIME_TYPE)
                ? MetadataBase.MIME_TYPE
                : "application/json";
    }

    @Override
    protected Future<?> handle(ResourceDescriptor resource, boolean hasWriteAccess) {
        boolean recursive = Boolean.parseBoolean(context.getRequest().getParam("recursive", "false"));
        String token = context.getRequest().getParam("token");
        int limit = Integer.parseInt(context.getRequest().getParam("limit", "100"));
        if (limit < 0 || limit > 1000) {
            return context.respond(HttpStatus.BAD_REQUEST, "Limit is out of allowed range: [0, 1000]");
        }

        proxy.getVertx().executeBlocking(() -> {
            try {
                MetadataBase metadata = resourceService.getMetadata(resource, token, limit, recursive);
                if (metadata != null) {
                    accessService.filterForbidden(context, resource, metadata);
                    if (context.getBooleanRequestQueryParam("permissions")) {
                        accessService.populatePermissions(context, List.of(metadata));
                    }
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

        return Future.succeededFuture();
    }
}
