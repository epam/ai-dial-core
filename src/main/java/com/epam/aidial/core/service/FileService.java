package com.epam.aidial.core.service;

import com.epam.aidial.core.data.FileMetadata;
import com.epam.aidial.core.data.MetadataBase;
import com.epam.aidial.core.storage.BlobStorage;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.EtagHeader;
import com.epam.aidial.core.util.ResourceUtil;
import lombok.AllArgsConstructor;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.BlobMetadata;
import org.jclouds.blobstore.domain.MultipartPart;
import org.jclouds.blobstore.domain.MultipartUpload;
import org.jclouds.io.MutableContentMetadata;
import org.jclouds.io.Payload;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

@AllArgsConstructor
public class FileService {
    private static final int MAX_CACHE_ITEM_IN_BYTES = 5 * 1024 * 1024;

    private final BlobStorage blobStorage;
    private final CacheService cacheService;
    private final LockService lockService;

    public FileStream getFile(ResourceDescription resource) throws IOException {
        String key = lockService.redisKey(resource);
        CacheService.Result<CacheService.Item<byte[]>> cacheItem = cacheService.getBytes(key);
        if (cacheItem != null) {
            return cacheItem.map(FileStream::fromCacheItem);
        }

        try (LockService.Lock ignored = lockService.lock(key)) {
            cacheItem = cacheService.getBytes(key);
            if (cacheItem != null) {
                return cacheItem.map(FileStream::fromCacheItem);
            }

            Blob blob = blobStorage.load(resource.getAbsoluteFilePath());
            if (blob == null) {
                cacheService.cacheMissing(key);
                return null;
            }

            Payload payload = blob.getPayload();
            MutableContentMetadata metadata = payload.getContentMetadata();
            String etag = ResourceUtil.extractEtag(blob.getMetadata().getUserMetadata());
            String contentType = metadata.getContentType();
            Long length = metadata.getContentLength();

            if (length <= MAX_CACHE_ITEM_IN_BYTES) {
                try (InputStream inputStream = payload.openStream()) {
                    CacheService.Item<byte[]> item = new CacheService.Item<>(
                            CacheService.ItemMetadata.builder()
                                    .etag(etag)
                                    .contentType(contentType)
                                    .contentLength(length)
                                    .build(),
                            inputStream.readAllBytes());
                    cacheService.cacheBytes(key, item);
                    return FileStream.fromCacheItem(item);
                }
            }

            return new FileStream(payload.openStream(), etag, contentType, length);
        }
    }

    @Nullable
    public String getEtag(ResourceDescription resource) {
        FileMetadata metadata = getMetadata(resource);
        if (metadata == null) {
            return null;
        }

        return metadata.getEtag();
    }

    public FileMetadata getMetadata(ResourceDescription resource) {
        String key = lockService.redisKey(resource);
        CacheService.Result<CacheService.ItemMetadata> metadata = cacheService.getMetadata(key);
        if (metadata != null) {
            return fileMetadataFromCacheItemMetadata(resource, metadata);
        }

        BlobMetadata blobMetadata = blobStorage.meta(resource.getAbsoluteFilePath());
        if (blobMetadata == null) {
            return null;
        }

        String contentType = blobMetadata.getContentMetadata().getContentType();
        long contentLength = blobMetadata.getContentMetadata().getContentLength();
        String etag = ResourceUtil.extractEtag(blobMetadata.getUserMetadata());

        return (FileMetadata) new FileMetadata(resource, contentLength, contentType).setEtag(etag);
    }

    public void putFile(
            ResourceDescription resource, byte[] bytes, String contentType, String newEtag, EtagHeader etag) {
        String key = lockService.redisKey(resource);
        try (LockService.Lock ignored = lockService.lock(key)) {
            FileMetadata metadata = getMetadata(resource);
            if (metadata != null) {
                etag.validate(metadata.getEtag());
            }

            CacheService.ItemMetadata newMetadata = CacheService.ItemMetadata.builder()
                    .etag(newEtag)
                    .contentType(contentType)
                    .contentLength((long) bytes.length)
                    .build();
            CacheService.Item<byte[]> item = new CacheService.Item<>(newMetadata, bytes);
            cacheService.saveBytes(key, item);
            if (metadata == null) {
                // create an empty object for listing
                cacheService.saveStub(key, newMetadata);
            }
        }
    }

    public void putFile(ResourceDescription resource, MultipartUpload mpu, List<MultipartPart> parts, EtagHeader etag) {
        String key = lockService.redisKey(resource);
        try (LockService.Lock ignored = lockService.lock(key)) {
            etag.validate(() -> getEtag(resource));

            cacheService.flush(key);
            blobStorage.completeMultipartUpload(mpu, parts);
        }
    }

    public void deleteFile(ResourceDescription resource, EtagHeader etag) {
        String key = lockService.redisKey(resource);
        try (LockService.Lock ignored = lockService.lock(key)) {
            etag.validate(() -> getEtag(resource));

            cacheService.delete(key);
            cacheService.flush(key);
        }
    }

    private static FileMetadata fileMetadataFromCacheItemMetadata(
            ResourceDescription resource, CacheService.Result<CacheService.ItemMetadata> result) {
        if (!result.exists()) {
            return null;
        }
        CacheService.ItemMetadata metadata = result.value();
        return (FileMetadata) new FileMetadata(resource, metadata.contentLength(), metadata.contentType())
                .setEtag(metadata.etag());
    }

    public record FileStream(InputStream inputStream, String etag, String contentType, long contentLength)
            implements Closeable {

        @Override
        public void close() throws IOException {
            inputStream.close();
        }

        private static FileStream fromCacheItem(CacheService.Item<byte[]> item) {
            byte[] body = item.body();
            CacheService.ItemMetadata metadata = item.metadata();
            return new FileStream(
                    new ByteArrayInputStream(body),
                    metadata.etag(),
                    metadata.contentType(),
                    body.length);
        }
    }
}
