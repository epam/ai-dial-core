package com.epam.aidial.core.storage.blobstore;

import java.util.Map;

/**
 * The full content metadata and user metadata of a blob, used by {@link BlobStorage#upload} and
 * {@link BlobStorage#initMultipartUpload(String, BlobAttributes)} to store a blob exactly as given, rather than the
 * narrower subset {@link BlobStorage#store} takes.
 */
public record BlobAttributes(
        String contentType,
        String contentEncoding,
        String contentDisposition,
        String contentLanguage,
        String cacheControl,
        Map<String, String> userMetadata) {
}
