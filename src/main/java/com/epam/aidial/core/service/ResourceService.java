package com.epam.aidial.core.service;

import com.epam.aidial.core.data.MetadataBase;
import com.epam.aidial.core.data.ResourceFolderMetadata;
import com.epam.aidial.core.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.BlobStorage;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.Compression;
import com.epam.aidial.core.util.EtagBuilder;
import com.epam.aidial.core.util.EtagHeader;
import com.epam.aidial.core.util.ResourceUtil;
import io.vertx.core.json.JsonObject;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.BlobMetadata;
import org.jclouds.blobstore.domain.PageSet;
import org.jclouds.blobstore.domain.StorageMetadata;
import org.jclouds.blobstore.domain.StorageType;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.annotation.Nullable;

import static com.epam.aidial.core.util.ResourceUtil.CREATED_AT_ATTRIBUTE;
import static com.epam.aidial.core.util.ResourceUtil.UPDATED_AT_ATTRIBUTE;

@Slf4j
public class ResourceService {
    private final BlobStorage blobStore;
    private final LockService lockService;
    private final CacheService cacheService;
    @Getter
    private final int maxSize;

    public ResourceService(
            BlobStorage blobStore,
            CacheService cacheService,
            LockService lockService,
            JsonObject settings) {
        this(blobStore, cacheService, lockService, settings.getInteger("maxSize"));
    }

    /**
     * @param maxSize            - max allowed size in bytes for a resource.
     */
    public ResourceService(
            BlobStorage blobStore,
            CacheService cacheService,
            LockService lockService,
            int maxSize) {
        this.blobStore = blobStore;
        this.lockService = lockService;
        this.cacheService = cacheService;
        this.maxSize = maxSize;
    }

    @Nullable
    public MetadataBase getMetadata(ResourceDescription descriptor, String token, int limit, boolean recursive) {
        return descriptor.isFolder()
                ? getFolderMetadata(descriptor, token, limit, recursive)
                : getResourceMetadata(descriptor);
    }

    public ResourceFolderMetadata getFolderMetadata(ResourceDescription descriptor, String token, int limit, boolean recursive) {
        String blobKey = blobKey(descriptor);
        PageSet<? extends StorageMetadata> set = blobStore.list(blobKey, token, limit, recursive);

        if (set.isEmpty() && !descriptor.isRootFolder()) {
            return null;
        }

        List<MetadataBase> resources = set.stream().map(meta -> {
            Map<String, String> metadata = meta.getUserMetadata();
            String path = meta.getName();
            ResourceDescription description = ResourceDescription.fromDecoded(descriptor, path);

            if (meta.getType() != StorageType.BLOB) {
                return new ResourceFolderMetadata(description);
            }

            Long createdAt = null;
            Long updatedAt = null;

            if (metadata != null) {
                createdAt = metadata.containsKey(CREATED_AT_ATTRIBUTE) ? Long.parseLong(metadata.get(CREATED_AT_ATTRIBUTE)) : null;
                updatedAt = metadata.containsKey(UPDATED_AT_ATTRIBUTE) ? Long.parseLong(metadata.get(UPDATED_AT_ATTRIBUTE)) : null;
            }

            if (createdAt == null && meta.getCreationDate() != null) {
                createdAt = meta.getCreationDate().getTime();
            }

            if (updatedAt == null && meta.getLastModified() != null) {
                updatedAt = meta.getLastModified().getTime();
            }

            return new ResourceItemMetadata(description).setCreatedAt(createdAt).setUpdatedAt(updatedAt);
        }).toList();

        return new ResourceFolderMetadata(descriptor, resources, set.getNextMarker());
    }

    @Nullable
    public ResourceItemMetadata getResourceMetadata(ResourceDescription descriptor) {
        if (descriptor.isFolder()) {
            throw new IllegalArgumentException("Resource folder: " + descriptor.getUrl());
        }

        String redisKey = lockService.redisKey(descriptor);
        String blobKey = blobKey(descriptor);
        CacheService.Result<CacheService.ItemMetadata> result = cacheService.getMetadata(redisKey);

        if (result == null) {
            return blobGet(blobKey, false).map(
                    item -> toResourceItemMetadata(descriptor, item.metadata()));
        }

        return result.map(metadata -> toResourceItemMetadata(descriptor, metadata));
    }

    private static ResourceItemMetadata toResourceItemMetadata(
            ResourceDescription descriptor, CacheService.ItemMetadata metadata) {
        return new ResourceItemMetadata(descriptor)
                .setCreatedAt(metadata.createdAt())
                .setUpdatedAt(metadata.updatedAt())
                .setEtag(metadata.etag());
    }

    public boolean hasResource(ResourceDescription descriptor) {
        String redisKey = lockService.redisKey(descriptor);
        CacheService.Result<CacheService.ItemMetadata> result = cacheService.getMetadata(redisKey);

        if (result == null) {
            String blobKey = blobKey(descriptor);
            return blobExists(blobKey);
        }

        return result.exists();
    }

    @Nullable
    public Pair<ResourceItemMetadata, String> getResourceWithMetadata(ResourceDescription descriptor) {
        return getResourceWithMetadata(descriptor, true);
    }

