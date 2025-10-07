package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.storage.data.MetadataBase;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.UrlUtil;
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

        proxy.getTaskExecutor().submit(() -> {
            MetadataBase result = resourceService.getMetadata(resource, token, limit, recursive);
            if (result == null) {
                return null;
            }
            accessService.filterForbidden(context, resource, result);
            if (context.getBooleanRequestQueryParam("permissions")) {
                accessService.populatePermissions(context, List.of(result));
            }
            return result;
        }).onSuccess(result -> {
            if (result == null) {
                context.respond(HttpStatus.NOT_FOUND);
            } else {
                context.respond(HttpStatus.OK, getContentType(), result);
            }
        }).onFailure(error -> {
            log.warn("Can't list files: {}", resource.getUrl(), error);
            context.respond(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to list files by path %s".formatted(resource.getUrl()));
        });

        return Future.succeededFuture();
    }
}
