package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.data.FileMetadata;
import com.epam.aidial.core.server.data.MetadataBase;
import com.epam.aidial.core.server.data.ResourceEvent;
import com.epam.aidial.core.server.data.ResourceFolderMetadata;
import com.epam.aidial.core.server.data.ResourceItemMetadata;
import com.epam.aidial.core.server.data.ResourceType;
import com.epam.aidial.core.server.resource.ResourceDescriptor;
import com.epam.aidial.core.server.storage.BlobStorage;
import com.epam.aidial.core.server.storage.BlobStorageUtil;
import com.epam.aidial.core.server.storage.BlobWriteStream;
import com.epam.aidial.core.server.util.Compression;
import com.epam.aidial.core.server.util.EtagBuilder;
import com.epam.aidial.core.server.util.EtagHeader;
import com.epam.aidial.core.server.util.RedisUtil;
import com.epam.aidial.core.server.util.ResourceUtil;
import com.google.common.collect.Sets;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import lombok.Builder;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
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
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.Nullable;

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
            ResourceUtil.RESOURCE_TYPE_ATTRIBUTE,
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

    public ResourceTopic.Subscription subscribeResources(Collection<ResourceDescriptor> resources,
                                                         Consumer<ResourceEvent> subscriber) {
        return topic.subscribe(resources, subscriber);
    }

    public void copyFolder(ResourceDescriptor sourceFolder, ResourceDescriptor targetFolder, boolean overwrite) {
        String token = null;
        do {
            ResourceFolderMetadata folder = getFolderMetadata(sourceFolder, token, 1000, true);
            if (folder == null) {
                throw new IllegalArgumentException("Source folder is empty");
            }

            for (MetadataBase item : folder.getItems()) {
                String sourceFileUrl = item.getUrl();
                String targetFileUrl = targetFolder + sourceFileUrl.substring(sourceFolder.getUrl().length());

                ResourceDescriptor sourceFile = sourceFolder.resolveByUrl(sourceFileUrl);
                ResourceDescriptor targetFile = targetFolder.resolveByUrl(targetFileUrl);

                if (!copyResource(sourceFile, targetFile, overwrite)) {
                    throw new IllegalArgumentException("Can't copy source file: " + sourceFileUrl
                                                       + " to target file: " + targetFileUrl);
                }
            }

            token = folder.getNextToken();
        } while (token != null);
    }

    public boolean deleteFolder(ResourceDescriptor folder) {
        String token = null;
        do {
            ResourceFolderMetadata metadata = getFolderMetadata(folder, token, 1000, true);
            if (metadata == null) {
                return false;
            }

            for (MetadataBase item : metadata.getItems()) {
                ResourceDescriptor file = folder.resolveByUrl(item.getUrl());
                deleteResource(file, EtagHeader.ANY);
            }

            token = metadata.getNextToken();
        } while (token != null);

        return true;
    }

    @Nullable
    public MetadataBase getMetadata(ResourceDescriptor descriptor, String token, int limit, boolean recursive) {
        return descriptor.isFolder()
                ? getFolderMetadata(descriptor, token, limit, recursive)
                : getResourceMetadata(descriptor);
    }

    public ResourceFolderMetadata getFolderMetadata(ResourceDescriptor descriptor, String token, int limit, boolean recursive) {
        String blobKey = blobKey(descriptor);
        PageSet<? extends StorageMetadata> set = blobStore.list(blobKey, token, limit, recursive);

        if (set.isEmpty() && !descriptor.isRootFolder()) {
            return null;
        }

        List<MetadataBase> resources = set.stream().map(meta -> {
            Map<String, String> metadata = meta.getUserMetadata();
            String path = meta.getName();
            ResourceDescriptor description = descriptor.resolveByPath(path);

            if (meta.getType() != StorageType.BLOB) {
                return new ResourceFolderMetadata(description);
            }

            Long createdAt = null;
            Long updatedAt = null;

            if (metadata != null) {
                createdAt = metadata.containsKey(ResourceUtil.CREATED_AT_ATTRIBUTE) ? Long.parseLong(metadata.get(ResourceUtil.CREATED_AT_ATTRIBUTE)) : null;
                updatedAt = metadata.containsKey(ResourceUtil.UPDATED_AT_ATTRIBUTE) ? Long.parseLong(metadata.get(ResourceUtil.UPDATED_AT_ATTRIBUTE)) : null;
            }

            if (createdAt == null && meta.getCreationDate() != null) {
                createdAt = meta.getCreationDate().getTime();
            }

            if (updatedAt == null && meta.getLastModified() != null) {
                updatedAt = meta.getLastModified().getTime();
            }

            if (description.getType() == ResourceType.FILE) {
                return new FileMetadata(description, meta.getSize(), BlobStorage.resolveContentType((BlobMetadata) meta))
                        .setCreatedAt(createdAt)
                        .setUpdatedAt(updatedAt);
            }

            return new ResourceItemMetadata(description).setCreatedAt(createdAt).setUpdatedAt(updatedAt);
        }).toList();

        return new ResourceFolderMetadata(descriptor, resources, set.getNextMarker());
    }

    @Nullable
    public ResourceItemMetadata getResourceMetadata(ResourceDescriptor descriptor) {
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
            ResourceDescriptor descriptor, Result result) {
        return new ResourceItemMetadata(descriptor)
                .setCreatedAt(result.createdAt)
                .setUpdatedAt(result.updatedAt)
                .setEtag(result.etag);
    }

    private static FileMetadata toFileMetadata(
            ResourceDescriptor resource, Result result) {
        return (FileMetadata) new FileMetadata(resource, result.contentLength(), result.contentType())
                .setCreatedAt(result.createdAt)
                .setUpdatedAt(result.updatedAt)
                .setEtag(result.etag());
    }

    public boolean hasResource(ResourceDescriptor descriptor) {
        String redisKey = redisKey(descriptor);
        Result result = redisGet(redisKey, false);

        if (result == null) {
            String blobKey = blobKey(descriptor);
            return blobExists(blobKey);
        }

        return result.exists();
    }

    @Nullable
    public Pair<ResourceItemMetadata, String> getResourceWithMetadata(ResourceDescriptor descriptor) {
        return getResourceWithMetadata(descriptor, true);
    }

    @Nullable
    public Pair<ResourceItemMetadata, String> getResourceWithMetadata(ResourceDescriptor descriptor, boolean lock) {
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
    public String getResource(ResourceDescriptor descriptor) {
        return getResource(descriptor, true);
    }

    @Nullable
    public String getResource(ResourceDescriptor descriptor, boolean lock) {
        Pair<ResourceItemMetadata, String> result = getResourceWithMetadata(descriptor, lock);
        return (result == null) ? null : result.getRight();
    }

    public ResourceStream getResourceStream(ResourceDescriptor resource) throws IOException {
        if (resource.getType() != ResourceType.FILE) {
            throw new IllegalArgumentException("Streaming is supported for files only");
        }

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
            String etag = ResourceUtil.extractEtag(metadata.getUserMetadata());
            String contentType = metadata.getContentMetadata().getContentType();
            Long length = metadata.getContentMetadata().getContentLength();

            if (length <= maxSize) {
                result = blobToResult(blob, metadata);
                redisPut(key, result);
                return ResourceStream.fromResult(result);
            }

            return new ResourceStream(payload.openStream(), etag, contentType, length);
        }
    }

    public ResourceItemMetadata putResource(
            ResourceDescriptor descriptor, String body, EtagHeader etag) {
        return putResource(descriptor, body, etag, true);
    }

    public ResourceItemMetadata putResource(
            ResourceDescriptor descriptor, String body, EtagHeader etag, boolean lock) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return putResource(descriptor, bytes, etag, "application/json", lock);
    }

    private ResourceItemMetadata putResource(
            ResourceDescriptor descriptor,
            byte[] body,
            EtagHeader etag,
            String contentType,
            boolean lock) {
        String redisKey = redisKey(descriptor);

        try (var ignore = lock ? lockService.lock(redisKey) : null) {
            ResourceItemMetadata metadata = getResourceMetadata(descriptor);

            if (metadata != null) {
                etag.validate(metadata.getEtag());
            }

            Long updatedAt = time();
            Long createdAt = metadata == null ? updatedAt : metadata.getCreatedAt();
            String newEtag = EtagBuilder.generateEtag(body);
            Result result = new Result(body, newEtag, createdAt, updatedAt, contentType, (long) body.length, descriptor.getType(), false);
            if (body.length <= maxSize) {
                redisPut(redisKey, result);
                if (metadata == null) {
                    String blobKey = blobKey(descriptor);
                    blobPut(blobKey, result.toStub(), false); // create an empty object for listing
                }
            } else {
                flushToBlobStore(redisKey);
                String blobKey = blobKey(descriptor);
                blobPut(blobKey, result, descriptor.getType() != ResourceType.FILE);
            }

            ResourceEvent.Action action = metadata == null
                    ? ResourceEvent.Action.CREATE
                    : ResourceEvent.Action.UPDATE;
            publishEvent(descriptor, action, updatedAt, newEtag);
            return descriptor.getType() == ResourceType.FILE
                    ? toFileMetadata(descriptor, result)
                    : toResourceItemMetadata(descriptor, result);
        }
    }

    public FileMetadata putFile(ResourceDescriptor descriptor, byte[] body, EtagHeader etag, String contentType) {
        if (descriptor.getType() != ResourceType.FILE) {
            throw new IllegalArgumentException("Expected a file, got %s".formatted(descriptor.getType()));
        }

        return (FileMetadata) putResource(descriptor, body, etag, contentType, true);
    }

    public BlobWriteStream beginFileUpload(ResourceDescriptor descriptor, EtagHeader etag, String contentType) {
        return new BlobWriteStream(vertx, this, blobStore, descriptor, etag, contentType);
    }

    public FileMetadata finishFileUpload(
            ResourceDescriptor descriptor, MultipartData multipartData, EtagHeader etag) {
        String redisKey = redisKey(descriptor);
        try (var ignore = lockService.lock(redisKey)) {
            ResourceItemMetadata metadata = getResourceMetadata(descriptor);
            if (metadata != null) {
                etag.validate(metadata.getEtag());
            }

            flushToBlobStore(redisKey);
            Long updatedAt = time();
            Long createdAt = metadata == null ? updatedAt : metadata.getCreatedAt();
            MultipartUpload multipartUpload = multipartData.multipartUpload;
            Map<String, String> userMetadata = multipartUpload.blobMetadata().getUserMetadata();
            userMetadata.putAll(toUserMetadata(multipartData.etag, createdAt, updatedAt, descriptor.getType()));
            blobStore.completeMultipartUpload(multipartUpload, multipartData.parts);

            ResourceEvent.Action action = metadata == null
                    ? ResourceEvent.Action.CREATE
                    : ResourceEvent.Action.UPDATE;
            publishEvent(descriptor, action, updatedAt, multipartData.etag);

            return (FileMetadata) new FileMetadata(
                    descriptor, multipartData.contentLength, multipartData.contentType)
                    .setCreatedAt(createdAt)
                    .setUpdatedAt(updatedAt)
                    .setEtag(multipartData.etag);
        }
    }

    public ResourceItemMetadata computeResource(ResourceDescriptor descriptor, Function<String, String> fn) {
        return computeResource(descriptor, EtagHeader.ANY, fn);
    }

    public ResourceItemMetadata computeResource(ResourceDescriptor descriptor, EtagHeader etag, Function<String, String> fn) {
        String redisKey = redisKey(descriptor);

        try (var ignore = lockService.lock(redisKey)) {
            Pair<ResourceItemMetadata, String> oldResult = getResourceWithMetadata(descriptor, false);

            if (oldResult != null) {
                etag.validate(oldResult.getKey().getEtag());
            }

            String oldBody = oldResult == null ? null : oldResult.getValue();
            String newBody = fn.apply(oldBody);

            if (oldBody == null && newBody == null) {
                return null;
            }

            if (oldBody != null && newBody == null) {
                deleteResource(descriptor, etag, false);
                return oldResult.getKey();
            }

            if (Objects.equals(oldBody, newBody)) {
                return oldResult.getKey();
            }

            return putResource(descriptor, newBody, etag, false);
        }
    }

    public boolean deleteResource(ResourceDescriptor descriptor, EtagHeader etag) {
        return deleteResource(descriptor, etag, true);
    }

    private boolean deleteResource(ResourceDescriptor descriptor, EtagHeader etag, boolean lock) {
        String redisKey = redisKey(descriptor);

        try (var ignore = lock ? lockService.lock(redisKey) : null) {
            ResourceItemMetadata metadata = getResourceMetadata(descriptor);

            if (metadata == null) {
                return false;
            }

            etag.validate(metadata.getEtag());

            redisPut(redisKey, Result.DELETED_NOT_SYNCED);
            blobDelete(blobKey(descriptor));
            redisSync(redisKey);

            publishEvent(descriptor, ResourceEvent.Action.DELETE, time(), null);
            return true;
        }
    }

    public boolean copyResource(ResourceDescriptor from, ResourceDescriptor to) {
        return copyResource(from, to, true);
    }

    public boolean copyResource(ResourceDescriptor from, ResourceDescriptor to, boolean overwrite) {
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

                ResourceEvent.Action action = toMetadata == null
                        ? ResourceEvent.Action.CREATE
                        : ResourceEvent.Action.UPDATE;
                publishEvent(to, action, time(), fromMetadata.getEtag());
                return true;
            }

            return false;
        }
    }

    private void publishEvent(ResourceDescriptor descriptor, ResourceEvent.Action action, long timestamp, String etag) {
        ResourceEvent event = new ResourceEvent()
                .setUrl(descriptor.getUrl())
                .setAction(action)
                .setTimestamp(timestamp)
                .setEtag(etag);

        topic.publish(event);
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
            blobPut(blobKey, result, !redisKey.startsWith("file:"));
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
        Long createdAt = meta.getUserMetadata().containsKey(ResourceUtil.CREATED_AT_ATTRIBUTE)
                ? Long.parseLong(meta.getUserMetadata().get(ResourceUtil.CREATED_AT_ATTRIBUTE))
                : null;
        Long updatedAt = meta.getUserMetadata().containsKey(ResourceUtil.UPDATED_AT_ATTRIBUTE)
                ? Long.parseLong(meta.getUserMetadata().get(ResourceUtil.UPDATED_AT_ATTRIBUTE))
                : null;
        ResourceType resourceType = Optional.ofNullable(meta.getUserMetadata().get(ResourceUtil.RESOURCE_TYPE_ATTRIBUTE))
                .map(ResourceType::valueOf)
                .orElse(null);

        // Get times from blob metadata if available for files that didn't store it in user metadata
        if (createdAt == null && meta.getCreationDate() != null) {
            createdAt = meta.getCreationDate().getTime();
        }

        if (updatedAt == null && meta.getLastModified() != null) {
            updatedAt = meta.getLastModified().getTime();
        }

        byte[] body = ArrayUtils.EMPTY_BYTE_ARRAY;

        if (blob != null) {
            String encoding = meta.getContentMetadata().getContentEncoding();
            try (InputStream stream = blob.getPayload().openStream()) {
                body = stream.readAllBytes();
                if (!StringUtils.isBlank(encoding)) {
                    body = Compression.decompress(encoding, body);
                }
            }
        }

        return new Result(body, etag, createdAt, updatedAt, contentType, contentLength, resourceType, true);
    }

    private void blobPut(String key, Result result, boolean compress) {
        String encoding = null;
        byte[] bytes = result.body;
        if (bytes.length >= compressionMinSize && compress) {
            encoding = "gzip";
            bytes = Compression.compress(encoding, bytes);
        }

        Map<String, String> metadata = toUserMetadata(result.etag, result.createdAt, result.updatedAt, result.resourceType);
        blobStore.store(key, result.contentType, encoding, metadata, bytes);
    }

    private void blobDelete(String key) {
        blobStore.delete(key);
    }

    private static String blobKey(ResourceDescriptor descriptor) {
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

        ResourceType resourceType = Optional.ofNullable(RedisUtil.redisToString(fields.get(ResourceUtil.RESOURCE_TYPE_ATTRIBUTE), null))
                .map(ResourceType::valueOf)
                .orElse(null);

        return new Result(body, etag, createdAt, updatedAt, contentType, contentLength, resourceType, synced);
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
            fields.put(ResourceUtil.RESOURCE_TYPE_ATTRIBUTE, RedisUtil.stringToRedis(Optional.ofNullable(result.resourceType)
                    .map(ResourceType::name)
                    .orElse(null)));
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

    private String redisKey(ResourceDescriptor descriptor) {
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

    public String getEtag(ResourceDescriptor descriptor) {
        ResourceItemMetadata metadata = getResourceMetadata(descriptor);
        if (metadata == null) {
            return null;
        }

        return metadata.getEtag();
    }

    private static Map<String, String> toUserMetadata(String etag, Long createdAt, Long updatedAt, ResourceType resourceType) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put(ResourceUtil.ETAG_ATTRIBUTE, etag);
        if (createdAt != null) {
            metadata.put(ResourceUtil.CREATED_AT_ATTRIBUTE, Long.toString(createdAt));
        }
        if (updatedAt != null) {
            metadata.put(ResourceUtil.UPDATED_AT_ATTRIBUTE, Long.toString(updatedAt));
        }
        if (resourceType != null) {
            metadata.put(ResourceUtil.RESOURCE_TYPE_ATTRIBUTE, resourceType.name());
        }

        return metadata;
    }

    @Builder
    private record Result(
            byte[] body,
            String etag,
            Long createdAt,
            Long updatedAt,
            String contentType,
            Long contentLength,
            ResourceType resourceType,
            boolean synced) {
        public static final Result DELETED_SYNCED = new Result(null, null, null, null, null, null, null, true);
        public static final Result DELETED_NOT_SYNCED = new Result(null, null, null, null, null, null, null, false);

        public boolean exists() {
            return body != null;
        }

        public Result toStub() {
            return new Result(ArrayUtils.EMPTY_BYTE_ARRAY, etag, createdAt, updatedAt, contentType, 0L, resourceType, synced);
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

    public record MultipartData(
            MultipartUpload multipartUpload,
            List<MultipartPart> parts,
            String contentType,
            long contentLength,
            String etag) {
    }
}