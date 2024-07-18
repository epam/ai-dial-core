package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.data.FileMetadata;
import com.epam.aidial.core.service.ResourceService;
import com.epam.aidial.core.storage.BlobWriteStream;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.EtagHeader;
import com.epam.aidial.core.util.HttpException;
import com.epam.aidial.core.util.HttpStatus;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.streams.Pipe;
import io.vertx.core.streams.impl.PipeImpl;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class UploadFileController extends AccessControlBaseController {

    public UploadFileController(Proxy proxy, ProxyContext context) {
        super(proxy, context, true);
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
            EtagHeader etag = EtagHeader.fromRequest(context.getRequest());
            etag.validate(() -> proxy.getResourceService().getEtag(resource));
            return context.getRequest()
                    .setExpectMultipart(true)
                    .uploadHandler(upload -> {
                        ResourceService resourceService = proxy.getResourceService();
                        String contentType = upload.contentType();
                        Pipe<Buffer> pipe = new PipeImpl<>(upload).endOnFailure(false);
                        BlobWriteStream writeStream = resourceService.getFileWriteStream(resource, etag, contentType);
                        pipe.to(writeStream)
                                .onSuccess(success -> {
                                    FileMetadata metadata = writeStream.getMetadata();
                                    context.getResponse().putHeader(HttpHeaders.ETAG, metadata.getEtag());
                                    context.respond(HttpStatus.OK, metadata);
                                })
                                .onFailure(error -> {
                                    writeStream.abortUpload(error);
                                    context.respond(error,
                                            "Failed to upload file by path %s/%s".formatted(resource.getBucketName(), resource.getOriginalPath()));
                                });
                    });
        }, false)
                .onFailure(error -> context.respond(error, error.getMessage()));
    }
}
