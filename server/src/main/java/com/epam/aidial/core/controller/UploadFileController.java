package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.data.FileMetadata;
import com.epam.aidial.core.service.ResourceService;
import com.epam.aidial.core.storage.BlobStorage;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.EtagHeader;
import com.epam.aidial.core.util.HttpStatus;
import com.epam.aidial.core.util.ProxyUtil;
import com.epam.aidial.core.vertx.BlobWriteStream;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.streams.Pipe;
import io.vertx.core.streams.impl.PipeImpl;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class UploadFileController extends AccessControlBaseController {

    private final BlobStorage blobStorage;

    private final Vertx vertx;

    private final ResourceService resourceService;

    public UploadFileController(Proxy proxy, ProxyContext context) {
        super(proxy, context, true);
        this.blobStorage = proxy.getStorage();
        this.vertx = proxy.getVertx();
        this.resourceService = proxy.getResourceService();
    }

    @Override
    protected Future<?> handle(ResourceDescription resource, boolean hasWriteAccess) {
        if (resource.isFolder()) {
            return context.respond(HttpStatus.BAD_REQUEST, "File name is missing");
        }

        if (!ResourceDescription.isValidResourcePath(resource)) {
            return context.respond(HttpStatus.BAD_REQUEST, "Resource name and/or parent folders must not end with .(dot)");
        }

        return proxy.getVertx().executeBlocking(() -> {
            EtagHeader etag = ProxyUtil.fromRequest(context.getRequest());
            etag.validate(() -> proxy.getResourceService().getEtag(resource));
            context.getRequest()
                    .setExpectMultipart(true)
                    .uploadHandler(upload -> {
                        String contentType = upload.contentType();
                        Pipe<Buffer> pipe = new PipeImpl<>(upload).endOnFailure(false);
                        BlobWriteStream writeStream = new BlobWriteStream(vertx, resourceService, blobStorage, resource, etag, contentType);
                        pipe.to(writeStream)
                                .onSuccess(success -> {
                                    FileMetadata metadata = writeStream.getMetadata();
                                    context.putHeader(HttpHeaders.ETAG, metadata.getEtag())
                                            .exposeHeaders()
                                            .respond(HttpStatus.OK, metadata);
                                })
                                .onFailure(error -> {
                                    writeStream.abortUpload(error);
                                    log.warn("Failed to upload file: {}", resource.getUrl(), error);
                                    context.respond(error, "Failed to upload file: " + resource.getUrl());
                                });
                    });

            return Future.succeededFuture();
        }, false)
                .otherwise(error -> {
                    log.warn("Failed to upload file: {}", resource.getUrl(), error);
                    context.respond(error, "Failed to upload file: " + resource.getUrl());
                    return null;
                });
    }
}