    @Nullable
    public Pair<ResourceItemMetadata, String> getResourceWithMetadata(ResourceDescription descriptor, boolean lock) {
        String redisKey = lockService.redisKey(descriptor);
        CacheService.Result<CacheService.Item<String>> result = cacheService.getString(redisKey);

        if (result == null) {
            try (var ignore = lock ? lockService.lock(redisKey) : null) {
                result = cacheService.getString(redisKey);

                if (result == null) {
                    String blobKey = blobKey(descriptor);
                    result = blobGet(blobKey, true);
                    cacheService.cacheString(redisKey, result.value());
                }
            }
        }

        return result.map(item ->
                Pair.of(toResourceItemMetadata(descriptor, item.metadata()), item.body()));
    }

    @Nullable
    public String getResource(ResourceDescription descriptor) {
        return getResource(descriptor, true);
    }

    @Nullable
    public String getResource(ResourceDescription descriptor, boolean lock) {
        Pair<ResourceItemMetadata, String> result = getResourceWithMetadata(descriptor, lock);
        return (result == null) ? null : result.getRight();
    }

    public ResourceItemMetadata putResource(
            ResourceDescription descriptor, String body, EtagHeader etag, boolean overwrite) {
        return putResource(descriptor, body, etag, overwrite, true);
    }

    public ResourceItemMetadata putResource(
            ResourceDescription descriptor, String body, EtagHeader etag, boolean overwrite, boolean lock) {
        String redisKey = lockService.redisKey(descriptor);

        try (var ignore = lock ? lockService.lock(redisKey) : null) {
            ResourceItemMetadata metadata = getResourceMetadata(descriptor);

            if (metadata != null) {
                if (!overwrite) {
                    return null;
                }

                etag.validate(metadata.getEtag());
            }

            long updatedAt = System.currentTimeMillis();
            long createdAt = metadata == null ? updatedAt : metadata.getCreatedAt();
            String newEtag = EtagBuilder.generateEtag(body.getBytes());
            CacheService.ItemMetadata newMetadata = CacheService.ItemMetadata.builder()
                    .etag(newEtag)
                    .createdAt(createdAt)
                    .updatedAt(updatedAt)
                    .contentType("application/json")
                    .build();
            CacheService.Item<String> item = new CacheService.Item<>(newMetadata, body);
            cacheService.saveString(redisKey, item);

            if (metadata == null) {
                // create an empty object for listing
                cacheService.saveStub(redisKey, newMetadata);
            }

            return toResourceItemMetadata(descriptor, newMetadata);
        }
    }

    public void computeResource(ResourceDescription descriptor, Function<String, String> fn) {
        String redisKey = lockService.redisKey(descriptor);

        try (var ignore = lockService.lock(redisKey)) {
            String oldBody = getResource(descriptor, false);
            String newBody = fn.apply(oldBody);
            if (newBody != null) {
                // update resource only if body changed
                if (!newBody.equals(oldBody)) {
                    putResource(descriptor, newBody, EtagHeader.ANY, true, false);
                }
            }
        }
    }

    public boolean deleteResource(ResourceDescription descriptor, EtagHeader etag) {
        String redisKey = lockService.redisKey(descriptor);
        String blobKey = blobKey(descriptor);

        try (var ignore = lockService.lock(redisKey)) {
            CacheService.Result<CacheService.ItemMetadata> result = cacheService.getMetadata(redisKey);
            boolean exists = (result == null) ? blobExists(blobKey) : result.exists();

            if (!exists) {
                return false;
            }

            etag.validate(result.value().etag());

            cacheService.delete(redisKey);

            return true;
        }
    }

    public boolean copyResource(ResourceDescription from, ResourceDescription to) {
        return copyResource(from, to, true);
    }

    public boolean copyResource(ResourceDescription from, ResourceDescription to, boolean overwrite) {
        String body = getResource(from);

        if (body == null) {
            return false;
        }

        ResourceItemMetadata metadata = putResource(to, body, EtagHeader.ANY, overwrite);
        return metadata != null;
    }

    private boolean blobExists(String key) {
        return blobStore.exists(key);
    }

    @SneakyThrows
    private CacheService.Result<CacheService.Item<String>> blobGet(String key, boolean withBody) {
        Blob blob = null;
        BlobMetadata meta;

        if (withBody) {
            blob = blobStore.load(key);
            meta = (blob == null) ? null : blob.getMetadata();
        } else {
            meta = blobStore.meta(key);
        }

        if (meta == null) {
            return CacheService.Result.empty();
        }

        String etag = ResourceUtil.extractEtag(meta.getUserMetadata());
        long createdAt = Long.parseLong(meta.getUserMetadata().get(CREATED_AT_ATTRIBUTE));
        long updatedAt = Long.parseLong(meta.getUserMetadata().get(UPDATED_AT_ATTRIBUTE));

        String body = "";

        if (blob != null) {
            String encoding = meta.getContentMetadata().getContentEncoding();
            try (InputStream stream = blob.getPayload().openStream()) {
                byte[] payload = stream.readAllBytes();
                if (encoding != null) {
                    payload = Compression.decompress(encoding, payload);
                }
                body = new String(payload, StandardCharsets.UTF_8);
            }
        }

        return new CacheService.Result<>(new CacheService.Item<>(
                CacheService.ItemMetadata.builder()
                        .etag(etag)
                        .createdAt(createdAt)
                        .updatedAt(updatedAt)
                        .build(),
                body));
    }

    private static String blobKey(ResourceDescription descriptor) {
        return descriptor.getAbsoluteFilePath();
    }
}