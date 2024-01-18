package com.epam.aidial.core.service;

import com.epam.aidial.core.data.MetadataBase;
import com.epam.aidial.core.data.ResourceFolderMetadata;
import com.epam.aidial.core.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.BlobStorage;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.Compression;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.BlobMetadata;
import org.jclouds.blobstore.domain.PageSet;
import org.jclouds.blobstore.domain.StorageMetadata;
import org.jclouds.blobstore.domain.StorageType;
import org.redisson.api.RLock;
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
import java.util.concurrent.locks.Lock;
import javax.annotation.Nullable;

@Slf4j
public class ResourceService implements AutoCloseable {

    private static final String BLOB_EXTENSION = ".json";
    private static final String REDIS_QUEUE = "resource:queue";
    private static final String REDIS_LOCK = "lock:";
    private static final Set<String> REDIS_FIELDS = Set.of("body", "created_at", "updated_at", "synced", "exists");
    private static final Set<String> REDIS_FIELDS_NO_BODY = Set.of("created_at", "updated_at", "synced", "exists");

    private final Vertx vertx;
    private final RedissonClient redisCache;
    private final RedissonClient redisStore;
    private final BlobStorage blobStore;
    @Getter
    private final int maxSize;
    private final long syncTimer;
    private final long syncDelay;
    private final int syncBatch;
    private final Duration cacheExpiration;
    private final int compressionMinSize;

    public ResourceService(Vertx vertx,
                           RedissonClient redisCache,
                           RedissonClient redisStore,
                           BlobStorage blobStore,
                           JsonObject settings) {
        this(vertx, redisCache, redisStore, blobStore,
                settings.getInteger("maxSize"),
                settings.getLong("syncPeriod"),
                settings.getLong("syncDelay"),
                settings.getInteger("syncBatch"),
                settings.getLong("cacheExpiration"),
                settings.getInteger("compressionMinSize")
        );
    }

    public ResourceService(Vertx vertx,
                           RedissonClient redisCache,
                           RedissonClient redisStore,
                           BlobStorage blobStore,
                           int maxSize,
                           long syncPeriod,
                           long syncDelay,
                           int syncBatch,
                           long cacheExpiration,
                           int compressionMinSize) {
        this.vertx = vertx;
        this.redisCache = redisCache;
        this.redisStore = redisStore;
        this.blobStore = blobStore;
        this.maxSize = maxSize;
        this.syncDelay = syncDelay;
        this.syncBatch = syncBatch;
        this.cacheExpiration = Duration.ofMillis(cacheExpiration);
        this.compressionMinSize = compressionMinSize;

        // vertex timer is called from event loop, so sync is done in worker thread to not block event loop
        this.syncTimer = vertx.setPeriodic(syncPeriod, syncPeriod, ignore -> vertx.executeBlocking(() -> sync()));
    }

    @Override
    public void close() {
        vertx.cancelTimer(syncTimer);
    }

    @Nullable
    public MetadataBase getMetadata(ResourceDescription descriptor, String token, int limit) {
        return descriptor.isFolder()
                ? getFolderMetadata(descriptor, token, limit)
                : getItemMetadata(descriptor);
    }

