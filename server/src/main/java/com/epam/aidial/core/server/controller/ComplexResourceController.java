package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.folder.FolderResourceMarker;
import com.epam.aidial.core.server.service.resource.ComplexResourceHandler;
import com.epam.aidial.core.server.service.resource.ComplexResourceService;
import com.epam.aidial.core.server.service.resource.SkillHandler;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.stream.InputStreamReader;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceType;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.impl.HttpUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.mutable.MutableObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Whole-resource (folder-as-resource) controller for the v2 API. Handles the HTTP concerns (multipart
 * decoding for PUT, ZIP streaming for GET) and delegates the resource logic to {@link ComplexResourceService}
 * using a per-type handler keyed by URL group.
 */
@Slf4j
public class ComplexResourceController extends AccessControlBaseController {

    private static final Map<ResourceType, ComplexResourceHandler> HANDLERS = Map.of(
            ResourceTypes.SKILL, new SkillHandler());

    private final ComplexResourceService complexResourceService;
    // Relative path of a single file inside the resource; null for whole-resource operations.
    private final String filePath;
    // True for DIAL grouping-folder operations (trailing-slash route).
    private final boolean folderOp;

    public ComplexResourceController(Proxy proxy, ProxyContext context, boolean isWriteAccess) {
        this(proxy, context, isWriteAccess, null, false);
    }

    public ComplexResourceController(Proxy proxy, ProxyContext context, boolean isWriteAccess, String filePath) {
        this(proxy, context, isWriteAccess, filePath, false);
    }

    public ComplexResourceController(Proxy proxy, ProxyContext context, boolean isWriteAccess, boolean folderOp) {
        this(proxy, context, isWriteAccess, null, folderOp);
    }

    private ComplexResourceController(Proxy proxy, ProxyContext context, boolean isWriteAccess, String filePath, boolean folderOp) {
        super(proxy, context, isWriteAccess);
        this.complexResourceService = proxy.getComplexResourceService();
        this.filePath = filePath;
        this.folderOp = folderOp;
    }

    @Override
    protected Future<?> handle(ResourceDescriptor resource, boolean hasWriteAccess) {
        ComplexResourceHandler handler = HANDLERS.get(resource.getType());
        if (handler == null) {
            return context.respond(HttpStatus.BAD_REQUEST, "Unsupported folder resource type: " + resource.getType().group());
        }

        HttpMethod method = context.getRequest().method();
        if (folderOp) {
            if (HttpMethod.PUT.equals(method)) {
                return putFolderCreate(resource);
            }
            if (HttpMethod.DELETE.equals(method)) {
                return deleteFolderTarget(resource);
            }
            if (HttpMethod.GET.equals(method)) {
                // A trailing-slash GET is never a valid whole-resource retrieval.
                return context.respond(HttpStatus.BAD_REQUEST, "Path is a folder; use metadata listing: " + resource.getUrl());
            }
            return context.respond(HttpStatus.METHOD_NOT_ALLOWED);
        }
        if (filePath != null) {
            if (HttpMethod.PUT.equals(method)) {
                return putFile(resource, handler);
            }
            if (HttpMethod.GET.equals(method)) {
                return getFile(resource);
            }
            if (HttpMethod.DELETE.equals(method)) {
                return deleteFile(resource, handler);
            }
            return context.respond(HttpStatus.METHOD_NOT_ALLOWED);
        }
        if (HttpMethod.PUT.equals(method)) {
            return put(resource, handler);
        }
        if (HttpMethod.GET.equals(method)) {
            return get(resource);
        }
        if (HttpMethod.DELETE.equals(method)) {
            return delete(resource);
        }
        return context.respond(HttpStatus.METHOD_NOT_ALLOWED);
    }

