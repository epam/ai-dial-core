package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.service.ResourceService;
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

    public DownloadFileController(Proxy proxy, ProxyContext context) {
        super(proxy, context, false);
    }

    @Override
    protected Future<?> handle(ResourceDescription resource, boolean hasWriteAccess) {
        if (resource.isFolder()) {
            return context.respond(HttpStatus.BAD_REQUEST, "Can't download a folder");
        }

        ResourceService resourceService = proxy.getResourceService();
        Future<ResourceService.ResourceStream> blobFuture = proxy.getVertx().executeBlocking(() ->
                resourceService.getResourceStream(resource), false);

        Promise<Void> result = Promise.promise();
        blobFuture.onSuccess(resourceStream -> {
            if (resourceStream == null) {
                context.respond(HttpStatus.NOT_FOUND);
                result.complete();
                return;
            }

            HttpServerResponse response = context.getResponse()
                    .putHeader(HttpHeaders.CONTENT_TYPE, resourceStream.contentType())
                    // content-length removed by vertx
                    .putHeader(HttpHeaders.CONTENT_LENGTH, Long.toString(resourceStream.contentLength()))
                    .putHeader(HttpHeaders.ETAG, resourceStream.etag());

            InputStreamReader stream = new InputStreamReader(proxy.getVertx(), resourceStream.inputStream());
            stream.pipeTo(response, result);
            result.future().onFailure(error -> {
                stream.close();
                response.reset();
            });
        }).onFailure(error -> context.respond(HttpStatus.INTERNAL_SERVER_ERROR,
                "Failed to fetch file with path %s/%s".formatted(resource.getBucketName(), resource.getOriginalPath())));

        return result.future();
    }
}
