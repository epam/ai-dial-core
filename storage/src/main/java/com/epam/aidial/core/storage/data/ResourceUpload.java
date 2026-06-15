package com.epam.aidial.core.storage.data;

import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.util.EtagBuilder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import lombok.Data;
import org.jclouds.blobstore.domain.MultipartPart;
import org.jclouds.blobstore.domain.MultipartUpload;
import org.jclouds.io.Payload;
import org.jclouds.io.payloads.InputStreamPayload;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
public class ResourceUpload {
    private final MultipartUpload multipartUpload;
    private final BlobStorage blobStorage;
    private final List<MultipartPart> parts = new ArrayList<>();
    private final String contentType;
    private final Map<String, String> userMetadata;
    private final EtagBuilder etagBuilder;
    /**
     * Temporary blob path the multipart upload is assembled at before being moved to the target resource.
     */
    private final String tempPath;
    private long contentLength;
    private int chunkNumber = 0;
    private String etag;

    public ResourceUpload(BlobStorage blobStorage, MultipartUpload mpu, String contentType,
                          Map<String, String> userMetadata, String tempPath) {
        this.multipartUpload = mpu;
        this.blobStorage = blobStorage;
        this.contentType = contentType;
        this.userMetadata = userMetadata;
        this.tempPath = tempPath;
        this.etagBuilder = new EtagBuilder();
    }

    public void addChunk(ByteBuf chunk) throws IOException {
        try (Payload payload = bufferToPayload(chunk.duplicate())) {
            MultipartPart part = blobStorage.storeMultipartPart(multipartUpload, ++chunkNumber, payload);
            parts.add(part);
        }
        contentLength += chunk.readableBytes();
        etagBuilder.append(chunk.nioBuffer());
    }

    public void abort() {
        blobStorage.abortMultipartUpload(multipartUpload);
    }

    private static Payload bufferToPayload(ByteBuf buffer) {
        Payload payload = new InputStreamPayload(new ByteBufInputStream(buffer));
        // Content length is required by S3BlobStore
        payload.getContentMetadata().setContentLength((long) buffer.readableBytes());

        return payload;
    }

    public String calculateEtag() {
        if (etag == null) {
            etag = etagBuilder.build();
        }
        return etag;
    }
}
