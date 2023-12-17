package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.storage.BlobWriteStream;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.storage.ResourceType;
import com.epam.aidial.core.util.HttpStatus;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.Pipe;
import io.vertx.core.streams.impl.PipeImpl;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class UploadFileController extends AccessControlBaseController {

    public UploadFileController(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    protected Future<?> handle(String bucketName, String bucketLocation, String filePath) {
        if (filePath.isEmpty() || BlobStorageUtil.isFolder(filePath)) {
            return context.respond(HttpStatus.BAD_REQUEST, "File name is missing");
        }

        ResourceDescription resource = ResourceDescription.from(ResourceType.FILE, bucketName, bucketLocation, filePath);
        Promise<Void> result = Promise.promise();
        context.getRequest()
                .setExpectMultipart(true)
                .uploadHandler(upload -> {
                    String contentType = upload.contentType();
                    Pipe<Buffer> pipe = new PipeImpl<>(upload).endOnFailure(false);
                    BlobWriteStream writeStream = new BlobWriteStream(
                            proxy.getVertx(),
                            proxy.getStorage(),
                            resource,
                            contentType);
                    pipe.to(writeStream, result);

                    result.future()
                            .onSuccess(success -> context.respond(HttpStatus.OK, writeStream.getMetadata()))
                            .onFailure(error -> {
                                writeStream.abortUpload(error);
                                context.respond(HttpStatus.INTERNAL_SERVER_ERROR,
                                        "Failed to upload file by path %s/%s".formatted(bucketName, filePath));
                            });
                });

        return result.future();
    }
}
