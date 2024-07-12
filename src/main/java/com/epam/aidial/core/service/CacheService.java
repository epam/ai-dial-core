package com.epam.aidial.core.service;

import com.epam.aidial.core.storage.BlobStorage;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.util.Compression;
import com.epam.aidial.core.util.ResourceUtil;
import com.google.common.collect.Sets;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RMap;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import javax.annotation.Nullable;


@Slf4j
public class CacheService implements AutoCloseable {
    private static final String BODY_ATTRIBUTE = "body";
    public static final String CONTENT_TYPE_ATTRIBUTE = "content_type";
    public static final String CONTENT_LENGTH_ATTRIBUTE = "content_length";
    private static final String BINARY_ATTRIBUTE = "binary";
    private static final String SYNCED_ATTRIBUTE = "synced";
    private static final String EXISTS_ATTRIBUTE = "exists";
    private static final Set<String> REDIS_FIELDS_NO_BODY = Set.of(
            ResourceUtil.ETAG_ATTRIBUTE,
            ResourceUtil.CREATED_AT_ATTRIBUTE,
            ResourceUtil.UPDATED_AT_ATTRIBUTE,
            CONTENT_TYPE_ATTRIBUTE,
            CONTENT_LENGTH_ATTRIBUTE,
            BINARY_ATTRIBUTE,
            SYNCED_ATTRIBUTE,
            EXISTS_ATTRIBUTE);
    private static final Set<String> REDIS_FIELDS = Sets.union(
            Set.of(BODY_ATTRIBUTE),
            REDIS_FIELDS_NO_BODY);

    private static final Base64.Encoder BASE64_ENCODER = Base64.getEncoder();
    private static final Base64.Decoder BASE64_DECODER = Base64.getDecoder();
    private static final Record NOT_FOUND = new Record(null, false, true);
    private static final Record DELETED = new Record(null, false, false);

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

