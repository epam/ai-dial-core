package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.service.LockService;
import com.epam.aidial.core.storage.BlobWriteStream;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.EtagHeader;
import com.epam.aidial.core.util.HttpException;
import com.epam.aidial.core.util.HttpStatus;
import io.vertx.core.Future;
import io.vertx.core.Promise;
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

        EtagHeader etag = EtagHeader.fromRequest(context.getRequest());
        Promise<Void> result = Promise.promise();
        context.getRequest()
                .setExpectMultipart(true)
                .uploadHandler(upload -> {
                    String contentType = upload.contentType();
                    Pipe<Buffer> pipe = new PipeImpl<>(upload).endOnFailure(false);

                    LockService.ExtendableLock lock = proxy.getLockService().lock(resource);
                    try {
                        etag.validate(() -> proxy.getStorage().getEtag(resource));

                        BlobWriteStream writeStream = new BlobWriteStream(
                                proxy.getVertx(),
                                proxy.getStorage(),
                                lock,
                                resource,
                                contentType);
                        pipe.to(writeStream, result);

                        result.future()
                                .onComplete(complete -> lock.close())
                                .onSuccess(success -> {
                                    context.getResponse().putHeader(HttpHeaders.ETAG, writeStream.getMetadata().getEtag());
                                    context.respond(HttpStatus.OK, writeStream.getMetadata());
                                })
                                .onFailure(error -> context.respond(HttpStatus.INTERNAL_SERVER_ERROR,
                                        "Failed to upload file by path %s/%s".formatted(resource.getBucketName(), resource.getOriginalPath())));
                    } catch (Throwable error) {
                        lock.close();
                        if (error instanceof HttpException exception) {
                            context.respond(exception.getStatus(), exception.getMessage());
                        } else {
                            context.respond(HttpStatus.INTERNAL_SERVER_ERROR, error.getMessage());
                        }
                    }
                });

        return result.future();
    }
}
