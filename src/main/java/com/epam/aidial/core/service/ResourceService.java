package com.epam.aidial.core.service;

import com.epam.aidial.core.data.MetadataBase;
import com.epam.aidial.core.data.ResourceFolderMetadata;
import com.epam.aidial.core.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.BlobStorage;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.Compression;
import io.vertx.core.Vertx;
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
import org.redisson.api.RMap;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import javax.annotation.Nullable;

@Slf4j
public class ResourceService implements AutoCloseable {
    private static final Set<String> REDIS_FIELDS = Set.of("body", "created_at", "updated_at", "synced", "exists");
    private static final Set<String> REDIS_FIELDS_NO_BODY = Set.of("created_at", "updated_at", "synced", "exists");

    private static final Result BLOB_NOT_FOUND = new Result("", Long.MIN_VALUE, Long.MIN_VALUE, true, false);

    private final Vertx vertx;
    private final RedissonClient redis;
    private final BlobStorage blobStore;
    private final LockService lockService;
    @Getter
    private final int maxSize;
    private final long syncTimer;
    private final long syncDelay;
    private final int syncBatch;
    private final Duration cacheExpiration;
    private final int compressionMinSize;
    private final String prefix;
    private final String resourceQueue;

    public ResourceService(Vertx vertx,
                           RedissonClient redis,
                           BlobStorage blobStore,
                           LockService lockService,
                           JsonObject settings,
                           String prefix) {
        this(vertx, redis, blobStore, lockService,
                settings.getInteger("maxSize"),
                settings.getLong("syncPeriod"),
                settings.getLong("syncDelay"),
                settings.getInteger("syncBatch"),
                settings.getLong("cacheExpiration"),
                settings.getInteger("compressionMinSize"),
                prefix
        );
    }

    /**
     * @param maxSize            - max allowed size in bytes for a resource.
     * @param syncPeriod         - period in milliseconds, how frequently check for resources to sync.
     * @param syncDelay          - delay in milliseconds for a resource to be written back in object storage after last modification.
     * @param syncBatch          - how many resources to sync in one go.
     * @param cacheExpiration    - expiration in milliseconds for synced resources in Redis.
     * @param compressionMinSize - compress resources with gzip if their size in bytes more or equal to this value.
     */
    public ResourceService(Vertx vertx,
                           RedissonClient redis,
                           BlobStorage blobStore,
                           LockService lockService,
                           int maxSize,
                           long syncPeriod,
                           long syncDelay,
                           int syncBatch,
                           long cacheExpiration,
                           int compressionMinSize,
                           String prefix) {
        this.vertx = vertx;
        this.redis = redis;
        this.blobStore = blobStore;
        this.lockService = lockService;
        this.maxSize = maxSize;
        this.syncDelay = syncDelay;
        this.syncBatch = syncBatch;
        this.cacheExpiration = Duration.ofMillis(cacheExpiration);
        this.compressionMinSize = compressionMinSize;
        this.prefix = prefix;
        this.resourceQueue = "resource:" + BlobStorageUtil.toStoragePath(prefix, "queue");

        // vertex timer is called from event loop, so sync is done in worker thread to not block event loop
        this.syncTimer = vertx.setPeriodic(syncPeriod, syncPeriod, ignore -> vertx.executeBlocking(() -> sync()));
    }

    @Override
    public void close() {
        vertx.cancelTimer(syncTimer);
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
                createdAt = metadata.containsKey("created_at") ? Long.parseLong(metadata.get("created_at")) : null;
                updatedAt = metadata.containsKey("updated_at") ? Long.parseLong(metadata.get("updated_at")) : null;
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

        String redisKey = redisKey(descriptor);
        String blobKey = blobKey(descriptor);
        Result result = redisGet(redisKey, false);

        if (result == null) {
            result = blobGet(blobKey, false);
        }

        if (!result.exists) {
            return null;
        }

        return new ResourceItemMetadata(descriptor)
                .setCreatedAt(result.createdAt)
                .setUpdatedAt(result.updatedAt);
    }

    public boolean hasResource(ResourceDescription descriptor) {
        String redisKey = redisKey(descriptor);
        Result result = redisGet(redisKey, false);

        if (result == null) {
            String blobKey = blobKey(descriptor);
            return blobExists(blobKey);
        }

        return result.exists;
    }

    @Nullable
    public Pair<ResourceItemMetadata, String> getResourceWithMetadata(ResourceDescription descriptor) {
        return getResourceWithMetadata(descriptor, true);
    }

