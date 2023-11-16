package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.storage.BlobWriteStream;
import com.epam.aidial.core.util.HttpStatus;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.Pipe;
import io.vertx.core.streams.impl.PipeImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class UploadFileController {

    private final Proxy proxy;
    private final ProxyContext context;

    public Future<?> upload(String path) {
        String absoluteFilePath = BlobStorageUtil.buildAbsoluteFilePath(context, path);
        Promise<Void> result = Promise.promise();
        context.getRequest()
                .setExpectMultipart(true)
                .uploadHandler(upload -> {
                    String filename = upload.filename();
                    Pipe<Buffer> pipe = new PipeImpl<>(upload).endOnFailure(false);
                    BlobWriteStream writeStream = new BlobWriteStream(
                            proxy.getVertx(),
                            proxy.getStorage(),
                            filename,
                            absoluteFilePath);
                    pipe.to(writeStream, result);

                    result.future()
                            .onSuccess(success -> context.respond(HttpStatus.OK, writeStream.getMetadata()))
                            .onFailure(error -> {
                                writeStream.abortUpload(error);
                                context.respond(HttpStatus.INTERNAL_SERVER_ERROR,
                                        "Failed to upload file: " + error.getMessage());
                            });
                });

        return result.future();
    }
}
