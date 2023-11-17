package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.storage.InputStreamReader;
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

    private static final String PATH_TYPE_QUERY_PARAMETER = "path";
    private static final String ABSOLUTE_PATH_TYPE = "absolute";

    static final String PURPOSE_FILE_QUERY_PARAMETER = "purpose";

    static final String QUERY_METADATA_QUERY_PARAMETER_VALUE = "metadata";

    private final Proxy proxy;
    private final ProxyContext context;

    /**
     * Downloads file content from provided path.
     * Path can be either absolute or relative.
     * Path type determined by "path" query parameter which can be "absolute" or "relative"(default value)
     *
     * @param path file path; absolute or relative
     */
    public Future<?> download(String path) {
        String pathType = context.getRequest().params().get(PATH_TYPE_QUERY_PARAMETER);
        String absoluteFilePath;
        if (!ABSOLUTE_PATH_TYPE.equals(pathType)) {
            absoluteFilePath = BlobStorageUtil.buildAbsoluteFilePath(context, path);
        } else {
            absoluteFilePath = path;
        }
        Future<Blob> blobFuture = proxy.getVertx().executeBlocking(() ->
                proxy.getStorage().load(BlobStorageUtil.removeLeadingAndTrailingPathSeparators(absoluteFilePath)));

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
                "Failed to fetch file with ID " + path));

        return result.future();
    }
}