    @Nullable
    public Pair<ResourceItemMetadata, String> getResourceWithMetadata(ResourceDescription descriptor, boolean lock) {
        String redisKey = redisKey(descriptor);
        Result result = redisGet(redisKey, true);

        if (result == null) {
            try (var ignore = lock ? lockService.lock(redisKey) : null) {
                result = redisGet(redisKey, true);

                if (result == null) {
                    String blobKey = blobKey(descriptor);
                    result = blobGet(blobKey, true);
                    redisPut(redisKey, result);
                }
            }
        }

        if (result.exists) {
            ResourceItemMetadata metadata = new ResourceItemMetadata(descriptor)
                    .setCreatedAt(result.createdAt)
                    .setUpdatedAt(result.updatedAt);

            return Pair.of(metadata, result.body);
        }

        return null;
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

    public ResourceItemMetadata putResource(ResourceDescription descriptor, String body, boolean overwrite) {
        return putResource(descriptor, body, overwrite, true);
    }

    public ResourceItemMetadata putResource(ResourceDescription descriptor, String body,
                                            boolean overwrite, boolean lock) {
        String redisKey = redisKey(descriptor);
        String blobKey = blobKey(descriptor);

        try (var ignore = lock ? lockService.lock(redisKey) : null) {
            Result result = redisGet(redisKey, false);
            if (result == null) {
                result = blobGet(blobKey, false);
            }

            if (result.exists && !overwrite) {
                return null;
            }

            long updatedAt = time();
            long createdAt = result.exists ? result.createdAt : updatedAt;
            redisPut(redisKey, new Result(body, createdAt, updatedAt, false, true));

            if (!result.exists) {
                blobPut(blobKey, "", createdAt, updatedAt); // create an empty object for listing
            }

            return new ResourceItemMetadata(descriptor).setCreatedAt(createdAt).setUpdatedAt(updatedAt);
        }
    }

    public void computeResource(ResourceDescription descriptor, Function<String, String> fn) {
        String redisKey = redisKey(descriptor);

        try (var ignore = lockService.lock(redisKey)) {
            String oldBody = getResource(descriptor, false);
            String newBody = fn.apply(oldBody);
            if (newBody != null) {
                // update resource only if body changed
                if (!newBody.equals(oldBody)) {
                    putResource(descriptor, newBody, true, false);
                }
            }
        }
    }

    public boolean deleteResource(ResourceDescription descriptor) {
        String redisKey = redisKey(descriptor);
        String blobKey = blobKey(descriptor);

        try (var ignore = lockService.lock(redisKey)) {
            Result result = redisGet(redisKey, false);
            boolean existed = (result == null) ? blobExists(blobKey) : result.exists;

            if (!existed) {
                return false;
            }

            redisPut(redisKey, new Result("", Long.MIN_VALUE, Long.MIN_VALUE, false, false));
            blobDelete(blobKey);
            redisSync(redisKey);

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

        ResourceItemMetadata metadata = putResource(to, body, overwrite);
        return metadata != null;
    }

    private Void sync() {
        log.debug("Syncing");
        try {
            RScoredSortedSet<String> set = redis.getScoredSortedSet(resourceQueue, StringCodec.INSTANCE);
            long now = time();

            for (String redisKey : set.valueRange(Double.NEGATIVE_INFINITY, true, now, true, 0, syncBatch)) {
                sync(redisKey);
            }
        } catch (Throwable e) {
            log.warn("Failed to sync:", e);
        }

        return null;
    }

    private void sync(String redisKey) {
        log.debug("Syncing resource: {}", redisKey);
        try (var lock = lockService.tryLock(redisKey)) {
            if (lock == null) {
                return;
            }

            Result result = redisGet(redisKey, false);
            if (result == null || result.synced) {
                RMap<Object, Object> map = redis.getMap(redisKey, StringCodec.INSTANCE);
                long ttl = map.remainTimeToLive();
                // according to the documentation, -1 means expiration is not set
                if (ttl == -1) {
                    map.expire(cacheExpiration);
                }
                redis.getScoredSortedSet(resourceQueue, StringCodec.INSTANCE).remove(redisKey);
                return;
            }

            String blobKey = blobKeyFromRedisKey(redisKey);
            if (result.exists) {
                log.debug("Syncing resource: {}. Blob updating", redisKey);
                result = redisGet(redisKey, true);
                blobPut(blobKey, result.body, result.createdAt, result.updatedAt);
            } else {
                log.debug("Syncing resource: {}. Blob deleting", redisKey);
                blobDelete(blobKey);
            }

            redisSync(redisKey);
        } catch (Throwable e) {
            log.warn("Failed to sync resource: {}", redisKey, e);
        }
    }

    private boolean blobExists(String key) {
        return blobStore.exists(key);
    }

    @SneakyThrows
    private Result blobGet(String key, boolean withBody) {
        Blob blob = null;
        BlobMetadata meta;

        if (withBody) {
            blob = blobStore.load(key);
            meta = (blob == null) ? null : blob.getMetadata();
        } else {
            meta = blobStore.meta(key);
        }

        if (meta == null) {
            return BLOB_NOT_FOUND;
        }

        long createdAt = Long.parseLong(meta.getUserMetadata().get("created_at"));
        long updatedAt = Long.parseLong(meta.getUserMetadata().get("updated_at"));

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

        return new Result(body, createdAt, updatedAt, true, true);
    }

    private void blobPut(String key, String body, long createdAt, long updatedAt) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String encoding = null;

        if (bytes.length >= compressionMinSize) {
            encoding = "gzip";
            bytes = Compression.compress(encoding, bytes);
        }

        Map<String, String> metadata = Map.of(
                "created_at", Long.toString(createdAt),
                "updated_at", Long.toString(updatedAt)
        );
        blobStore.store(key, "application/json", encoding, metadata, bytes);
    }

    private void blobDelete(String key) {
        blobStore.delete(key);
    }

    private static String blobKey(ResourceDescription descriptor) {
        return descriptor.getAbsoluteFilePath();
    }

    private String blobKeyFromRedisKey(String redisKey) {
        // redis key may have prefix, we need to subtract it, because BlobStore manage prefix on its own
        int delimiterIndex = redisKey.indexOf(":");
        int prefixChars = prefix != null ? prefix.length() + 1 : 0;
        return redisKey.substring(prefixChars + delimiterIndex + 1);
    }

    @Nullable
    private Result redisGet(String key, boolean withBody) {
        RMap<String, String> map = redis.getMap(key, StringCodec.INSTANCE);
        Map<String, String> fields = map.getAll(withBody ? REDIS_FIELDS : REDIS_FIELDS_NO_BODY);

        if (fields.isEmpty()) {
            return null;
        }

        String body = fields.get("body");
        long createdAt = Long.parseLong(fields.get("created_at"));
        long updatedAt = Long.parseLong(fields.get("updated_at"));
        boolean synced = Boolean.parseBoolean(Objects.requireNonNull(fields.get("synced")));
        boolean exists = Boolean.parseBoolean(Objects.requireNonNull(fields.get("exists")));

        return new Result(body, createdAt, updatedAt, synced, exists);
    }

    private void redisPut(String key, Result result) {
        RScoredSortedSet<String> set = redis.getScoredSortedSet(resourceQueue, StringCodec.INSTANCE);
        set.add(time() + syncDelay, key); // add resource to sync set before changing because calls below can fail

        RMap<String, String> map = redis.getMap(key, StringCodec.INSTANCE);

        if (!result.synced) {
            map.clearExpire();
        }

        Map<String, String> fields = Map.of(
                "body", result.body,
                "created_at", Long.toString(result.createdAt),
                "updated_at", Long.toString(result.updatedAt),
                "synced", Boolean.toString(result.synced),
                "exists", Boolean.toString(result.exists)
        );
        map.putAll(fields);

        if (result.synced) { // cleanup because it is already synced
            map.expire(cacheExpiration);
            set.remove(key);
        }
    }

    private void redisSync(String key) {
        RMap<String, String> map = redis.getMap(key, StringCodec.INSTANCE);
        map.put("synced", "true");
        map.expire(cacheExpiration);

        RScoredSortedSet<String> set = redis.getScoredSortedSet(resourceQueue, StringCodec.INSTANCE);
        set.remove(key);
    }

    private String redisKey(ResourceDescription descriptor) {
        String resourcePath = BlobStorageUtil.toStoragePath(prefix, descriptor.getAbsoluteFilePath());
        return descriptor.getType().name().toLowerCase() + ":" + resourcePath;
    }

    private static long time() {
        return System.currentTimeMillis();
    }

    private record Result(String body, long createdAt, long updatedAt, boolean synced, boolean exists) {
    }
}