    private Future<?> put(ResourceDescriptor resource, ComplexResourceHandler handler) {
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
                .compose(v -> proxy.getTaskExecutor().submit(() -> complexResourceService.putFolder(resource, handler, uploads, etag, author)))
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

    private Future<?> putFolderCreate(ResourceDescriptor resource) {
        String author = context.getUserDisplayName();
        proxy.getTaskExecutor().submit(() -> complexResourceService.createDialFolder(resource, author))
                .compose(etag -> context.putHeader(HttpHeaders.ETAG, etag)
                        .exposeHeaders()
                        .respond(HttpStatus.OK)
                        .mapEmpty())
                .recover(error -> {
                    log.warn("Failed to create folder: {}", resource.getUrl(), error);
                    return context.respond(error, "Failed to create folder: " + resource.getUrl()).mapEmpty();
                });
        return Future.succeededFuture();
    }

    private Future<?> deleteFolderTarget(ResourceDescriptor resource) {
        EtagHeader etag = ProxyUtil.etag(context.getRequest());
        proxy.getTaskExecutor().submit(() -> {
            complexResourceService.deleteDialFolder(resource, etag);
            return null;
        })
                .compose(v -> context.respond(HttpStatus.OK).mapEmpty())
                .recover(error -> {
                    log.warn("Failed to delete folder: {}", resource.getUrl(), error);
                    return context.respond(error, "Failed to delete folder: " + resource.getUrl()).mapEmpty();
                });
        return Future.succeededFuture();
    }

    private Future<?> get(ResourceDescriptor resource) {
        proxy.getTaskExecutor().submit(() -> complexResourceService.getResourceMarkerOrRejectFolder(resource))
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

    private Future<?> delete(ResourceDescriptor resource) {
        EtagHeader etag = ProxyUtil.etag(context.getRequest());
        proxy.getTaskExecutor().submit(() -> {
            complexResourceService.deleteFolder(resource, etag);
            return null;
        })
                .compose(v -> context.respond(HttpStatus.OK).mapEmpty())
                .recover(error -> {
                    log.warn("Failed to delete resource: {}", resource.getUrl(), error);
                    return context.respond(error, "Failed to delete resource: " + resource.getUrl()).mapEmpty();
                });
        return Future.succeededFuture();
    }

    private Future<?> putFile(ResourceDescriptor resource, ComplexResourceHandler handler) {
        HttpServerRequest request = context.getRequest();
        String contentType = request.getHeader(HttpHeaderNames.CONTENT_TYPE);
        if (contentType == null || !HttpUtils.isValidMultipartContentType(contentType)) {
            return context.respond(HttpStatus.BAD_REQUEST, "Request must have a valid multipart/form-data content-type");
        }

        String author = context.getUserDisplayName();
        EtagHeader etag = ProxyUtil.etag(request);

        MutableObject<Buffer> bufferRef = new MutableObject<>();
        Promise<Void> received = Promise.promise();
        request.setExpectMultipart(true)
                .uploadHandler(upload -> {
                    Buffer buffer = Buffer.buffer();
                    upload.handler(buffer::appendBuffer);
                    upload.endHandler(v -> {
                        if (bufferRef.get() != null) {
                            throw new HttpException(HttpStatus.BAD_REQUEST, "Exactly one file part is required");
                        }
                        bufferRef.setValue(buffer);
                    });
                    upload.exceptionHandler(received::tryFail);
                })
                .endHandler(v -> received.tryComplete())
                .exceptionHandler(received::tryFail);

        received.future()
                .compose(v -> {
                    Buffer buffer = bufferRef.get();
                    if (buffer == null) {
                        throw new HttpException(HttpStatus.BAD_REQUEST, "Exactly one file part is required");
                    }
                    byte[] content = buffer.getBytes();
                    return proxy.getTaskExecutor().submit(() ->
                            complexResourceService.putFile(resource, handler, filePath, content, etag, author));
                })
                .compose(aggregateEtag -> context.putHeader(HttpHeaders.ETAG, aggregateEtag)
                        .exposeHeaders()
                        .respond(HttpStatus.OK)
                        .mapEmpty())
                .recover(error -> {
                    log.warn("Failed to upload file to resource: {}", resource.getUrl(), error);
                    return context.respond(error, "Failed to upload file to resource: " + resource.getUrl()).mapEmpty();
                });
        return Future.succeededFuture();
    }

    private Future<?> getFile(ResourceDescriptor resource) {
        proxy.getTaskExecutor().submit(() -> complexResourceService.getFileStream(resource, filePath))
                .compose(stream -> {
                    if (stream == null) {
                        return context.respond(HttpStatus.NOT_FOUND).mapEmpty();
                    }
                    streamFile(stream);
                    return Future.succeededFuture();
                })
                .recover(error -> {
                    log.warn("Failed to download file from resource: {}", resource.getUrl(), error);
                    return context.respond(error, "Failed to download file from resource: " + resource.getUrl()).mapEmpty();
                });
        return Future.succeededFuture();
    }

    private Future<?> deleteFile(ResourceDescriptor resource, ComplexResourceHandler handler) {
        String author = context.getUserDisplayName();
        EtagHeader etag = ProxyUtil.etag(context.getRequest());
        proxy.getTaskExecutor().submit(() -> complexResourceService.deleteFile(resource, handler, filePath, etag, author))
                .compose(aggregateEtag -> context.putHeader(HttpHeaders.ETAG, aggregateEtag)
                        .exposeHeaders()
                        .respond(HttpStatus.OK)
                        .mapEmpty())
                .recover(error -> {
                    log.warn("Failed to delete file from resource: {}", resource.getUrl(), error);
                    return context.respond(error, "Failed to delete file from resource: " + resource.getUrl()).mapEmpty();
                });
        return Future.succeededFuture();
    }

    private void streamFile(ResourceService.ResourceStream stream) {
        HttpServerResponse response = context.putHeader(HttpHeaders.CONTENT_TYPE, stream.contentType())
                .putHeader(HttpHeaders.CONTENT_LENGTH, Long.toString(stream.contentLength()))
                .putHeader(HttpHeaders.ETAG, stream.etag())
                .exposeHeaders()
                .getResponse();

        InputStreamReader reader = new InputStreamReader(proxy.getVertx(), proxy.getTaskExecutor(), stream.inputStream());
        reader.pipe()
                .endOnFailure(false)
                .to(response)
                .onFailure(error -> {
                    reader.close();
                    response.reset();
                });
    }

    private void downloadArchive(ResourceDescriptor resource, FolderResourceMarker document) {
        Buffer archive = complexResourceService.downloadArchive(resource, document.getCurrentVersion());

        context.putHeader(HttpHeaders.CONTENT_TYPE, "application/zip")
                .putHeader(HttpHeaders.ETAG, document.getEtag())
                .putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(archive.length()))
                .exposeHeaders();

        context.respond(HttpStatus.OK.getCode(), archive);
    }
}
