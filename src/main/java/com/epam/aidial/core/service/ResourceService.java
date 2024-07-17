package com.epam.aidial.core.service;

import com.epam.aidial.core.data.FileMetadata;
import com.epam.aidial.core.data.MetadataBase;
import com.epam.aidial.core.data.ResourceEvent;
import com.epam.aidial.core.data.ResourceFolderMetadata;
import com.epam.aidial.core.data.ResourceItemMetadata;
import com.epam.aidial.core.data.ResourceType;
import com.epam.aidial.core.storage.BlobStorage;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.storage.BlobWriteStream;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.Compression;
import com.epam.aidial.core.util.EtagBuilder;
import com.epam.aidial.core.util.EtagHeader;
import com.epam.aidial.core.util.RedisUtil;
import com.epam.aidial.core.util.ResourceUtil;
import com.google.common.collect.Sets;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import lombok.Builder;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.BlobMetadata;
import org.jclouds.blobstore.domain.MultipartPart;
import org.jclouds.blobstore.domain.MultipartUpload;
import org.jclouds.blobstore.domain.PageSet;
import org.jclouds.blobstore.domain.StorageMetadata;
import org.jclouds.blobstore.domain.StorageType;
import org.jclouds.io.Payload;
import org.redisson.api.RMap;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.StringCodec;
import org.redisson.codec.CompositeCodec;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.Nullable;

import static com.epam.aidial.core.util.ResourceUtil.CREATED_AT_ATTRIBUTE;
import static com.epam.aidial.core.util.ResourceUtil.UPDATED_AT_ATTRIBUTE;

@Slf4j
public class ResourceService implements AutoCloseable {
    private static final String BODY_ATTRIBUTE = "body";
    private static final String CONTENT_TYPE_ATTRIBUTE = "content_type";
    private static final String CONTENT_LENGTH_ATTRIBUTE = "content_length";
    private static final String SYNCED_ATTRIBUTE = "synced";
    private static final String EXISTS_ATTRIBUTE = "exists";
    private static final Set<String> REDIS_FIELDS_NO_BODY = Set.of(
            ResourceUtil.ETAG_ATTRIBUTE,
            ResourceUtil.CREATED_AT_ATTRIBUTE,
            ResourceUtil.UPDATED_AT_ATTRIBUTE,
            CONTENT_TYPE_ATTRIBUTE,
            CONTENT_LENGTH_ATTRIBUTE,
            SYNCED_ATTRIBUTE,
            EXISTS_ATTRIBUTE);
    private static final Set<String> REDIS_FIELDS = Sets.union(
            Set.of(BODY_ATTRIBUTE),
            REDIS_FIELDS_NO_BODY);
    private static final Codec REDIS_MAP_CODEC = new CompositeCodec(
            StringCodec.INSTANCE,
            ByteArrayCodec.INSTANCE);

    private final Vertx vertx;
    private final RedissonClient redis;
    private final BlobStorage blobStore;
    private final LockService lockService;
    private final ResourceTopic topic;
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
        this.topic = new ResourceTopic(redis, "resource:" + BlobStorageUtil.toStoragePath(prefix, "topic"));
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