    private ResourceFolderMetadata getFolderMetadata(ResourceDescription descriptor, String token, int limit) {
        String blobKey = blobKey(descriptor);
        PageSet<? extends StorageMetadata> set = blobStore.list(blobKey, token, limit);

        if (set.isEmpty()) {
            return null;
        }

        List<MetadataBase> resources = set.stream().map(meta -> {
            Map<String, String> metadata = meta.getUserMetadata();
            String path = fromBlobKey(meta.getName());
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

        return new ResourceFolderMetadata(descriptor, resources).setNextToken(set.getNextMarker());
    }

    private ResourceItemMetadata getItemMetadata(ResourceDescription descriptor) {
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

    @Nullable
    public String getResource(ResourceDescription descriptor) {
        String redisKey = redisKey(descriptor);
        Result result = redisGet(redisKey, true);

        if (result == null) {
            try (Locker lock = redisLock(redisKey)) {
                result = redisGet(redisKey, true);

                if (result == null) {
                    String blobKey = blobKey(descriptor);
                    result = blobGet(blobKey, true);
                    redisPut(redisKey, result);
                }
            }
        }

        return result.exists ? result.body : null;
    }

    public boolean putResource(ResourceDescription descriptor, String body) {
        String redisKey = redisKey(descriptor);
        String blobKey = blobKey(descriptor);

        try (Locker lock = redisLock(redisKey)) {
            Result result = redisGet(redisKey, false);
            if (result == null) {
                result = blobGet(blobKey, false);
            }

            long updatedAt = time();
            long createdAt = result.exists ? result.createdAt : updatedAt;
            redisPut(redisKey, new Result(body, createdAt, updatedAt, false, true));

            if (!result.exists) {
                blobPut(blobKey, "", createdAt, updatedAt);
            }
        }

        return true;
    }

    public boolean deleteResource(ResourceDescription descriptor) {
        String redisKey = redisKey(descriptor);
        String blobKey = blobKey(descriptor);

        try (Locker lock = redisLock(redisKey)) {
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

    private Void sync() {
        log.debug("Syncing");
        try {
            RScoredSortedSet<String> set = redisCache.getScoredSortedSet(REDIS_QUEUE, StringCodec.INSTANCE);
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
        try (Locker lock = redisTryLock(redisKey)) {
            if (lock == null) {
                return;
            }

            Result result = redisGet(redisKey, false);
            if (result == null || result.synced) {
                redisCache.getMap(redisKey, StringCodec.INSTANCE).expireIfNotSet(cacheExpiration);
                redisCache.getScoredSortedSet(REDIS_QUEUE, StringCodec.INSTANCE).remove(redisKey);
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
            return new Result("", Long.MIN_VALUE, Long.MIN_VALUE, true, false);
        }

        long createdAt = Long.parseLong(meta.getUserMetadata().get("created_at"));
        long updatedAt = Long.parseLong(meta.getUserMetadata().get("updated_at"));

        String body = "";

        if (blob != null) {
            String encoding = meta.getContentMetadata().getContentEncoding();
            try (InputStream stream = blob.getPayload().openStream()) {
                byte[] bytes = (encoding == null) ? stream.readAllBytes() : Compression.decompress(encoding, stream);
                body = new String(bytes, StandardCharsets.UTF_8);
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
        String path = descriptor.getAbsoluteFilePath();
        return descriptor.isFolder() ? path : (path + BLOB_EXTENSION);
    }

    private static String blobKeyFromRedisKey(String redisKey) {
        int i = redisKey.indexOf(":");
        return redisKey.substring(i + 1) + BLOB_EXTENSION;
    }

    private static String fromBlobKey(String blobKey) {
        return blobKey.endsWith(BLOB_EXTENSION) ? blobKey.substring(0, blobKey.length() - BLOB_EXTENSION.length()) : blobKey;
    }

    @Nullable
    private Result redisGet(String key, boolean withBody) {
        RMap<String, String> map = redisCache.getMap(key, StringCodec.INSTANCE);
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
        RScoredSortedSet<String> set = redisCache.getScoredSortedSet(REDIS_QUEUE, StringCodec.INSTANCE);
        set.add(time() + syncDelay, key);

        RMap<String, String> map = redisCache.getMap(key, StringCodec.INSTANCE);

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

        if (result.synced) {
            map.expire(cacheExpiration);
            set.remove(key);
        }
    }

    private void redisSync(String key) {
        RMap<String, String> map = redisCache.getMap(key, StringCodec.INSTANCE);
        map.put("synced", "true");
        map.expire(cacheExpiration);

        RScoredSortedSet<String> set = redisCache.getScoredSortedSet(REDIS_QUEUE, StringCodec.INSTANCE);
        set.remove(key);
    }

    private Locker redisLock(String key) {
        String lockKey = REDIS_LOCK + key;
        RLock lock = redisStore.getLock(lockKey);
        lock.lock();
        return new Locker(lock);
    }

    @Nullable
    private Locker redisTryLock(String key) {
        String lockKey = REDIS_LOCK + key;
        RLock lock = redisStore.getLock(lockKey);
        return lock.tryLock() ? new Locker(lock) : null;
    }

    private static String redisKey(ResourceDescription descriptor) {
        return descriptor.getType().name().toLowerCase() + ":" + descriptor.getAbsoluteFilePath();
    }

    private static long time() {
        return System.currentTimeMillis();
    }

    private record Result(String body, long createdAt, long updatedAt, boolean synced, boolean exists) {
    }

    private record Locker(Lock lock) implements AutoCloseable {
        @Override
        public void close() {
            lock.unlock();
        }
    }
}