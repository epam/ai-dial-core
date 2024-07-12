package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.service.FileService;
import com.epam.aidial.core.storage.InputStreamReader;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.HttpStatus;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DownloadFileController extends AccessControlBaseController {
    private final FileService fileService;

    public DownloadFileController(Proxy proxy, ProxyContext context) {
        super(proxy, context, false);
        fileService = proxy.getFileService();
    }

    @Override
    protected Future<?> handle(ResourceDescription resource, boolean hasWriteAccess) {
        if (resource.isFolder()) {
            return context.respond(HttpStatus.BAD_REQUEST, "Can't download a folder");
        }

        Future<FileService.FileStream> blobFuture = proxy.getVertx().executeBlocking(() ->
                fileService.getFile(resource), false);

        Promise<Void> result = Promise.promise();
        blobFuture.onSuccess(fileStream -> {
            if (fileStream == null) {
                context.respond(HttpStatus.NOT_FOUND);
                result.complete();
                return;
            }

            HttpServerResponse response = context.getResponse()
                    .putHeader(HttpHeaders.CONTENT_TYPE, fileStream.contentType())
                    // content-length removed by vertx
                    .putHeader(HttpHeaders.CONTENT_LENGTH, Long.toString(fileStream.contentLength()))
                    .putHeader(HttpHeaders.ETAG, fileStream.etag());

            InputStreamReader stream = new InputStreamReader(proxy.getVertx(), fileStream.inputStream());
            stream.pipeTo(response, result);
            result.future().onFailure(error -> {
                stream.close();
                context.getResponse().reset();
            });
        }).onFailure(error -> context.respond(HttpStatus.INTERNAL_SERVER_ERROR,
                "Failed to fetch file with path %s/%s".formatted(resource.getBucketName(), resource.getOriginalPath())));

        return result.future();
    }
}
