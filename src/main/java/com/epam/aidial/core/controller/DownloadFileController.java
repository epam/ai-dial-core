package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.storage.InputStreamReader;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.storage.ResourceType;
import com.epam.aidial.core.util.HttpStatus;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.io.MutableContentMetadata;
import org.jclouds.io.Payload;

import java.io.IOException;

@Slf4j
@AllArgsConstructor
public class DownloadFileController {

    private final Proxy proxy;
    private final ProxyContext context;

    /**
     * Downloads file content from provided path.
     * Path can be either absolute or relative.
     * Path type determined by "path" query parameter which can be "absolute" or "relative"(default value)
     *
     * @param filePath file path; absolute or relative
     */
    public Future<?> download(String bucket, String filePath) {
        String expectedUserBucket = BlobStorageUtil.buildUserBucket(context);
        String decryptedBucket = proxy.getEncryptionService().decrypt(bucket);

        if (!expectedUserBucket.equals(decryptedBucket)) {
            return context.respond(HttpStatus.FORBIDDEN, "You don't have an access to the bucket " + bucket);
        }

        ResourceDescription resource = ResourceDescription.from(ResourceType.FILES, bucket, decryptedBucket, filePath);

        Future<Blob> blobFuture = proxy.getVertx().executeBlocking(() ->
                proxy.getStorage().load(resource.getAbsoluteFilePath()));

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
                "Failed to fetch file with path %s/%s".formatted(bucket, filePath)));

        return result.future();
    }
}