    public ResourceTopic.Subscription subscribeResources(Collection<ResourceDescription> resources,
                                                         Consumer<ResourceEvent> subscriber) {
        return topic.subscribe(resources, subscriber);
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

            if (description.getType() == ResourceType.FILE) {
                return BlobStorage.buildFileMetadata(description, (BlobMetadata) meta);
            } else {
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
            }
        }).toList();

        return new ResourceFolderMetadata(descriptor, resources, set.getNextMarker());
    }

    @Nullable
    public ResourceItemMetadata getResourceMetadata(ResourceDescription descriptor) {
        if (descriptor.isFolder()) {
            throw new IllegalArgumentException("Resource folder: " + descriptor.getUrl());
        }

        String redisKey = redisKey(descriptor);
        Result result = redisGet(redisKey, false);

        if (result == null) {
            String blobKey = blobKey(descriptor);
            result = blobGet(blobKey, false);
        }

        if (!result.exists()) {
            return null;
        }

        return descriptor.getType() == ResourceType.FILE
                ? toFileMetadata(descriptor, result)
                : toResourceItemMetadata(descriptor, result);
    }

    private static ResourceItemMetadata toResourceItemMetadata(
            ResourceDescription descriptor, Result result) {
        return new ResourceItemMetadata(descriptor)
                .setCreatedAt(result.createdAt)
                .setUpdatedAt(result.updatedAt)
                .setEtag(result.etag);
    }

    private static FileMetadata toFileMetadata(
            ResourceDescription resource, Result result) {
        return (FileMetadata) new FileMetadata(resource, result.contentLength(), result.contentType())
                .setEtag(result.etag());
    }

    public boolean hasResource(ResourceDescription descriptor) {
        String redisKey = redisKey(descriptor);
        Result result = redisGet(redisKey, false);

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

        if (result.exists()) {
            return Pair.of(
                    toResourceItemMetadata(descriptor, result),
                    new String(result.body, StandardCharsets.UTF_8));
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

    public ResourceStream getResourceStream(ResourceDescription resource) throws IOException {
        String key = redisKey(resource);
        Result result = redisGet(key, true);
        if (result != null) {
            return ResourceStream.fromResult(result);
        }

        try (LockService.Lock ignored = lockService.lock(key)) {
            result = redisGet(key, true);
            if (result != null) {
                return ResourceStream.fromResult(result);
            }

            Blob blob = blobStore.load(resource.getAbsoluteFilePath());
            if (blob == null) {
                redisPut(key, Result.DELETED_SYNCED);
                return null;
            }

            Payload payload = blob.getPayload();
            BlobMetadata metadata = blob.getMetadata();
            String etag = ResourceUtil.extractEtag(blob.getMetadata().getUserMetadata());
            String contentType = metadata.getContentMetadata().getContentType();
            Long length = metadata.getContentMetadata().getContentLength();

            if (length <= maxSize) {
                result = blobToResult(blob, metadata);
                redisPut(key, result);
                return ResourceStream.fromResult(result);
            }

            String encoding = metadata.getContentMetadata().getContentEncoding();
            return new ResourceStream(
                    encoding == null ? payload.openStream() : Compression.decompress(encoding, payload.openStream()),
                    etag,
                    contentType,
                    length);
        }
    }

    public ResourceItemMetadata putResource(
            ResourceDescription descriptor, String body, EtagHeader etag, boolean overwrite) {
        return putResource(descriptor, body, etag, overwrite, true);
    }

    public ResourceItemMetadata putResource(
            ResourceDescription descriptor, String body, EtagHeader etag, boolean overwrite, boolean lock) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return putResource(descriptor, bytes, etag, "application/json", overwrite, lock);
    }

    private ResourceItemMetadata putResource(
            ResourceDescription descriptor,
            byte[] body,
            EtagHeader etag,
            String contentType,
            boolean overwrite,
            boolean lock) {
        String redisKey = redisKey(descriptor);

        try (var ignore = lock ? lockService.lock(redisKey) : null) {
            ResourceItemMetadata metadata = getResourceMetadata(descriptor);

            if (metadata != null) {
                if (!overwrite) {
                    return null;
                }

                etag.validate(metadata.getEtag());
            }

            long updatedAt = System.currentTimeMillis();
            Long createdAt = metadata == null ? (Long) updatedAt : metadata.getCreatedAt();
            String newEtag = EtagBuilder.generateEtag(body);
            Result result = new Result(body, newEtag, createdAt, updatedAt, contentType, (long) body.length, false);
            if (body.length <= maxSize) {
                redisPut(redisKey, result);
                if (metadata == null) {
                    String blobKey = blobKey(descriptor);
                    blobPut(blobKey, result.toStub()); // create an empty object for listing
                }
            } else {
                flushToBlobStore(redisKey);
                String blobKey = blobKey(descriptor);
                blobPut(blobKey, result);
            }

            ResourceEvent event = new ResourceEvent()
                    .setUrl(descriptor.getUrl())
                    .setAction(ResourceEvent.Action.UPDATE)
                    .setTimestamp(updatedAt);

            if (metadata == null) {
                event.setAction(ResourceEvent.Action.CREATE);
            }

            topic.publish(event);
            return descriptor.getType() == ResourceType.FILE
                    ? toFileMetadata(descriptor, result)
                    : toResourceItemMetadata(descriptor, result);
        }
    }

    public FileMetadata putFile(ResourceDescription descriptor, byte[] body, EtagHeader etag, String contentType) {
        if (descriptor.getType() != ResourceType.FILE) {
            throw new IllegalArgumentException("Expected a file, got %s".formatted(descriptor.getType()));
        }

        return (FileMetadata) putResource(descriptor, body, etag, contentType, true, true);
    }

    public void completeMultipartUpload(
            ResourceDescription descriptor, MultipartUpload multipartUpload, List<MultipartPart> parts, EtagHeader etag) {
        String redisKey = redisKey(descriptor);
        try (var ignore = lockService.lock(redisKey)) {
            etag.validate(() -> getEtag(descriptor));

            flushToBlobStore(redisKey);
            blobStore.completeMultipartUpload(multipartUpload, parts);
        }
    }

    public BlobWriteStream getFileWriteStream(ResourceDescription descriptor, EtagHeader etag, String contentType) {
        return new BlobWriteStream(vertx, this, blobStore, descriptor, etag, contentType);
    }

    public void computeResource(ResourceDescription descriptor, Function<String, String> fn) {
        String redisKey = redisKey(descriptor);

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
        String redisKey = redisKey(descriptor);

        try (var ignore = lockService.lock(redisKey)) {
            ResourceItemMetadata metadata = getResourceMetadata(descriptor);

            if (metadata == null) {
                return false;
            }

            etag.validate(metadata.getEtag());

            redisPut(redisKey, Result.DELETED_NOT_SYNCED);
            blobDelete(blobKey(descriptor));
            redisSync(redisKey);

            ResourceEvent event = new ResourceEvent()
                    .setUrl(descriptor.getUrl())
                    .setAction(ResourceEvent.Action.DELETE)
                    .setTimestamp(time());

            topic.publish(event);
            return true;
        }
    }

    public boolean copyResource(ResourceDescription from, ResourceDescription to) {
        return copyResource(from, to, true);
    }

    public boolean copyResource(ResourceDescription from, ResourceDescription to, boolean overwrite) {
        if (from.equals(to)) {
            return overwrite;
        }

        String fromRedisKey = redisKey(from);
        String toRedisKey = redisKey(to);
        Pair<String, String> sortedPair = toOrderedPair(fromRedisKey, toRedisKey);
        try (LockService.Lock ignored1 = lockService.lock(sortedPair.getLeft());
             LockService.Lock ignored2 = lockService.lock(sortedPair.getRight())) {
            ResourceItemMetadata fromMetadata = getResourceMetadata(from);
            if (fromMetadata == null) {
                return false;
            }

            ResourceItemMetadata toMetadata = getResourceMetadata(to);
            if (toMetadata == null || overwrite) {
                flushToBlobStore(fromRedisKey);
                flushToBlobStore(toRedisKey);
                blobStore.copy(blobKey(from), blobKey(to));

                return true;
            }

            return false;
        }
    }

    private Pair<String, String> toOrderedPair(String a, String b) {
        return a.compareTo(b) > 0 ? Pair.of(a, b) : Pair.of(b, a);
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

    private RMap<String, byte[]> sync(String redisKey) {
        log.debug("Syncing resource: {}", redisKey);
        Result result = redisGet(redisKey, false);
        if (result == null || result.synced) {
            RMap<String, byte[]> map = redis.getMap(redisKey, REDIS_MAP_CODEC);
            long ttl = map.remainTimeToLive();
            // according to the documentation, -1 means expiration is not set
            if (ttl == -1) {
                map.expire(cacheExpiration);
            }
            redis.getScoredSortedSet(resourceQueue, StringCodec.INSTANCE).remove(redisKey);
            return map;
        }

        String blobKey = blobKeyFromRedisKey(redisKey);
        if (result.exists()) {
            log.debug("Syncing resource: {}. Blob updating", redisKey);
            result = redisGet(redisKey, true);
            blobPut(blobKey, result);
        } else {
            log.debug("Syncing resource: {}. Blob deleting", redisKey);
            blobDelete(blobKey);
        }

        return redisSync(redisKey);
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
            return Result.DELETED_SYNCED;
        }

        return blobToResult(blob, meta);
    }

    @SneakyThrows
    private static Result blobToResult(Blob blob, BlobMetadata meta) {
        String etag = ResourceUtil.extractEtag(meta.getUserMetadata());
        String contentType = meta.getContentMetadata().getContentType();
        Long contentLength = meta.getContentMetadata().getContentLength();
        Long createdAt = meta.getUserMetadata().containsKey(CREATED_AT_ATTRIBUTE)
                ? Long.parseLong(meta.getUserMetadata().get(CREATED_AT_ATTRIBUTE))
                : null;
        Long updatedAt = meta.getUserMetadata().containsKey(UPDATED_AT_ATTRIBUTE)
                ? Long.parseLong(meta.getUserMetadata().get(UPDATED_AT_ATTRIBUTE))
                : null;

        byte[] body = ArrayUtils.EMPTY_BYTE_ARRAY;

        if (blob != null) {
            String encoding = meta.getContentMetadata().getContentEncoding();
            try (InputStream stream = blob.getPayload().openStream()) {
                body = stream.readAllBytes();
                if (encoding != null) {
                    body = Compression.decompress(encoding, body);
                }
            }
        }

        return new Result(body, etag, createdAt, updatedAt, contentType, contentLength, true);
    }

    private void blobPut(String key, Result result) {
        String encoding = null;
        byte[] bytes = result.body;
        if (bytes.length >= compressionMinSize) {
            encoding = "gzip";
            bytes = Compression.compress(encoding, bytes);
        }

        Map<String, String> metadata = new HashMap<>();
        metadata.put(ResourceUtil.ETAG_ATTRIBUTE, result.etag);
        if (result.createdAt != null) {
            metadata.put(ResourceUtil.CREATED_AT_ATTRIBUTE, Long.toString(result.createdAt));
        }
        if (result.updatedAt != null) {
            metadata.put(ResourceUtil.UPDATED_AT_ATTRIBUTE, Long.toString(result.updatedAt));
        }

        blobStore.store(key, result.contentType, encoding, metadata, bytes);
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
        RMap<String, byte[]> map = redis.getMap(key, REDIS_MAP_CODEC);
        Map<String, byte[]> fields = map.getAll(withBody ? REDIS_FIELDS : REDIS_FIELDS_NO_BODY);

        if (fields.isEmpty()) {
            return null;
        }

        boolean exists = Objects.requireNonNull(RedisUtil.redisToBoolean(fields.get(EXISTS_ATTRIBUTE)));
        boolean synced = Objects.requireNonNull(RedisUtil.redisToBoolean(fields.get(SYNCED_ATTRIBUTE)));
        if (!exists) {
            return synced ? Result.DELETED_SYNCED : Result.DELETED_NOT_SYNCED;
        }

        byte[] body = fields.getOrDefault(BODY_ATTRIBUTE, ArrayUtils.EMPTY_BYTE_ARRAY);
        String etag = RedisUtil.redisToString(fields.get(ResourceUtil.ETAG_ATTRIBUTE), ResourceUtil.DEFAULT_ETAG);
        String contentType = RedisUtil.redisToString(fields.get(CONTENT_TYPE_ATTRIBUTE), null);
        Long contentLength = RedisUtil.redisToLong(fields.get(CONTENT_LENGTH_ATTRIBUTE));
        Long createdAt = RedisUtil.redisToLong(fields.get(ResourceUtil.CREATED_AT_ATTRIBUTE));
        Long updatedAt = RedisUtil.redisToLong(fields.get(ResourceUtil.UPDATED_AT_ATTRIBUTE));

        return new Result(body, etag, createdAt, updatedAt, contentType, contentLength, synced);
    }

    private void redisPut(String key, Result result) {
        RScoredSortedSet<String> set = redis.getScoredSortedSet(resourceQueue, StringCodec.INSTANCE);
        set.add(time() + syncDelay, key); // add resource to sync set before changing because calls below can fail

        RMap<String, byte[]> map = redis.getMap(key, REDIS_MAP_CODEC);

        if (!result.synced) {
            map.clearExpire();
        }

        Map<String, byte[]> fields = new HashMap<>();
        if (result.exists()) {
            fields.put(BODY_ATTRIBUTE, result.body);
            fields.put(ResourceUtil.ETAG_ATTRIBUTE, RedisUtil.stringToRedis(result.etag));
            fields.put(ResourceUtil.CREATED_AT_ATTRIBUTE, RedisUtil.longToRedis(result.createdAt));
            fields.put(ResourceUtil.UPDATED_AT_ATTRIBUTE, RedisUtil.longToRedis(result.updatedAt));
            fields.put(CONTENT_TYPE_ATTRIBUTE, RedisUtil.stringToRedis(result.contentType));
            fields.put(CONTENT_LENGTH_ATTRIBUTE, RedisUtil.longToRedis(result.contentLength));
            fields.put(EXISTS_ATTRIBUTE, RedisUtil.BOOLEAN_TRUE_ARRAY);
        } else {
            REDIS_FIELDS.forEach(field -> fields.put(field, RedisUtil.EMPTY_ARRAY));
            fields.put(EXISTS_ATTRIBUTE, RedisUtil.BOOLEAN_FALSE_ARRAY);
        }
        fields.put(SYNCED_ATTRIBUTE, RedisUtil.booleanToRedis(result.synced));
        map.putAll(fields);

        if (result.synced) { // cleanup because it is already synced
            map.expire(cacheExpiration);
            set.remove(key);
        }
    }

    private RMap<String, byte[]> redisSync(String key) {
        RMap<String, byte[]> map = redis.getMap(key, REDIS_MAP_CODEC);
        map.put(SYNCED_ATTRIBUTE, RedisUtil.BOOLEAN_TRUE_ARRAY);
        map.expire(cacheExpiration);

        RScoredSortedSet<String> set = redis.getScoredSortedSet(resourceQueue, StringCodec.INSTANCE);
        set.remove(key);

        return map;
    }

    private String redisKey(ResourceDescription descriptor) {
        String resourcePath = BlobStorageUtil.toStoragePath(prefix, descriptor.getAbsoluteFilePath());
        return descriptor.getType().name().toLowerCase() + ":" + resourcePath;
    }

    private static long time() {
        return System.currentTimeMillis();
    }

    private void flushToBlobStore(String redisKey) {
        RMap<String, byte[]> map = sync(redisKey);
        map.delete();
    }

    public String getEtag(ResourceDescription descriptor) {
        ResourceItemMetadata metadata = getResourceMetadata(descriptor);
        if (metadata == null) {
            return null;
        }

        return metadata.getEtag();
    }

    @Builder
    private record Result(
            byte[] body,
            String etag,
            Long createdAt,
            Long updatedAt,
            String contentType,
            Long contentLength,
            boolean synced) {
        public static final Result DELETED_SYNCED = new Result(null, null, null, null, null, null, true);
        public static final Result DELETED_NOT_SYNCED = new Result(null, null, null, null, null, null, false);

        public boolean exists() {
            return body != null;
        }

        public Result toStub() {
            return new Result(ArrayUtils.EMPTY_BYTE_ARRAY, etag, createdAt, updatedAt, contentType, 0L, synced);
        }
    }

    public record ResourceStream(InputStream inputStream, String etag, String contentType, long contentLength)
            implements Closeable {

        @Override
        public void close() throws IOException {
            inputStream.close();
        }

        @Nullable
        private static ResourceStream fromResult(Result item) {
            if (!item.exists()) {
                return null;
            }

            return new ResourceStream(
                    new ByteArrayInputStream(item.body),
                    item.etag(),
                    item.contentType(),
                    item.body.length);
        }
    }
}