    public CacheService(Vertx vertx,
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
    public CacheService(Vertx vertx,
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

    private Void sync() {
        log.debug("Syncing");
        try {
            RScoredSortedSet<String> set = redis.getScoredSortedSet(resourceQueue, StringCodec.INSTANCE);
            long now = time();

            for (String redisKey : set.valueRange(Double.NEGATIVE_INFINITY, true, now, true, 0, syncBatch)) {
                try (var lock = lockService.tryLock(redisKey)) {
                    if (lock == null) {
                        continue;
                    }

                    sync(redisKey);
                } catch (Throwable e) {
                    log.warn("Failed to sync resource: {}", redisKey, e);
                }
            }
        } catch (Throwable e) {
            log.warn("Failed to sync:", e);
        }

        return null;
    }

    private RMap<String, String> sync(String redisKey) {
        log.debug("Syncing resource: {}", redisKey);
        Record cacheRecord = redisGet(redisKey, false);
        if (cacheRecord == null || cacheRecord.synced) {
            RMap<String, String> map = redis.getMap(redisKey, StringCodec.INSTANCE);
            long ttl = map.remainTimeToLive();
            // according to the documentation, -1 means expiration is not set
            if (ttl == -1) {
                map.expire(cacheExpiration);
            }
            redis.getScoredSortedSet(resourceQueue, StringCodec.INSTANCE).remove(redisKey);
            return map;
        }

        String blobKey = blobKeyFromRedisKey(redisKey);
        if (cacheRecord.exists()) {
            log.debug("Syncing resource: {}. Blob updating", redisKey);
            cacheRecord = redisGet(redisKey, true);
            blobPut(blobKey, cacheRecord);
        } else {
            log.debug("Syncing resource: {}. Blob deleting", redisKey);
            blobStore.delete(blobKey);
        }

        return redisSync(redisKey);
    }

    private void blobPut(String key, Record cacheRecord) {
        byte[] bytes;
        String encoding = null;
        Item<String> item = cacheRecord.item;
        if (cacheRecord.binary) {
            bytes = BASE64_DECODER.decode(item.body);
        } else {
            bytes = item.body.getBytes(StandardCharsets.UTF_8);
            if (bytes.length >= compressionMinSize) {
                encoding = "gzip";
                bytes = Compression.compress(encoding, bytes);
            }
        }

        blobStore.store(key, item.metadata.contentType, encoding, toAttributesMap(item.metadata), bytes);
    }

    public void saveStub(String key, ItemMetadata metadata) {
        String blobKey = blobKeyFromRedisKey(key);
        blobStore.store(blobKey, metadata.contentType, null, toAttributesMap(metadata), new byte[0]);
    }

    private static Map<String, String> toAttributesMap(ItemMetadata metadata) {
        Map<String, String> result = new HashMap<>();
        if (metadata.createdAt != null) {
            result.put(ResourceUtil.CREATED_AT_ATTRIBUTE, Long.toString(metadata.createdAt));
        }
        if (metadata.updatedAt != null) {
            result.put(ResourceUtil.UPDATED_AT_ATTRIBUTE, Long.toString(metadata.updatedAt));
        }
        result.put(ResourceUtil.ETAG_ATTRIBUTE, metadata.etag);
        return result;
    }

    private String blobKeyFromRedisKey(String redisKey) {
        // redis key may have prefix, we need to subtract it, because BlobStore manage prefix on its own
        int delimiterIndex = redisKey.indexOf(":");
        int prefixChars = prefix != null ? prefix.length() + 1 : 0;
        return redisKey.substring(prefixChars + delimiterIndex + 1);
    }

    public void saveString(String key, @Nullable Item<String> item) {
        putString(key, item, false);
    }

    public void cacheString(String key, @Nullable Item<String> item) {
        putString(key, item, true);
    }

    public void saveBytes(String key, @Nullable Item<byte[]> item) {
        putBytes(key, item, false);
    }

    public void cacheBytes(String key, @Nullable Item<byte[]> item) {
        putBytes(key, item, true);
    }

    public void cacheMissing(String key) {
        putString(key, null, true);
    }

    public void delete(String key) {
        putString(key, null, false);
    }

    private void putString(String key, @Nullable Item<String> item, boolean synced) {
        if (item == null) {
            redisPut(key, synced ? NOT_FOUND : DELETED);
        } else {
            Record cacheRecord = new Record(item, false, synced);
            redisPut(key, cacheRecord);
        }
    }

    private void putBytes(String key, @Nullable Item<byte[]> item, boolean synced) {
        if (item == null) {
            redisPut(key, synced ? NOT_FOUND : DELETED);
        } else {
            String body = BASE64_ENCODER.encodeToString(item.body);
            putString(key, item.withBody(body), synced);
        }
    }

    public void flush(String key) {
        RMap<String, String> map = sync(key);
        map.delete();
    }

    @Nullable
    public Result<ItemMetadata> getMetadata(String key) {
        Record cacheRecord = redisGet(key, false);
        if (cacheRecord == null) {
            return null;
        }

        if (!cacheRecord.exists()) {
            return Result.empty();
        }

        return new Result<>(cacheRecord.item.metadata);
    }

    @Nullable
    public Result<Item<String>> getString(String key) {
        Record cacheRecord = redisGet(key, true);
        if (cacheRecord == null) {
            return null;
        }

        if (!cacheRecord.exists()) {
            return Result.empty();
        }

        return new Result<>(cacheRecord.item);
    }

    @Nullable
    public Result<Item<byte[]>> getBytes(String key) {
        Record cacheRecord = redisGet(key, true);
        if (cacheRecord == null) {
            return null;
        }

        if (!cacheRecord.exists()) {
            return Result.empty();
        }

        if (cacheRecord.binary) {
            throw new IllegalStateException("The value at %s is not an array".formatted(key));
        }

        return new Result<>(cacheRecord.item.withBody(BASE64_DECODER.decode(cacheRecord.item.body)));
    }

    @Nullable
    private Record redisGet(String key, boolean withBody) {
        RMap<String, String> map = redis.getMap(key, StringCodec.INSTANCE);
        Map<String, String> fields = map.getAll(withBody ? REDIS_FIELDS : REDIS_FIELDS_NO_BODY);

        if (fields.isEmpty()) {
            return null;
        }

        boolean exists = Boolean.parseBoolean(Objects.requireNonNull(fields.get(EXISTS_ATTRIBUTE)));
        boolean binary = Boolean.parseBoolean(Objects.requireNonNull(fields.get(BINARY_ATTRIBUTE)));
        boolean synced = Boolean.parseBoolean(Objects.requireNonNull(fields.get(SYNCED_ATTRIBUTE)));
        if (!exists) {
            return new Record(null, binary, synced);
        }

        String body = fields.get(BODY_ATTRIBUTE);
        String etag = ResourceUtil.extractEtag(fields);
        String contentType = StringUtils.defaultIfEmpty(fields.get(CONTENT_TYPE_ATTRIBUTE), null);
        Long contentLength = nullIfEmpty(fields.get(CONTENT_LENGTH_ATTRIBUTE));
        Long createdAt = nullIfEmpty(fields.get(ResourceUtil.CREATED_AT_ATTRIBUTE));
        Long updatedAt = nullIfEmpty(fields.get(ResourceUtil.UPDATED_AT_ATTRIBUTE));

        return new Record(
                new Item<>(new ItemMetadata(etag, createdAt, updatedAt, contentType, contentLength), body),
                binary,
                synced);
    }

    private void redisPut(String key, Record cacheRecord) {
        RScoredSortedSet<String> set = redis.getScoredSortedSet(resourceQueue, StringCodec.INSTANCE);
        set.add(time() + syncDelay, key); // add resource to sync set before changing because calls below can fail

        RMap<String, String> map = redis.getMap(key, StringCodec.INSTANCE);

        if (!cacheRecord.synced) {
            map.clearExpire();
        }

        Map<String, String> fields = new HashMap<>();
        if (cacheRecord.exists()) {
            Item<String> item = cacheRecord.item;
            fields.put(BODY_ATTRIBUTE, item.body);
            ItemMetadata metadata = item.metadata;
            fields.put(ResourceUtil.ETAG_ATTRIBUTE, metadata.etag);
            fields.put(ResourceUtil.CREATED_AT_ATTRIBUTE, emptyIfNull(metadata.createdAt));
            fields.put(ResourceUtil.UPDATED_AT_ATTRIBUTE, emptyIfNull(metadata.updatedAt));
            fields.put(CONTENT_TYPE_ATTRIBUTE, emptyIfNull(metadata.contentType));
            fields.put(CONTENT_LENGTH_ATTRIBUTE, emptyIfNull(metadata.contentLength));
            fields.put(EXISTS_ATTRIBUTE, String.valueOf(true));
        } else {
            REDIS_FIELDS.forEach(field -> fields.put(field, ""));
            fields.put(EXISTS_ATTRIBUTE, String.valueOf(false));
        }
        fields.put(BINARY_ATTRIBUTE, Boolean.toString(cacheRecord.binary));
        fields.put(SYNCED_ATTRIBUTE, Boolean.toString(cacheRecord.synced));
        map.putAll(fields);

        if (cacheRecord.synced) { // cleanup because it is already synced
            map.expire(cacheExpiration);
            set.remove(key);
        }
    }

    private static String emptyIfNull(Long value) {
        if (value != null) {
            return Long.toString(value);
        }

        return "";
    }

    private static String emptyIfNull(String value) {
        return Objects.requireNonNullElse(value, "");
    }

    private static Long nullIfEmpty(String value) {
        if (StringUtils.isEmpty(value)) {
            return null;
        }

        return Long.parseLong(value);
    }

    private RMap<String, String> redisSync(String key) {
        RMap<String, String> map = redis.getMap(key, StringCodec.INSTANCE);
        map.put("synced", "true");
        map.expire(cacheExpiration);

        RScoredSortedSet<String> set = redis.getScoredSortedSet(resourceQueue, StringCodec.INSTANCE);
        set.remove(key);

        return map;
    }

    private static long time() {
        return System.currentTimeMillis();
    }

    private record Record(
            Item<String> item,
            boolean binary,
            boolean synced) {
        public boolean exists() {
            return item != null;
        }
    }

    @Builder
    public record ItemMetadata(
            String etag, Long createdAt, Long updatedAt, String contentType, Long contentLength) {
    }

    public record Result<T>(T value) {
        private static final Result<?> EMPTY = new Result<>(null);

        public boolean exists() {
            return value != null;
        }

        public <R> R map(Function<T, R> function) {
            return value != null ? function.apply(value) : null;
        }

        @SuppressWarnings("unchecked")
        public static <R> Result<R> empty() {
            return (Result<R>) EMPTY;
        }
    }

    public record Item<T>(ItemMetadata metadata, T body) {
        public <R> Item<R> withBody(R body) {
            return new Item<>(metadata, body);
        }
    }
}