package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.vertx.InputStreamReader;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.HttpStatus;
import io.vertx.core.Future;
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

        proxy.getVertx().executeBlocking(() -> proxy.getResourceService().getResourceStream(resource), false)
                .compose(resourceStream -> {
                    if (resourceStream == null) {
                        return context.respond(HttpStatus.NOT_FOUND);
                    }

                    HttpServerResponse response = context.putHeader(HttpHeaders.CONTENT_TYPE, resourceStream.contentType())
                            // content-length removed by vertx
                            .putHeader(HttpHeaders.CONTENT_LENGTH, Long.toString(resourceStream.contentLength()))
                            .putHeader(HttpHeaders.ETAG, resourceStream.etag())
                            .exposeHeaders()
                            .getResponse();

                    InputStreamReader stream = new InputStreamReader(proxy.getVertx(), resourceStream.inputStream());
                    stream.pipeTo(response)
                            .onFailure(error -> {
                                stream.close();
                                response.reset();
                            });
                    return Future.succeededFuture();
                }).onFailure(error -> {
                    log.warn("Failed to download file: {}", resource.getUrl(), error);
                    context.respond(error, "Failed to download file: " + resource.getUrl());
                });

        return Future.succeededFuture();
    }
}
