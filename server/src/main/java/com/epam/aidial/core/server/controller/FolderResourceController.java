package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.folder.FolderResourceMarker;
import com.epam.aidial.core.server.service.folder.FolderResourceHandler;
import com.epam.aidial.core.server.service.folder.FolderResourceService;
import com.epam.aidial.core.server.service.folder.SkillHandler;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceType;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.util.EtagHeader;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.impl.HttpUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Whole-resource (folder-as-resource) controller for the v2 API. Handles the HTTP concerns (multipart
 * decoding for PUT, ZIP streaming for GET) and delegates the resource logic to {@link FolderResourceService}
 * using a per-type handler keyed by URL group.
 */
@Slf4j
public class FolderResourceController extends AccessControlBaseController {

    private static final Map<ResourceType, FolderResourceHandler> HANDLERS = Map.of(
            ResourceTypes.SKILL, new SkillHandler());

    private final FolderResourceService folderResourceService;

    public FolderResourceController(Proxy proxy, ProxyContext context, boolean isWriteAccess) {
        super(proxy, context, isWriteAccess);
        this.folderResourceService = proxy.getFolderResourceService();
    }

    @Override
    protected Future<?> handle(ResourceDescriptor resource, boolean hasWriteAccess) {
        FolderResourceHandler handler = HANDLERS.get(resource.getType());
        if (handler == null) {
            return context.respond(HttpStatus.BAD_REQUEST, "Unsupported folder resource type: " + resource.getType().group());
        }

        HttpMethod method = context.getRequest().method();
        if (HttpMethod.PUT.equals(method)) {
            return put(resource, handler);
        }
        if (HttpMethod.GET.equals(method)) {
            return get(resource);
        }
        return context.respond(HttpStatus.METHOD_NOT_ALLOWED);
    }

    private Future<?> put(ResourceDescriptor resource, FolderResourceHandler handler) {
        HttpServerRequest request = context.getRequest();
        String contentType = request.getHeader(HttpHeaderNames.CONTENT_TYPE);
        if (contentType == null || !HttpUtils.isValidMultipartContentType(contentType)) {
            return context.respond(HttpStatus.BAD_REQUEST, "Request must have a valid multipart/form-data content-type");
        }

        String author = context.getUserDisplayName();
        EtagHeader etag = ProxyUtil.etag(request);

        // Multipart parts arrive sequentially on the event loop; buffer each so the blocking commit can
        // run on a worker thread. The whole request is already size-capped by the proxy.
        Map<String, Buffer> uploads = new LinkedHashMap<>();
        Promise<Void> received = Promise.promise();
        request.setExpectMultipart(true)
                .uploadHandler(upload -> {
                    Buffer buffer = Buffer.buffer();
                    upload.handler(buffer::appendBuffer);
                    upload.endHandler(v -> uploads.put(upload.filename(), buffer));
                    upload.exceptionHandler(received::tryFail);
                })
                .endHandler(v -> received.tryComplete())
                .exceptionHandler(received::tryFail);

        received.future()
                .compose(v -> proxy.getTaskExecutor().submit(() -> folderResourceService.putFolder(resource, handler, uploads, etag, author)))
                .compose(aggregateEtag -> context.putHeader(HttpHeaders.ETAG, aggregateEtag)
                        .exposeHeaders()
                        .respond(HttpStatus.OK)
                        .mapEmpty())
                .recover(error -> {
                    log.warn("Failed to upload resource: {}", resource.getUrl(), error);
                    return context.respond(error, "Failed to upload resource: " + resource.getUrl()).mapEmpty();
                });
        return Future.succeededFuture();
    }

    private Future<?> get(ResourceDescriptor resource) {
        proxy.getTaskExecutor().submit(() -> folderResourceService.getMarker(resource))
                .compose(document -> {
                    if (document == null) {
                        return context.respond(HttpStatus.NOT_FOUND).mapEmpty();
                    }
                    downloadArchive(resource, document);
                    return Future.succeededFuture();
                })
                .recover(error -> {
                    log.warn("Failed to download resource: {}", resource.getUrl(), error);
                    return context.respond(error, "Failed to download resource: " + resource.getUrl()).mapEmpty();
                });
        return Future.succeededFuture();
    }

    private void downloadArchive(ResourceDescriptor resource, FolderResourceMarker document) {
        Buffer archive = folderResourceService.downloadArchive(resource, document.getCurrentVersion());

        context.putHeader(HttpHeaders.CONTENT_TYPE, "application/zip")
                .putHeader(HttpHeaders.ETAG, document.getEtag())
                .putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(archive.length()))
                .exposeHeaders();

        context.respond(HttpStatus.OK.getCode(), archive);
    }
}
