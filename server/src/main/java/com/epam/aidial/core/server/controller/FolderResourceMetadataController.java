package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.service.resource.ComplexResourceService;
import com.epam.aidial.core.storage.data.MetadataBase;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import io.vertx.core.Future;
import io.vertx.core.http.HttpHeaders;
import lombok.extern.slf4j.Slf4j;

/**
 * Metadata listing for the v2 complex resource API. In children mode it lists the classified, enriched
 * immediate children of a grouping level; in files mode it lists the files of a resource's current version.
 */
@Slf4j
public class FolderResourceMetadataController extends AccessControlBaseController {

    private final ComplexResourceService complexResourceService;
    // True to list files of a skill's current version; false to list the children of a grouping level.
    private final boolean filesMode;
    // Optional subfolder inside the version, for files mode.
    private final String subPath;

    public FolderResourceMetadataController(Proxy proxy, ProxyContext context, boolean filesMode, String subPath) {
        super(proxy, context, false);
        this.complexResourceService = proxy.getComplexResourceService();
        this.filesMode = filesMode;
        this.subPath = subPath;
    }

    private String getContentType() {
        String acceptType = context.getRequest().getHeader(HttpHeaders.ACCEPT);
        return acceptType != null && acceptType.contains(MetadataBase.MIME_TYPE)
                ? MetadataBase.MIME_TYPE
                : "application/json";
    }

    @Override
    protected Future<?> handle(ResourceDescriptor resource, boolean hasWriteAccess) {
        String token = context.getRequest().getParam("token");
        int limit = Integer.parseInt(context.getRequest().getParam("limit", "100"));
        boolean recursive = Boolean.parseBoolean(context.getRequest().getParam("recursive", "false"));
        if (limit < 0 || limit > 1000) {
            return context.respond(HttpStatus.BAD_REQUEST, "Limit is out of allowed range: [0, 1000]");
        }

        proxy.getTaskExecutor().submit(() -> filesMode
                        ? complexResourceService.listFiles(resource, subPath, token, limit, recursive)
                        : complexResourceService.listChildren(resource, token, limit, recursive))
                .onSuccess(result -> {
                    if (result == null) {
                        context.respond(HttpStatus.NOT_FOUND);
                    } else {
                        context.respond(HttpStatus.OK, getContentType(), result);
                    }
                })
                .onFailure(error -> {
                    log.warn("Failed to list metadata: {}", resource.getUrl(), error);
                    context.respond(error, "Failed to list metadata: " + resource.getUrl());
                });

        return Future.succeededFuture();
    }
}
