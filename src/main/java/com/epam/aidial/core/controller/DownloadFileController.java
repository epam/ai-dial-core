package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.storage.InputStreamReader;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.HttpStatus;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import lombok.extern.slf4j.Slf4j;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.io.MutableContentMetadata;
import org.jclouds.io.Payload;

import java.io.IOException;

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

        Future<Blob> blobFuture = proxy.getVertx().executeBlocking(() ->
                proxy.getStorage().load(resource.getAbsoluteFilePath()), false);

        Promise<Void> result = Promise.promise();
        blobFuture.onSuccess(blob -> {
            if (blob == null) {
                context.respond(HttpStatus.NOT_FOUND);
                result.complete();
                return;
            }

            Payload payload = blob.getPayload();
            MutableContentMetadata metadata = payload.getContentMetadata();
            String contentType = metadata.getContentType();
            Long length = metadata.getContentLength();

            HttpServerResponse response = context.getResponse()
                    .putHeader(HttpHeaders.CONTENT_TYPE, contentType)
                    // content-length removed by vertx
                    .putHeader(HttpHeaders.CONTENT_LENGTH, length.toString());

            try {
                InputStreamReader stream = new InputStreamReader(proxy.getVertx(), payload.openStream());
                stream.pipeTo(response, result);
                result.future().onFailure(error -> {
                    stream.close();
                    context.getResponse().reset();
                });
            } catch (IOException e) {
                result.fail(e);
            }
        }).onFailure(error -> context.respond(HttpStatus.INTERNAL_SERVER_ERROR,
                "Failed to fetch file with path %s/%s".formatted(resource.getBucketName(), resource.getOriginalPath())));

        return result.future();
    }
}
