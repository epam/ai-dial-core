package com.epam.aidial.core.storage.service;

import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.blobstore.BlobStorageUtil;
import com.epam.aidial.core.storage.data.FileMetadata;
import com.epam.aidial.core.storage.data.MetadataBase;
import com.epam.aidial.core.storage.data.NodeType;
import com.epam.aidial.core.storage.data.ResourceEvent;
import com.epam.aidial.core.storage.data.ResourceFolderMetadata;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.data.ResourceUpload;
import com.epam.aidial.core.storage.data.UserMetadata;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.tracing.BlockingCallTracer;
import com.epam.aidial.core.storage.util.Base58;
import com.epam.aidial.core.storage.util.Compression;
import com.epam.aidial.core.storage.util.EtagBuilder;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.epam.aidial.core.storage.util.RedisUtil;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Sets;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import lombok.Builder;
import lombok.Data;
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
import org.redisson.api.BatchResult;
import org.redisson.api.RBatch;
import org.redisson.api.RKeys;
import org.redisson.api.RMap;
import org.redisson.api.RMapAsync;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RScript;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nullable;

import static java.util.concurrent.Executors.newThreadPerTaskExecutor;

@Slf4j
public class ResourceService implements AutoCloseable {

    private static final String BASE58_PREFIX = "base58_";
    // Default ETag for old records
    public static final String DEFAULT_ETAG = "\"0\"";
    private static final String BODY_ATTRIBUTE = "body";
    private static final String CONTENT_TYPE_ATTRIBUTE = "content_type";
    private static final String CONTENT_LENGTH_ATTRIBUTE = "content_length";
    private static final String SYNCED_ATTRIBUTE = "synced";
    private static final String EXISTS_ATTRIBUTE = "exists";
    public static final String RESOURCE_TYPE_ATTRIBUTE = "resource_type";
    public static final String UPDATED_AT_ATTRIBUTE = "updated_at";
    public static final String CREATED_AT_ATTRIBUTE = "created_at";
    public static final String ETAG_ATTRIBUTE = "etag";
    /**
     * System folder large files are uploaded to before being moved to the target resource, see issue #1604.
     */
    public static final String TEMP_FOLDER = ".dial-tmp";
    /**
     * Files uploaded to the {@link #TEMP_FOLDER} are grouped into sub-folders, e.g. {@code .dial-tmp/2024-01-31 13:34:10/<uuid>}.
     */
    private static final DateTimeFormatter TEMP_FOLDER_FORMATTER = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .toFormatter();
    /**
     * How frequently the scheduled job scans the {@link #TEMP_FOLDER} for expired files.
     */
    private static final long TEMP_CLEANUP_PERIOD = Duration.ofHours(24).toMillis();
    /**
     * A temp file is considered expired and removed once its upload hour ended more than this duration ago.
     */
    private static final Duration TEMP_FILE_TTL = Duration.ofHours(1);
    private static final String COMPRESS_ATTRIBUTE = "compress";
    private static final String AUTHOR_ATTRIBUTE = "author";

    private static final Set<String> REDIS_FIELDS_NO_BODY = Set.of(
            ETAG_ATTRIBUTE,
            CREATED_AT_ATTRIBUTE,
            UPDATED_AT_ATTRIBUTE,
            RESOURCE_TYPE_ATTRIBUTE,
            CONTENT_TYPE_ATTRIBUTE,
            CONTENT_LENGTH_ATTRIBUTE,
            AUTHOR_ATTRIBUTE,
            SYNCED_ATTRIBUTE,
            EXISTS_ATTRIBUTE);
    private static final Set<String> REDIS_FIELDS = Sets.union(
            Set.of(BODY_ATTRIBUTE),
            REDIS_FIELDS_NO_BODY);
    private static final Codec REDIS_MAP_CODEC = new CompositeCodec(
            StringCodec.INSTANCE,
            ByteArrayCodec.INSTANCE);

    private static final int BATCH_SIZE = 50;
    private static final int PAGE_SIZE = 1000;

    // a writer that loses this many compare-and-set rounds in a row to other pods gives up; before its n-th retry it
    // waits a random 0 to 2^n - 1 ms, at most 63 ms, so the pods that collided spread out
    private static final int MAX_COMPUTE_WITHOUT_LOCK_ATTEMPTS = 32;
    private static final int MAX_COMPUTE_WITHOUT_LOCK_BACKOFF_SHIFT = 6;
    private static final Counter COMPUTE_GIVE_UPS = Counter.builder("dial_resource_compute_give_ups_total")
            .description("Updates made without the resource lock that gave up: another writer changed the record on every attempt")
            .register(Metrics.globalRegistry);
    // in a fixed order, for HMGET
    private static final List<String> REDIS_FIELD_LIST = List.copyOf(REDIS_FIELDS);
    // ARGV[1]: the synced flag of a record to skip ('' to skip none); ARGV[2..]: the field names. Run as a write
    // (RScript.Mode.READ_WRITE) it reads the master: a replica can lag behind the last compare-and-set
    private static final String READ_FIELDS_SCRIPT = """
            if ARGV[1] ~= '' and redis.call('hget', KEYS[1], '%s') == ARGV[1] then
              return {}
            end
            return redis.call('hmget', KEYS[1], unpack(ARGV, 2))
            """.formatted(SYNCED_ATTRIBUTE);
    private static final Object[] READ_FIELDS_ARGS = buildReadFieldsArgs(RedisUtil.EMPTY_ARRAY);
    private static final Object[] READ_UNSYNCED_FIELDS_ARGS = buildReadFieldsArgs(RedisUtil.BOOLEAN_TRUE_ARRAY);
    // ARGV[1]: the etag the value was read with ('' for none); ARGV[2]: 'true' if the value came from blob storage, so
    // the record is created when Redis has none; ARGV[3..]: field, value pairs.
    // Redisson resends a command after a response timeout. A resend of a write that was applied sees a conflict, and
    // fn runs again on a value that already holds the update, so it counts twice: a counter can run high, never low
    private static final String COMPARE_AND_SET_SCRIPT = """
            if redis.call('exists', KEYS[1]) == 0 then
              if ARGV[2] ~= 'true' then
                return 0
              end
            elseif (redis.call('hget', KEYS[1], '%s') or '') ~= ARGV[1] then
              return 0
            end
            redis.call('hset', KEYS[1], unpack(ARGV, 3))
            redis.call('persist', KEYS[1])
            return 1
            """.formatted(ETAG_ATTRIBUTE);
    // ARGV[1]: expiration in milliseconds; ARGV[2..]: field, value pairs
    private static final String PUT_IF_ABSENT_SCRIPT = """
            if redis.call('exists', KEYS[1]) == 1 then
              return 0
            end
            redis.call('hset', KEYS[1], unpack(ARGV, 2))
            redis.call('pexpire', KEYS[1], ARGV[1])
            return 1
            """;
    // ARGV[1]: the etag that was uploaded; ARGV[2]: the synced flag; ARGV[3]: expiration in milliseconds.
    // A key deleted during the sync stays deleted: a hash holding only the synced flag would break every read of it
    private static final String MARK_SYNCED_SCRIPT = """
            if redis.call('exists', KEYS[1]) == 0 then
              return 0
            end
            if (redis.call('hget', KEYS[1], '%s') or '') ~= ARGV[1] then
              return 0
            end
            redis.call('hset', KEYS[1], '%s', ARGV[2])
            redis.call('pexpire', KEYS[1], ARGV[3])
            return 1
            """.formatted(ETAG_ATTRIBUTE, SYNCED_ATTRIBUTE);
    // ARGV[1]: the synced flag; ARGV[2]: expiration in milliseconds. Returns 0 only for a record that is not synced
    private static final String EXPIRE_IF_SYNCED_SCRIPT = """
            local synced = redis.call('hget', KEYS[1], '%s')
            if not synced then
              return 1
            end
            if synced ~= ARGV[1] then
              return 0
            end
            if redis.call('pttl', KEYS[1]) == -1 then
              redis.call('pexpire', KEYS[1], ARGV[2])
            end
            return 1
            """.formatted(SYNCED_ATTRIBUTE);

    private static final ExecutorService VIRTUAL_THREAD_PER_TASK_EXECUTOR;

    static {
        ThreadFactory threadFactory = Thread.ofVirtual().name("batch-dial.x-virtual-thread-", 0).factory();
        VIRTUAL_THREAD_PER_TASK_EXECUTOR = newThreadPerTaskExecutor(threadFactory);
    }

    private final RedissonClient redis;
    private final BlobStorage blobStore;
    private final LockService lockService;
    private final ResourceTopic topic;
    @Getter
    private final int maxSize;
    private final int maxSizeToCache;
    private final TimerService.Timer syncTimer;
    private final TimerService.Timer tempCleanupTimer;
    private final long syncDelay;
    private final int syncBatch;
    private final Duration cacheExpiration;
    private final int compressionMinSize;
    private final String prefix;
    private final String resourceQueue;
    private final Map<String, Long> resourceTypeExpiration;
    private final Supplier<String> senderPodIdSupplier;
    private final BlockingCallTracer blockingCallTracer;
    private final TempFolderCleaner tempFolderCleaner;

    public ResourceService(TimerService timerService,
                           RedissonClient redis,
                           BlobStorage blobStore,
                           LockService lockService,
                           Settings settings,
                           String prefix) {
        this(timerService, redis, blobStore, lockService, settings, prefix, () -> null);
    }

    public ResourceService(TimerService timerService,
                           RedissonClient redis,
                           BlobStorage blobStore,
                           LockService lockService,
                           Settings settings,
                           String prefix,
                           Supplier<String> senderPodIdSupplier) {
        this(timerService, redis, blobStore, lockService, settings, prefix, senderPodIdSupplier, BlockingCallTracer.NOOP);
    }

    public ResourceService(TimerService timerService,
                           RedissonClient redis,
                           BlobStorage blobStore,
                           LockService lockService,
                           Settings settings,
                           String prefix,
                           Supplier<String> senderPodIdSupplier,
                           BlockingCallTracer blockingCallTracer) {
        this.blockingCallTracer = blockingCallTracer;
        this.redis = redis;
        this.blobStore = blobStore;
        this.lockService = lockService;
        this.topic = new ResourceTopic(redis, "resource:" + BlobStorageUtil.toStoragePath(prefix, "topic"));
        this.maxSize = settings.maxSize;
        this.maxSizeToCache = settings.maxSizeToCache;
        this.syncDelay = settings.syncDelay;
        this.syncBatch = settings.syncBatch;
        this.cacheExpiration = Duration.ofMillis(settings.cacheExpiration);
        this.compressionMinSize = settings.compressionMinSize;
        this.prefix = prefix;
        this.resourceQueue = "resource:" + BlobStorageUtil.toStoragePath(prefix, "queue");
        this.resourceTypeExpiration = Objects.requireNonNullElseGet(settings.resourceTypesExpiration, Map::of);
        this.senderPodIdSupplier = senderPodIdSupplier;
        this.tempFolderCleaner = new TempFolderCleaner(blobStore, TEMP_FOLDER, TEMP_FILE_TTL);
        this.syncTimer = timerService.scheduleWithFixedDelay(settings.syncPeriod, settings.syncPeriod, this::sync);
        this.tempCleanupTimer = timerService.scheduleWithFixedDelay(TEMP_CLEANUP_PERIOD, TEMP_CLEANUP_PERIOD, this::cleanupTempFolder);
    }

    @SneakyThrows
    @Override
    public void close() {
        syncTimer.close();
        tempCleanupTimer.close();
    }

    /**
     * Events are published only for {@link ResourceTypes#EVENT_TYPES}.
     */
    public ResourceTopic.Subscription subscribeResources(Collection<ResourceDescriptor> resources,
                                                         Consumer<ResourceEvent> subscriber) {
        return topic.subscribe(resources, subscriber);
    }

    /**
     * Events are published only for {@link ResourceTypes#EVENT_TYPES}.
     */
    public ResourceTopic.Subscription subscribeAllResources(Consumer<ResourceEvent> subscriber) {
        return topic.subscribeAll(subscriber);
    }

    @VisibleForTesting
    ResourceTopic getTopic() {
        return topic;
    }

    public String getPrefix() {
        return lockService.getPrefix();
    }

    public LockService.Lock lockResource(ResourceDescriptor descriptor) {
        String redisKey = redisKey(descriptor);
        return lockService.lock(redisKey);
    }

    public LockService.Lock tryLockResource(ResourceDescriptor descriptor) {
        String redisKey = redisKey(descriptor);
        return lockService.tryLock(redisKey);
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

                if (!copyResource(sourceFile, targetFile, null, overwrite)) {
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

    public ResourceFolderMetadata getFolderMetadata(ResourceDescriptor descriptor, String afterMarker, int limit, boolean recursive) {
        String blobKey = blobKey(descriptor);
        PageSet<? extends StorageMetadata> set = blobStore.list(blobKey, decodeNextMarker(afterMarker), limit, recursive);

        if (set.isEmpty() && !descriptor.isRootFolder()) {
            return null;
        }

        List<MetadataBase> resources = set.stream()
                // blob store never returns folder however local FS provider may return
                .filter(meta -> !recursive || meta.getType() == StorageType.BLOB)
                .map(meta -> storageToResourceMetadata(meta, descriptor)).toList();
        String nextMarker = encodeNextMarker(set.getNextMarker());
        return new ResourceFolderMetadata(descriptor, resources, nextMarker);
    }

    /**
     * The marker returned by the blob store can contain arbitrary characters (spaces, "+", "%", ...).
     * Percent-encoding it is not enough to make it safely reusable as an opaque query parameter: some
     * callers re-encode the value before resending it, others resend it exactly as received, and the two
     * expectations conflict for a percent-encoded value ("+" is read back as a space by the query
     * parser, "%" sequences get decoded again). Encoding the marker with a URL-safe Base58 alphabet
     * avoids both "+" and "%" entirely, so the token round-trips correctly either way.
     */
    @Nullable
    private static String encodeNextMarker(@Nullable String marker) {
        if (marker == null) {
            return null;
        }
        return Base58.encode(marker.getBytes(StandardCharsets.UTF_8));
    }

    @Nullable
    private static String decodeNextMarker(@Nullable String marker) {
        if (marker == null) {
            return null;
        }
        return new String(Base58.decode(marker), StandardCharsets.UTF_8);
    }

    @SneakyThrows
    public List<Pair<ResourceItemMetadata, String>> listResources(ResourceDescriptor resourceFolder, Consumer<ResourceFolderMetadata> filter) {
        if (!resourceFolder.isFolder()) {
            throw new IllegalArgumentException("Invalid deployment folder: " + resourceFolder.getUrl());
        }
        String nextToken = null;
        List<Pair<ResourceItemMetadata, String>> result = new ArrayList<>();
        do {
            log.debug("Start getting folder metadata: {}", resourceFolder.getAbsoluteFilePath());
            ResourceFolderMetadata folder = getFolderMetadata(resourceFolder, nextToken, PAGE_SIZE, true);
            log.debug("Finish getting folder metadata: {}", resourceFolder.getAbsoluteFilePath());
            if (folder == null) {
                break;
            }

            filter.accept(folder);

            List<ResourceItemMetadata> items = folder.getItems().stream()
                    .filter(item -> item.getNodeType() == NodeType.ITEM)
                    .map(item -> (ResourceItemMetadata) item).toList();

            load(items, result);
            nextToken = folder.getNextToken();

        } while (nextToken != null);

        return result;
    }

    /**
     * Loads content of resource metadata into the result.
     */
    public void load(List<ResourceItemMetadata> items, List<Pair<ResourceItemMetadata, String>> result) {
        loadItems(items, true, result);
    }

    /**
     * Loads the stored metadata (timestamps, etag, author) of the items without their bodies, in Redis batches.
     * An item whose resource does not exist is omitted.
     */
    public List<ResourceItemMetadata> loadMetadata(List<ResourceItemMetadata> items) {
        List<Pair<ResourceItemMetadata, String>> result = new ArrayList<>();
        loadItems(items, false, result);
        return result.stream().map(Pair::getLeft).toList();
    }

    @SneakyThrows
    private void loadItems(List<ResourceItemMetadata> items, boolean withBody, List<Pair<ResourceItemMetadata, String>> result) {
        final int size = items.size();
        int batches = size / BATCH_SIZE;
        int rem = size % BATCH_SIZE;
        int cur = 0;

        List<Future<List<Pair<ResourceItemMetadata, String>>>> futures = new ArrayList<>();
        for (int i = 0; i < batches; i++) {
            int end = cur + BATCH_SIZE;
            int start = cur;
            Future<List<Pair<ResourceItemMetadata, String>>> future = VIRTUAL_THREAD_PER_TASK_EXECUTOR
                    .submit(() -> runReadBatch(start, end, items, withBody));
            futures.add(future);
            cur = end;
        }
        if (rem > 0) {
            int end = cur + rem;
            int start = cur;
            Future<List<Pair<ResourceItemMetadata, String>>> future = VIRTUAL_THREAD_PER_TASK_EXECUTOR
                    .submit(() -> runReadBatch(start, end, items, withBody));
            futures.add(future);
        }

        for (Future<List<Pair<ResourceItemMetadata, String>>> future : futures) {
            result.addAll(future.get());
        }
    }

    @SneakyThrows
    private List<Pair<ResourceItemMetadata, String>> runReadBatch(int start, int end, List<ResourceItemMetadata> items, boolean withBody) {
        if (end - start <= 0) {
            return List.of();
        }
        RBatch batch = redis.createBatch();
        List<Pair<ResourceItemMetadata, String>> result = new ArrayList<>();
        List<String> redisKeys = new ArrayList<>();
        for (int i = start; i < end; i++) {
            ResourceItemMetadata meta = items.get(i);
            String redisKey = redisKey(meta.getDescriptor());
            redisKeys.add(redisKey);
            RMapAsync<String, byte[]> map = batch.getMap(redisKey, REDIS_MAP_CODEC);
            map.getAllAsync(withBody ? REDIS_FIELDS : REDIS_FIELDS_NO_BODY);
        }
        log.debug("Start executing Redis batch with start={}, end={}", start, end);
        BatchResult<?> batchResult = batch.execute();
        log.debug("Finish executing Redis batch with start={}, end={}", start, end);
        List<ResourceItemMetadata> missed = new ArrayList<>();
        List<?> responses = batchResult.getResponses();
        for (int j = 0; j < responses.size(); j++) {
            Map<String, byte[]> fields = (Map<String, byte[]>) responses.get(j);
            String redisKey = redisKeys.get(j);
            Result redisResult = toResult(fields, redisKey);
            ResourceItemMetadata metadata = items.get(start + j);
            if (redisResult == null) {
                missed.add(metadata);
            } else if (redisResult.exists()) {
                Pair<ResourceItemMetadata, String> pair = withBody
                        ? Pair.of(toResourceItemMetadata(metadata.getDescriptor(), redisResult), new String(redisResult.body, StandardCharsets.UTF_8))
                        : Pair.of(toMetadata(metadata.getDescriptor(), redisResult), null);
                result.add(pair);
            }
            // else: cached as absent, so omitted - its blob object can still be listed until the delete
            // syncs, and a tombstone carries no body to decode
        }
        log.debug("Number of missing resources in Redis cache: {}", missed.size());
        List<Future<Pair<ResourceItemMetadata, String>>> futures = new ArrayList<>();
        for (ResourceItemMetadata metadata : missed) {
            Future<Pair<ResourceItemMetadata, String>> future = VIRTUAL_THREAD_PER_TASK_EXECUTOR
                    .submit(() -> withBody
                            ? getResourceWithMetadata(metadata.getDescriptor(), EtagHeader.ANY, false)
                            : getBlobMetadata(metadata.getDescriptor()));
            futures.add(future);
        }
        log.debug("Start loading missing resources from blob storage: {}", missed.size());
        for (Future<Pair<ResourceItemMetadata, String>> future : futures) {
            Pair<ResourceItemMetadata, String> res = future.get();
            if (res != null) {
                result.add(res);
            }
        }
        log.debug("Finish loading missed resources from blob storage: {}", missed.size());
        return result;
    }

    private static MetadataBase storageToResourceMetadata(StorageMetadata meta, ResourceDescriptor folder) {
        Map<String, String> metadata = meta.getUserMetadata();
        String path = meta.getName();
        ResourceDescriptor description = folder.resolveByPath(path);

        if (meta.getType() != StorageType.BLOB) {
            return new ResourceFolderMetadata(description);
        }

        Long createdAt = null;
        Long updatedAt = null;
        String author = null;

        if (metadata != null) {
            createdAt = metadata.containsKey(CREATED_AT_ATTRIBUTE) ? Long.parseLong(metadata.get(CREATED_AT_ATTRIBUTE)) : null;
            updatedAt = metadata.containsKey(UPDATED_AT_ATTRIBUTE) ? Long.parseLong(metadata.get(UPDATED_AT_ATTRIBUTE)) : null;
            author = decode(metadata.get(AUTHOR_ATTRIBUTE));
        }

        if (createdAt == null && meta.getCreationDate() != null) {
            createdAt = meta.getCreationDate().getTime();
        }

        if (updatedAt == null && meta.getLastModified() != null) {
            updatedAt = meta.getLastModified().getTime();
        }

        if (description.getType().requireCompression()) {
            return new ResourceItemMetadata(description).setCreatedAt(createdAt).setUpdatedAt(updatedAt).setAuthor(author);
        }

        return new FileMetadata(description, meta.getSize(), BlobStorage.resolveContentType((BlobMetadata) meta))
                .setCreatedAt(createdAt)
                .setAuthor(author)
                .setUpdatedAt(updatedAt);
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

        return toMetadata(descriptor, result);
    }

    @Nullable
    private Pair<ResourceItemMetadata, String> getBlobMetadata(ResourceDescriptor descriptor) {
        Result result = blobGet(blobKey(descriptor), false);
        return result.exists() ? Pair.of(toMetadata(descriptor, result), null) : null;
    }

    private static ResourceItemMetadata toMetadata(ResourceDescriptor descriptor, Result result) {
        return descriptor.getType().requireCompression()
                ? toResourceItemMetadata(descriptor, result)
                : toFileMetadata(descriptor, result);
    }

    private static ResourceItemMetadata toResourceItemMetadata(
            ResourceDescriptor descriptor, Result result) {
        return new ResourceItemMetadata(descriptor)
                .setCreatedAt(result.createdAt)
                .setUpdatedAt(result.updatedAt)
                .setEtag(result.etag)
                .setAuthor(result.author);
    }

    private static FileMetadata toFileMetadata(
            ResourceDescriptor resource, Result result) {
        long contentLength = result.contentLength() == null ? 0L : result.contentLength();
        return (FileMetadata) new FileMetadata(resource, contentLength, result.contentType())
                .setCreatedAt(result.createdAt)
                .setUpdatedAt(result.updatedAt)
                .setAuthor(result.author)
                .setEtag(result.etag);
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
    public Pair<ResourceItemMetadata, String> getResourceWithMetadata(ResourceDescriptor descriptor, EtagHeader etag) {
        return getResourceWithMetadata(descriptor, etag, true);
    }

    @Nullable
    private Pair<ResourceItemMetadata, String> getResourceWithMetadata(ResourceDescriptor descriptor, EtagHeader etagHeader, boolean lock) {
        Pair<ResourceItemMetadata, byte[]> result = getResourceBytesWithMetadata(descriptor, etagHeader, lock);
        if (result == null) {
            return null;
        }
        return Pair.of(result.getLeft(), new String(result.getRight(), StandardCharsets.UTF_8));
    }

    @Nullable
    public String getResource(ResourceDescriptor descriptor) {
        return getResource(descriptor, EtagHeader.ANY, true);
    }

    @Nullable
    public String getResource(ResourceDescriptor descriptor, EtagHeader etag, boolean lock) {
        Pair<ResourceItemMetadata, String> result = getResourceWithMetadata(descriptor, etag, lock);
        return (result == null) ? null : result.getRight();
    }

    @Nullable
    public byte[] getResourceBytes(ResourceDescriptor descriptor) {
        Pair<ResourceItemMetadata, byte[]> result = getResourceBytesWithMetadata(descriptor, EtagHeader.ANY, true);
        return (result == null) ? null : result.getRight();
    }

    @Nullable
    private Pair<ResourceItemMetadata, byte[]> getResourceBytesWithMetadata(ResourceDescriptor descriptor, EtagHeader etagHeader, boolean lock) {
        return trace("resource.get", descriptor, () -> {
            String redisKey = redisKey(descriptor);
            Result result = redisGet(redisKey, true);
            boolean cacheHit = true;

            if (result == null) {
                try (var ignore = lock ? lockService.lock(redisKey) : null) {
                    result = redisGet(redisKey, true);

                    if (result == null) {
                        cacheHit = false;
                        String blobKey = blobKey(descriptor);
                        result = blobGet(blobKey, true);
                        // only if Redis still has no record: a write since the miss (one made without the lock, or any
                        // write when this read holds no lock) is newer than the blob copy. The read returns its own
                        // copy either way, which may be a few updates behind
                        putIfAbsent(redisKey, result);
                    }
                }
            }
            BlockingCallTracer.currentSpan().setAttribute("dial.cache.hit", cacheHit);

            etagHeader.validate(result.etag);

            if (result.exists()) {
                return Pair.of(toResourceItemMetadata(descriptor, result), result.body);
            }

            return null;
        });
    }

    public ResourceStream getResourceStream(ResourceDescriptor resource, EtagHeader etagHeader) throws IOException {
        if (resource.getType().requireCompression()) {
            throw new IllegalArgumentException("Streaming is supported for uncompressed resources only");
        }

        String key = redisKey(resource);
        Result result = redisGet(key, true);
        if (result != null) {
            return ResourceStream.fromResult(result, etagHeader);
        }

        try (LockService.Lock ignored = lockService.lock(key)) {
            result = redisGet(key, true);
            if (result != null) {
                return ResourceStream.fromResult(result, etagHeader);
            }

            Blob blob = blobStore.load(resource.getAbsoluteFilePath());
            // the fills below, as in getResourceBytesWithMetadata: only if Redis still has no record
            if (blob == null) {
                putIfAbsent(key, Result.DELETED_SYNCED);
                return null;
            }

            Payload payload = blob.getPayload();
            BlobMetadata metadata = blob.getMetadata();
            String etag = extractEtag(metadata);
            String contentType = metadata.getContentMetadata().getContentType();
            Long length = metadata.getContentMetadata().getContentLength();

            if (length == null || length <= maxSizeToCache) {
                result = blobToResult(blob, metadata);
                putIfAbsent(key, result);
                return ResourceStream.fromResult(result, etagHeader);
            }

            etagHeader.validate(etag);
            return new ResourceStream(payload.openStream(), etag, contentType, length);
        }
    }

    public ResourceItemMetadata putResource(
            ResourceDescriptor descriptor, String body, EtagHeader etag, String author) {
        return putResource(descriptor, body, etag, author, true);
    }

    public ResourceItemMetadata putResource(
            ResourceDescriptor descriptor, String body, EtagHeader etag) {
        return putResource(descriptor, body, etag, null, true);
    }

    public ResourceItemMetadata putResource(
            ResourceDescriptor descriptor, String body, EtagHeader etag, String author, boolean lock) {
        return putResource(descriptor, body, etag, author, lock, null);
    }

    public ResourceItemMetadata putResource(
            ResourceDescriptor descriptor, String body, EtagHeader etag, String author, boolean lock,
            @Nullable Map<String, String> eventMetadata) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return putResource(descriptor, bytes, etag, "application/json", author, lock, eventMetadata);
    }

    private ResourceItemMetadata putResource(
            ResourceDescriptor descriptor,
            byte[] body,
            EtagHeader etagHeader,
            String contentType,
            String author,
            boolean lock) {
        return putResource(descriptor, body, etagHeader, contentType, author, lock, null);
    }

    private ResourceItemMetadata putResource(
            ResourceDescriptor descriptor,
            byte[] body,
            EtagHeader etagHeader,
            String contentType,
            String author,
            boolean lock,
            @Nullable Map<String, String> eventMetadata) {
        return trace("resource.put", descriptor, () -> putResourceTraced(descriptor, body, etagHeader, contentType, author, lock, eventMetadata));
    }

    private ResourceItemMetadata putResourceTraced(
            ResourceDescriptor descriptor,
            byte[] body,
            EtagHeader etagHeader,
            String contentType,
            String author,
            boolean lock,
            @Nullable Map<String, String> eventMetadata) {
        String redisKey = redisKey(descriptor);

        try (var ignore = lock ? lockService.lock(redisKey) : null) {
            ResourceItemMetadata metadata = getResourceMetadata(descriptor);

            String etag;
            if (metadata != null) {
                etag = metadata.getEtag();
                author = metadata.getAuthor();
            } else {
                etag = null;
            }

            etagHeader.validate(etag);

            Long updatedAt = time();
            Long createdAt = metadata == null ? updatedAt : metadata.getCreatedAt();
            String newEtag = EtagBuilder.generateEtag(body);
            Result result = new Result(body, newEtag, createdAt, updatedAt, contentType,
                    descriptor.getType().requireCompression(), (long) body.length, descriptor.getType().name(), author, false);
            if (body.length <= maxSizeToCache) {
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

            ResourceEvent.Action action = metadata == null
                    ? ResourceEvent.Action.CREATE
                    : ResourceEvent.Action.UPDATE;
            publishEvent(descriptor, action, updatedAt, newEtag, eventMetadata);
            return descriptor.getType().requireCompression()
                    ? toResourceItemMetadata(descriptor, result)
                    : toFileMetadata(descriptor, result);
        }
    }

    public ResourceItemMetadata putResourceBytes(
            ResourceDescriptor descriptor, byte[] body, EtagHeader etag) {
        return putResource(descriptor, body, etag, "application/json", null, true);
    }

    public ResourceItemMetadata putResourceBytes(
            ResourceDescriptor descriptor, byte[] body, EtagHeader etag, String author, boolean lock) {
        return putResource(descriptor, body, etag, "application/json", author, lock);
    }

    public FileMetadata putFile(ResourceDescriptor descriptor, byte[] body, EtagHeader etag, String contentType, String author) {
        if (descriptor.getType().requireCompression()) {
            throw new IllegalArgumentException("Resource must be uncompressed, got %s".formatted(descriptor.getType()));
        }

        return (FileMetadata) putResource(descriptor, body, etag, contentType, author, true);
    }

    public ResourceUpload initFileUpload(ResourceDescriptor resource, String contentType, EtagHeader etagHeader, String author) {
        String redisKey = redisKey(resource);
        try (var ignore = lockService.lock(redisKey)) {
            ResourceItemMetadata metadata = getResourceMetadata(resource);
            if (metadata != null) {
                etagHeader.validate(metadata.getEtag());
                author = metadata.getAuthor();
            }

            long updatedAt = time();
            long createdAt = metadata == null ? updatedAt : metadata.getCreatedAt();
            Map<String, String> userMetadata = toUserMetadata(null, createdAt, updatedAt, resource.getType().name(), author);
            // Assemble the upload in an isolated temporary location to avoid concurrent modifications of the
            // target key while the multipart upload is in progress (see issue #1604).
            String tempPath = tempBlobPath();
            MultipartUpload mpu = blobStore.initMultipartUpload(tempPath, contentType, userMetadata);
            return new ResourceUpload(blobStore, mpu, contentType, userMetadata, tempPath);
        }
    }

    private static String tempBlobPath() {
        String separator = ResourceDescriptor.PATH_SEPARATOR;
        String folder = LocalDateTime.now().format(TEMP_FOLDER_FORMATTER);
        return TEMP_FOLDER + separator + folder + separator + UUID.randomUUID();
    }

    /**
     * Scans the {@link #TEMP_FOLDER} and removes expired files. A file is expired when the hour denoted by its parent
     * folder name (see {@link #TEMP_FOLDER_FORMATTER}) ended more than {@link #TEMP_FILE_TTL} ago. The job runs every
     * {@link #TEMP_CLEANUP_PERIOD} to clean up leftovers from interrupted or abandoned uploads, see issue #1604.
     */
    @VisibleForTesting
    Void cleanupTempFolder() {
        tempFolderCleaner.cleanup();
        return null;
    }

    public FileMetadata finishFileUpload(ResourceDescriptor descriptor, ResourceUpload resourceUpload, EtagHeader etagHeader) {
        String redisKey = redisKey(descriptor);
        try (var ignore = lockService.lock(redisKey)) {
            ResourceItemMetadata metadata = getResourceMetadata(descriptor);
            if (metadata != null) {
                etagHeader.validate(metadata.getEtag());
            }
            flushToBlobStore(redisKey);

            MultipartUpload multipartUpload = resourceUpload.getMultipartUpload();
            List<MultipartPart> parts = resourceUpload.getParts();

            blobStore.completeMultipartUpload(multipartUpload, parts);
            // Move the assembled blob from the temporary location to the target resource. Pass the user metadata
            // explicitly so the author/timestamps survive the copy regardless of the blob storage provider.
            String tempPath = resourceUpload.getTempPath();
            String etag = resourceUpload.calculateEtag();
            Map<String, String> userMetadata = resourceUpload.getUserMetadata();
            userMetadata.put(ETAG_ATTRIBUTE, etag);
            try {
                blobStore.copy(tempPath, descriptor.getAbsoluteFilePath(), userMetadata);
            } finally {
                blobStore.delete(tempPath);
            }

            long updatedAt = Long.parseLong(userMetadata.get(UPDATED_AT_ATTRIBUTE));
            long createdAt = Long.parseLong(userMetadata.get(CREATED_AT_ATTRIBUTE));

            ResourceEvent.Action action = metadata == null
                    ? ResourceEvent.Action.CREATE
                    : ResourceEvent.Action.UPDATE;
            publishEvent(descriptor, action, updatedAt, etag);

            return (FileMetadata) new FileMetadata(
                    descriptor, resourceUpload.getContentLength(), resourceUpload.getContentType())
                    .setCreatedAt(createdAt)
                    .setUpdatedAt(updatedAt)
                    .setEtag(etag);
        }
    }

    public ResourceItemMetadata computeResource(ResourceDescriptor descriptor, Function<String, String> fn) {
        return computeResource(descriptor, EtagHeader.ANY, fn);
    }

    public ResourceItemMetadata computeResource(ResourceDescriptor descriptor, EtagHeader etag, Function<String, String> fn) {
        return computeResource(descriptor, etag, null, fn);
    }

    public ResourceItemMetadata computeResource(ResourceDescriptor descriptor, EtagHeader etag, String author, Function<String, String> fn) {
        Function<byte[], byte[]> wrapper = bytes -> {
            String json = null;
            if (bytes != null) {
                json = new String(bytes, StandardCharsets.UTF_8);
            }
            String result = fn.apply(json);
            if (result != null) {
                return result.getBytes(StandardCharsets.UTF_8);
            }
            return null;
        };
        return computeResourceBytes(descriptor, etag, author, wrapper);
    }

    public ResourceItemMetadata computeResourceBytes(ResourceDescriptor descriptor, Function<byte[], byte[]> fn) {
        return computeResourceBytes(descriptor, EtagHeader.ANY, null, fn);
    }

    public ResourceItemMetadata computeResourceBytes(ResourceDescriptor descriptor, EtagHeader etag, String author, Function<byte[], byte[]> fn) {
        return trace("resource.compute", descriptor, () -> computeResourceBytesTraced(descriptor, etag, author, fn));
    }

    private ResourceItemMetadata computeResourceBytesTraced(ResourceDescriptor descriptor, EtagHeader etag, String author, Function<byte[], byte[]> fn) {
        String redisKey = redisKey(descriptor);

        try (var ignore = lockService.lock(redisKey)) {
            Pair<ResourceItemMetadata, byte[]> oldResult = getResourceBytesWithMetadata(descriptor, etag, false);

            byte[] oldBody = oldResult == null ? null : oldResult.getValue();
            byte[] newBody = fn.apply(oldBody);

            if (oldBody == null && newBody == null) {
                return null;
            }

            if (oldBody != null && newBody == null) {
                deleteResource(descriptor, etag, false);
                return oldResult.getKey();
            }

            if (Arrays.equals(oldBody, newBody)) {
                return oldResult.getKey();
            }

            return putResourceBytes(descriptor, newBody, etag, author, false);
        }
    }

    /**
     * Like {@link #computeResource}, without the resource lock: the result is written only if the record still has the
     * etag it was read with, else {@code fn} runs again on the newer value. Only for records that no other method
     * writes: a locked write does not check the etag, so it would replace a write made here. {@code fn} may run
     * several times and must not delete the record; a value over {@code maxSizeToCache} is rejected.
     */
    public void computeResourceWithoutLock(ResourceDescriptor descriptor, Function<String, String> fn) {
        trace("resource.compute", descriptor, () -> computeResourceWithoutLockTraced(descriptor, fn));
    }

    private Void computeResourceWithoutLockTraced(ResourceDescriptor descriptor, Function<String, String> fn) {
        String redisKey = redisKey(descriptor);
        Runnable afterWrite;
        // writers of this pod take turns, so only one writer per pod races in the compare-and-set
        try (var ignore = lockService.lockLocally(redisKey)) {
            afterWrite = computeResourceWithRetries(descriptor, redisKey, fn);
        }
        // after the turn, so the next writer of this pod does not wait for the queue entry or the stub
        afterWrite.run();
        return null;
    }

    private Runnable computeResourceWithRetries(ResourceDescriptor descriptor, String redisKey, Function<String, String> fn) {
        for (int attempt = 1; attempt <= MAX_COMPUTE_WITHOUT_LOCK_ATTEMPTS; attempt++) {
            if (attempt > 1) {
                long backoffMillis = ThreadLocalRandom.current().nextLong(1L << Math.min(attempt - 1, MAX_COMPUTE_WITHOUT_LOCK_BACKOFF_SHIFT));
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(backoffMillis));
            }
            BlockingCallTracer.currentSpan().setAttribute("dial.resource.attempts", attempt);
            // the first read may come from a replica: a value older than the last write fails the compare-and-set,
            // and the next attempts read the master
            Map<String, byte[]> fields = (attempt == 1) ? readFieldsReplicaFirst(redisKey) : readFieldsOnMaster(redisKey, false);
            Runnable afterWrite = tryComputeResource(descriptor, redisKey, fields, fn);
            if (afterWrite != null) {
                return afterWrite;
            }
        }
        COMPUTE_GIVE_UPS.increment();
        throw new IllegalStateException("Resource changed concurrently on every attempt: " + redisKey);
    }

    /**
     * One round of {@link #computeResourceWithoutLock}: runs {@code fn} on the record that was read and writes the
     * result if the record did not change meanwhile.
     *
     * @param fields the record as read from Redis, empty if Redis has none
     * @return what is left to do after the pod's turn, or null if the record changed since it was read
     */
    @Nullable
    private Runnable tryComputeResource(ResourceDescriptor descriptor, String redisKey, Map<String, byte[]> fields, Function<String, String> fn) {
        // with no record in Redis the value comes from blob storage, and the compare-and-set creates the record
        boolean fromBlob = fields.isEmpty();
        Result current = fromBlob ? blobGet(blobKey(descriptor), true) : toResult(fields, redisKey);

        String oldValue = current.exists() ? new String(current.body, StandardCharsets.UTF_8) : null;
        String newValue = fn.apply(oldValue);
        if (Objects.equals(oldValue, newValue)) {
            if (fromBlob && current.exists()) {
                // nothing to write, so cache the blob copy as a read would, and the next writer finds it in Redis
                putIfAbsent(redisKey, current);
            }
            return () -> {};
        }

        Result next = buildComputedResult(descriptor, current, newValue);
        byte[] expectedEtag = fromBlob
                ? RedisUtil.stringToRedis(Objects.requireNonNullElse(current.etag, ""))
                : fields.getOrDefault(ETAG_ATTRIBUTE, RedisUtil.EMPTY_ARRAY);
        if (!writeIfUnchanged(redisKey, expectedEtag, fromBlob, next)) {
            return null;
        }
        boolean created = !current.exists();
        return () -> finishWrite(descriptor, redisKey, next, created);
    }

    private Result buildComputedResult(ResourceDescriptor descriptor, Result current, String value) {
        byte[] body = Objects.requireNonNull(value, "computeResourceWithoutLock cannot delete").getBytes(StandardCharsets.UTF_8);
        if (body.length > maxSizeToCache) {
            throw new IllegalArgumentException("Resource is too large to compute without a lock: " + body.length);
        }

        // boxed, so a record with no creation time (a blob written before created_at was kept) keeps none
        Long updatedAt = time();
        return new Result(body, EtagBuilder.generateEtag(body), current.exists() ? current.createdAt : updatedAt,
                updatedAt, "application/json", descriptor.getType().requireCompression(), (long) body.length,
                descriptor.getType().name(), current.author, false);
    }

    // false if the record changed since it was read
    private boolean writeIfUnchanged(String redisKey, byte[] expectedEtag, boolean createIfAbsent, Result next) {
        try {
            return compareAndSet(redisKey, expectedEtag, createIfAbsent, next);
        } catch (RuntimeException e) {
            // the write may have been applied (a reply lost to a timeout), and nothing else would queue it. An
            // entry for a record that was not written is harmless: the sync drops it
            try {
                queueIfAbsent(redisKey);
            } catch (RuntimeException queueError) {
                e.addSuppressed(queueError);
            }
            throw e;
        }
    }

    /**
     * What a write without the lock leaves for after the pod's turn. The queue entry comes after the write, so a sync
     * that dropped the entry meanwhile cannot leave the write unqueued. Redisson resends it after a timeout (adding
     * twice is harmless); if it still fails, the caller fails though the write counted, and the record stays persisted
     * until its next write queues it.
     */
    private void finishWrite(ResourceDescriptor descriptor, String redisKey, Result written, boolean created) {
        queueIfAbsent(redisKey);
        if (created) {
            // only the writer that created the record writes the stub, after its write as a locked write does
            putStubIfAbsent(descriptor, redisKey, written);
        }
        publishEvent(descriptor, created ? ResourceEvent.Action.CREATE : ResourceEvent.Action.UPDATE, written.updatedAt, written.etag);
    }

    // the first unsynced write sets the deadline, so a record written more often than syncDelay is still synced
    private void queueIfAbsent(String redisKey) {
        redis.getScoredSortedSet(resourceQueue, StringCodec.INSTANCE).addIfAbsent(time() + syncDelay, redisKey);
    }

    /**
     * Creates the empty object that makes a new record listable before its first sync: under the lock a sync takes and
     * only while no object exists, so the stub never replaces a body a sync uploaded. With the lock busy a sync is at
     * the record: its upload, or the next sync's if it read the key before the record was created, makes it listable.
     * The stub has no etag: with the record's etag, a writer that loaded the stub from blob storage would pass the
     * compare-and-set against the record with a value computed from an empty body.
     */
    private void putStubIfAbsent(ResourceDescriptor descriptor, String redisKey, Result result) {
        try (var lock = lockService.tryLock(redisKey)) {
            String blobKey = blobKey(descriptor);
            if (lock != null && !blobExists(blobKey)) {
                blobPut(blobKey, result.toStub().toBuilder().etag(null).build());
            }
        }
    }

    private static Object[] buildReadFieldsArgs(byte[] skippedSyncedFlag) {
        List<Object> args = new ArrayList<>();
        args.add(skippedSyncedFlag);
        REDIS_FIELD_LIST.forEach(name -> args.add(name.getBytes(StandardCharsets.UTF_8)));
        return args.toArray();
    }

    // a plain HMGET, which goes where the client's read mode sends it (Redisson reads replicas by default), so an update
    // with no conflict costs the master only the compare-and-set and the queue entry
    private Map<String, byte[]> readFieldsReplicaFirst(String redisKey) {
        RMap<String, byte[]> map = redis.getMap(redisKey, REDIS_MAP_CODEC);
        Map<String, byte[]> fields = map.getAll(REDIS_FIELDS);
        // a replica may not have a new record yet, and blob storage is read only if the master has none
        return fields.isEmpty() ? readFieldsOnMaster(redisKey, false) : fields;
    }

    // a script run as a write, so it reads the master and the fields include the last write; empty if there is no record
    // or, with unsyncedOnly, if it is synced
    private Map<String, byte[]> readFieldsOnMaster(String redisKey, boolean unsyncedOnly) {
        List<Object> values = redis.getScript(ByteArrayCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, READ_FIELDS_SCRIPT,
                RScript.ReturnType.LIST, List.of(redisKey), unsyncedOnly ? READ_UNSYNCED_FIELDS_ARGS : READ_FIELDS_ARGS);
        Map<String, byte[]> fields = new HashMap<>();
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i) instanceof byte[] value) {
                fields.put(REDIS_FIELD_LIST.get(i), value);
            }
        }
        return fields;
    }

    /**
     * @param createIfAbsent the value came from blob storage: the record is created if Redis still has none
     */
    @VisibleForTesting
    boolean compareAndSet(String redisKey, byte[] expectedEtag, boolean createIfAbsent, Result result) {
        List<Object> args = new ArrayList<>();
        args.add(expectedEtag);
        args.add(RedisUtil.booleanToRedis(createIfAbsent));
        addFieldArgs(args, result);
        return evalBoolean(COMPARE_AND_SET_SCRIPT, redisKey, args);
    }

    /**
     * Caches a value loaded from blob storage unless Redis already holds the record: a write made since the load,
     * with or without the lock, is newer than the blob.
     */
    @VisibleForTesting
    boolean putIfAbsent(String redisKey, Result result) {
        List<Object> args = new ArrayList<>();
        args.add(RedisUtil.longToRedis(getExpiration(redisKey).toMillis()));
        addFieldArgs(args, result);
        return evalBoolean(PUT_IF_ABSENT_SCRIPT, redisKey, args);
    }

    private static void addFieldArgs(List<Object> args, Result result) {
        toRedisFields(result).forEach((field, value) -> {
            args.add(field.getBytes(StandardCharsets.UTF_8));
            args.add(value);
        });
    }

    private boolean evalBoolean(String script, String key, List<Object> args) {
        return redis.getScript(ByteArrayCodec.INSTANCE)
                .eval(RScript.Mode.READ_WRITE, script, RScript.ReturnType.BOOLEAN, List.of(key), args.toArray());
    }

    public boolean deleteResource(ResourceDescriptor descriptor, EtagHeader etag) {
        return deleteResource(descriptor, etag, true);
    }

    public boolean deleteResource(ResourceDescriptor descriptor, EtagHeader etag, boolean lock) {
        return deleteResource(descriptor, etag, lock, null);
    }

    public boolean deleteResource(ResourceDescriptor descriptor, EtagHeader etag, boolean lock,
            @Nullable Map<String, String> eventMetadata) {
        return trace("resource.delete", descriptor, () -> deleteResourceTraced(descriptor, etag, lock, eventMetadata));
    }

    private boolean deleteResourceTraced(ResourceDescriptor descriptor, EtagHeader etag, boolean lock,
            @Nullable Map<String, String> eventMetadata) {
        String redisKey = redisKey(descriptor);

        try (var ignore = lock ? lockService.lock(redisKey) : null) {
            ResourceItemMetadata metadata = getResourceMetadata(descriptor);

            etag.validate(metadata == null ? null : metadata.getEtag());

            if (metadata == null) {
                return false;
            }

            redisPut(redisKey, Result.DELETED_NOT_SYNCED);
            blobDelete(blobKey(descriptor));
            redisSync(redisKey);

            publishEvent(descriptor, ResourceEvent.Action.DELETE, time(), null, eventMetadata);
            return true;
        }
    }

    public boolean copyResource(ResourceDescriptor from, ResourceDescriptor to) {
        return copyResource(from, to, null, true);
    }

    public boolean copyResource(ResourceDescriptor from, ResourceDescriptor to, UserMetadata userMetadata, boolean overwrite) {
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
                blobStore.copy(blobKey(from), blobKey(to), toUserMetaData(userMetadata));

                ResourceEvent.Action action = toMetadata == null
                        ? ResourceEvent.Action.CREATE
                        : ResourceEvent.Action.UPDATE;
                publishEvent(to, action, time(), fromMetadata.getEtag());
                return true;
            }

            return false;
        }
    }

    @Nullable
    private Map<String, String> toUserMetaData(UserMetadata userMetadata) {
        if (userMetadata == null) {
            return null;
        }
        return toUserMetadata(userMetadata.getEtag(), userMetadata.getCreatedAt(),
                userMetadata.getUpdatedAt(), userMetadata.getResourceType(), userMetadata.getAuthor());
    }

    private void publishEvent(ResourceDescriptor descriptor, ResourceEvent.Action action, long timestamp, String etag) {
        publishEvent(descriptor, action, timestamp, etag, null);
    }

    private void publishEvent(ResourceDescriptor descriptor, ResourceEvent.Action action, long timestamp, String etag,
            @Nullable Map<String, String> metadata) {
        if (descriptor.getType().isComplex()) {
            // Writes of a complex resource are internal (marker, version files): the complex resource engine
            // publishes events for the logical resource and file URLs via publishLogicalEvent.
            return;
        }
        publishTopicEvent(descriptor, action, timestamp, etag, metadata);
    }

    /**
     * Publishes an event for a logical URL of a complex resource (the resource itself or a file inside it).
     */
    public void publishLogicalEvent(ResourceDescriptor descriptor, ResourceEvent.Action action, long timestamp,
                                    @Nullable String etag) {
        publishTopicEvent(descriptor, action, timestamp, etag, null);
    }

    /**
     * Tells every pod that a resource changed, whatever its type, for a change made outside the write path of this
     * service, such as a direct blob copy.
     */
    public void publishUpdate(ResourceDescriptor descriptor) {
        publishUpdate(descriptor.getUrl());
    }

    /**
     * Same as {@link #publishUpdate(ResourceDescriptor)} for an event that is not about one resource, with a
     * {@code url} that no resource has.
     */
    public void publishUpdate(String url) {
        ResourceEvent event = new ResourceEvent()
                .setUrl(url)
                .setAction(ResourceEvent.Action.UPDATE)
                .setTimestamp(System.currentTimeMillis())
                .setSenderPodId(senderPodIdSupplier.get());
        topic.publish(event);
    }

    private void publishTopicEvent(ResourceDescriptor descriptor, ResourceEvent.Action action, long timestamp, String etag,
            @Nullable Map<String, String> metadata) {
        if (!ResourceTypes.EVENT_TYPES.contains(descriptor.getType())) {
            return;
        }

        ResourceEvent event = new ResourceEvent()
                .setUrl(descriptor.getUrl())
                .setAction(action)
                .setTimestamp(timestamp)
                .setEtag(etag)
                .setSenderPodId(senderPodIdSupplier.get())
                .setMetadata(metadata);

        topic.publish(event);
    }

    private Pair<String, String> toOrderedPair(String a, String b) {
        return a.compareTo(b) > 0 ? Pair.of(a, b) : Pair.of(b, a);
    }

    @VisibleForTesting
    Void sync() {
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

    /**
     * Uploads the record if it is not synced yet. A write without the lock ({@link #computeResourceWithoutLock}) can
     * land at any point of this, and it queues the record only if it is not queued yet. So the record is marked synced
     * only if it still has the etag that was uploaded, the queue entry is dropped only after that, and it is put back
     * if the record is not synced any more. The entry outlives a crash at any point before the mark. A crash between
     * the removal and the check after it can leave a write that landed since the mark unqueued until the next write.
     */
    private RMap<String, byte[]> sync(String redisKey) {
        log.debug("Syncing resource: {}", redisKey);
        RMap<String, byte[]> map = redis.getMap(redisKey, REDIS_MAP_CODEC);
        byte[] uploadedEtag = null;
        // on the master: a lagging replica would upload an older body, and flushToBlobStore deletes the record after this.
        // Nothing is read of a synced record: it has nothing to upload, and its body can be large
        Map<String, byte[]> fields = readFieldsOnMaster(redisKey, true);
        Result result = toResult(fields, redisKey);
        if (result != null) {
            String blobKey = blobKeyFromRedisKey(redisKey);
            if (result.exists()) {
                log.debug("Syncing resource: {}. Blob updating", redisKey);
                blobPut(blobKey, result);
            } else {
                log.debug("Syncing resource: {}. Blob deleting", redisKey);
                blobDelete(blobKey);
            }
            uploadedEtag = fields.getOrDefault(ETAG_ATTRIBUTE, RedisUtil.EMPTY_ARRAY);
        }

        RScoredSortedSet<String> queue = redis.getScoredSortedSet(resourceQueue, StringCodec.INSTANCE);
        // a record that is not synced (a write landed since the read, or the mark failed) keeps its entry for the next sync
        if ((uploadedEtag == null) ? expireIfSynced(redisKey) : markSynced(redisKey, uploadedEtag)) {
            boolean synced = false;
            try {
                queue.remove(redisKey);
                synced = expireIfSynced(redisKey);
            } finally {
                // also when the removal or the check fails (the removal may have been applied): a write that landed
                // before the removal found the entry and queued nothing
                if (!synced) {
                    queue.addIfAbsent(time() + syncDelay, redisKey);
                }
            }
        } else if (uploadedEtag != null) {
            // the blob was just refreshed, so the next upload is due a syncDelay later, not on every pod's next tick
            queue.add(time() + syncDelay, redisKey);
        }
        return map;
    }

    // sets the expiration of a synced record that has none; false if the record is not synced
    @VisibleForTesting
    boolean expireIfSynced(String redisKey) {
        return evalBoolean(EXPIRE_IF_SYNCED_SCRIPT, redisKey,
                List.of(RedisUtil.BOOLEAN_TRUE_ARRAY, RedisUtil.longToRedis(getExpiration(redisKey).toMillis())));
    }

    @VisibleForTesting
    boolean markSynced(String redisKey, byte[] uploadedEtag) {
        return evalBoolean(MARK_SYNCED_SCRIPT, redisKey,
                List.of(uploadedEtag, RedisUtil.BOOLEAN_TRUE_ARRAY, RedisUtil.longToRedis(getExpiration(redisKey).toMillis())));
    }

    private <T> T trace(String name, ResourceDescriptor descriptor, Callable<T> work) {
        return blockingCallTracer.trace(name, () -> {
            BlockingCallTracer.currentSpan().setAttribute("dial.resource.type", descriptor.getType().name());
            return work.call();
        });
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
        String etag = extractEtag(meta);
        String contentType = meta.getContentMetadata().getContentType();
        Long contentLength = meta.getContentMetadata().getContentLength();
        Long createdAt = meta.getUserMetadata().containsKey(CREATED_AT_ATTRIBUTE)
                ? Long.parseLong(meta.getUserMetadata().get(CREATED_AT_ATTRIBUTE))
                : null;
        Long updatedAt = meta.getUserMetadata().containsKey(UPDATED_AT_ATTRIBUTE)
                ? Long.parseLong(meta.getUserMetadata().get(UPDATED_AT_ATTRIBUTE))
                : null;
        String resourceType = meta.getUserMetadata().get(RESOURCE_TYPE_ATTRIBUTE);
        String author = decode(meta.getUserMetadata().get(AUTHOR_ATTRIBUTE));

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

        return new Result(body, etag, createdAt, updatedAt, contentType, null, contentLength, resourceType, author, true);
    }

    private void blobPut(String key, Result result) {
        String encoding = null;
        byte[] bytes = result.body;
        if (bytes.length >= compressionMinSize && Boolean.TRUE.equals(result.compress)) {
            encoding = "gzip";
            bytes = Compression.compress(encoding, bytes);
        }

        Map<String, String> metadata = toUserMetadata(result.etag, result.createdAt, result.updatedAt, result.resourceType, result.author);
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
        return toResult(fields, key);
    }

    @Nullable
    private Result toResult(Map<String, byte[]> fields, String key) {
        if (fields.isEmpty()) {
            return null;
        }

        boolean exists = Objects.requireNonNull(RedisUtil.redisToBoolean(fields.get(EXISTS_ATTRIBUTE)));
        boolean synced = Objects.requireNonNull(RedisUtil.redisToBoolean(fields.get(SYNCED_ATTRIBUTE)));
        if (!exists) {
            return synced ? Result.DELETED_SYNCED : Result.DELETED_NOT_SYNCED;
        }

        byte[] body = fields.getOrDefault(BODY_ATTRIBUTE, ArrayUtils.EMPTY_BYTE_ARRAY);
        String etag = EtagHeader.quoteIfNeeded(RedisUtil.redisToString(fields.get(ETAG_ATTRIBUTE), DEFAULT_ETAG));
        String contentType = RedisUtil.redisToString(fields.get(CONTENT_TYPE_ATTRIBUTE), null);
        Long contentLength = RedisUtil.redisToLong(fields.get(CONTENT_LENGTH_ATTRIBUTE));
        Long createdAt = RedisUtil.redisToLong(fields.get(CREATED_AT_ATTRIBUTE));
        Long updatedAt = RedisUtil.redisToLong(fields.get(UPDATED_AT_ATTRIBUTE));
        String resourceType = RedisUtil.redisToString(fields.get(RESOURCE_TYPE_ATTRIBUTE), null);
        String author = RedisUtil.redisToString(fields.get(AUTHOR_ATTRIBUTE), null);
        // we have to maintain historical data which are already in the cache, but they don't have the field
        Boolean compress = RedisUtil.redisToBoolean(fields.get(COMPRESS_ATTRIBUTE), !key.startsWith("file:"));

        return new Result(body, etag, createdAt, updatedAt, contentType, compress, contentLength, resourceType, author, synced);
    }

    private void redisPut(String key, Result result) {
        RScoredSortedSet<String> set = redis.getScoredSortedSet(resourceQueue, StringCodec.INSTANCE);
        set.add(time() + syncDelay, key); // add resource to sync set before changing because calls below can fail

        RMap<String, byte[]> map = redis.getMap(key, REDIS_MAP_CODEC);

        if (!result.synced) {
            map.clearExpire();
        }

        map.putAll(toRedisFields(result));

        if (result.synced) { // cleanup because it is already synced
            Duration expiration = getExpiration(key);
            map.expire(expiration);
            set.remove(key);
        }
    }

    private static Map<String, byte[]> toRedisFields(Result result) {
        Map<String, byte[]> fields = new HashMap<>();
        if (result.exists()) {
            fields.put(BODY_ATTRIBUTE, result.body);
            fields.put(ETAG_ATTRIBUTE, RedisUtil.stringToRedis(result.etag));
            fields.put(CREATED_AT_ATTRIBUTE, RedisUtil.longToRedis(result.createdAt));
            fields.put(UPDATED_AT_ATTRIBUTE, RedisUtil.longToRedis(result.updatedAt));
            fields.put(RESOURCE_TYPE_ATTRIBUTE, RedisUtil.stringToRedis(result.resourceType));
            fields.put(CONTENT_TYPE_ATTRIBUTE, RedisUtil.stringToRedis(result.contentType));
            fields.put(CONTENT_LENGTH_ATTRIBUTE, RedisUtil.longToRedis(result.contentLength));
            fields.put(EXISTS_ATTRIBUTE, RedisUtil.BOOLEAN_TRUE_ARRAY);
            fields.put(AUTHOR_ATTRIBUTE, RedisUtil.stringToRedis(result.author));
        } else {
            REDIS_FIELDS.forEach(field -> fields.put(field, RedisUtil.EMPTY_ARRAY));
            fields.put(EXISTS_ATTRIBUTE, RedisUtil.BOOLEAN_FALSE_ARRAY);
        }
        fields.put(SYNCED_ATTRIBUTE, RedisUtil.booleanToRedis(result.synced));
        return fields;
    }

    private RMap<String, byte[]> redisSync(String key) {
        RMap<String, byte[]> map = redis.getMap(key, REDIS_MAP_CODEC);
        map.put(SYNCED_ATTRIBUTE, RedisUtil.BOOLEAN_TRUE_ARRAY);
        Duration expiration = getExpiration(key);
        map.expire(expiration);

        RScoredSortedSet<String> set = redis.getScoredSortedSet(resourceQueue, StringCodec.INSTANCE);
        set.remove(key);

        return map;
    }

    private String redisKey(ResourceDescriptor descriptor) {
        return RedisUtil.redisKey(descriptor, prefix);
    }

    private static long time() {
        return System.currentTimeMillis();
    }

    private void flushToBlobStore(String redisKey) {
        RMap<String, byte[]> map = sync(redisKey);
        map.delete();
    }

    /**
     * Drops the queued and cached Redis entries of the given bucket locations (e.g. {@code public/}, {@code
     * platform/}), with no blob I/O: every member of the sync queue whose blob key starts with one of them, and
     * every cache map whose key is {@code <type>:<storage path>} for a {@link ResourceTypes} name and a storage
     * path under one of them. It does not take per-key locks, so a writer holding only a per-resource lock can
     * race with it; callers that need exclusion take the bucket locks around this call. Used by a system import
     * after promoting its blobs, so that neither a cached copy nor a queued write masks the import.
     */
    public void invalidate(Collection<String> bucketLocations) {
        removeQueuedEntries(bucketLocations);
        removeCachedEntries(bucketLocations);
    }

    /**
     * Like {@link #invalidate} for the given blobs only, e.g. {@code platform/roles/r1}: drops their queued and cached
     * entries and leaves every other resource of their buckets alone. It scans nothing, so its cost grows with the
     * number of blobs, in batches of {@link #BATCH_SIZE}. A blob that is not a resource has no entry and is skipped.
     */
    public void invalidateBlobs(Collection<String> blobPaths) {
        RScoredSortedSet<String> queue = redis.getScoredSortedSet(resourceQueue, StringCodec.INSTANCE);
        RKeys keys = redis.getKeys();
        List<String> batch = new ArrayList<>(BATCH_SIZE);
        for (String blobPath : blobPaths) {
            String redisKey = redisKeyOfBlob(blobPath);
            if (redisKey != null) {
                batch.add(redisKey);
            }
            if (batch.size() == BATCH_SIZE) {
                removeEntries(queue, keys, batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            removeEntries(queue, keys, batch);
        }
    }

    private static void removeEntries(RScoredSortedSet<String> queue, RKeys keys, List<String> redisKeys) {
        queue.removeAll(redisKeys);
        keys.delete(redisKeys.toArray(new String[0]));
    }

    private String redisKeyOfBlob(String blobPath) {
        String[] segments = blobPath.split(ResourceDescriptor.PATH_SEPARATOR, 3);
        if (segments.length < 3) {
            return null;
        }
        try {
            return RedisUtil.redisKey(ResourceTypes.of(segments[1]), blobPath, prefix);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void removeQueuedEntries(Collection<String> bucketLocations) {
        RScoredSortedSet<String> set = redis.getScoredSortedSet(resourceQueue, StringCodec.INSTANCE);
        List<String> toRemove = new ArrayList<>();
        for (String redisKey : set) {
            if (startsWithAny(blobKeyFromRedisKey(redisKey), bucketLocations)) {
                toRemove.add(redisKey);
            }
        }
        if (!toRemove.isEmpty()) {
            set.removeAll(toRemove);
        }
    }

    private void removeCachedEntries(Collection<String> bucketLocations) {
        RKeys keys = redis.getKeys();
        for (String bucketLocation : bucketLocations) {
            String pattern = "*:" + BlobStorageUtil.toStoragePath(prefix, bucketLocation) + "*";
            List<String> batch = new ArrayList<>(BATCH_SIZE);
            for (String key : keys.getKeysByPattern(pattern)) {
                if (isResourceTypeKey(key)) {
                    batch.add(key);
                    if (batch.size() == BATCH_SIZE) {
                        keys.delete(batch.toArray(new String[0]));
                        batch.clear();
                    }
                }
            }
            if (!batch.isEmpty()) {
                keys.delete(batch.toArray(new String[0]));
            }
        }
    }

    /**
     * {@code getKeysByPattern} also matches lock keys ({@code lock:public/...}) and other non-cache keys sharing
     * the prefix, so every match is filtered on the client: only a key whose text before the first {@code ':'} is
     * a {@link ResourceTypes} name is deleted.
     */
    private static boolean isResourceTypeKey(String redisKey) {
        try {
            ResourceTypes.valueOf(RedisUtil.getResourceType(redisKey));
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean startsWithAny(String value, Collection<String> prefixes) {
        for (String candidate : prefixes) {
            if (value.startsWith(candidate)) {
                return true;
            }
        }
        return false;
    }

    public String getEtag(ResourceDescriptor descriptor) {
        ResourceItemMetadata metadata = getResourceMetadata(descriptor);
        if (metadata == null) {
            return null;
        }

        return metadata.getEtag();
    }

    private static Map<String, String> toUserMetadata(String etag, Long createdAt, Long updatedAt, String resourceType, String author) {
        Map<String, String> metadata = new HashMap<>();
        if (etag != null) {
            metadata.put(ETAG_ATTRIBUTE, etag);
        }
        if (createdAt != null) {
            metadata.put(CREATED_AT_ATTRIBUTE, Long.toString(createdAt));
        }
        if (updatedAt != null) {
            metadata.put(UPDATED_AT_ATTRIBUTE, Long.toString(updatedAt));
        }
        if (resourceType != null) {
            metadata.put(RESOURCE_TYPE_ATTRIBUTE, resourceType);
        }
        if (author != null) {
            metadata.put(AUTHOR_ATTRIBUTE, encode(author));
        }

        return metadata;
    }

    @VisibleForTesting
    static String encode(String val) {
        if (StringUtils.isEmpty(val)) {
            return val;
        }
        return BASE58_PREFIX + Base58.encode(val.getBytes(StandardCharsets.UTF_8));
    }

    @VisibleForTesting
    static String decode(String val) {
        if (val == null || !val.startsWith(BASE58_PREFIX)) {
            return val;
        }
        String decoded = val.substring(BASE58_PREFIX.length());
        return new String(Base58.decode(decoded), StandardCharsets.UTF_8);
    }

    private static String extractEtag(BlobMetadata meta) {
        Map<String, String> attributes = meta.getUserMetadata();
        String etag = attributes.get(ETAG_ATTRIBUTE);
        return (etag == null) ? meta.getETag() : EtagHeader.quoteIfNeeded(etag);
    }

    private Duration getExpiration(String redisKey) {
        String resourceType = RedisUtil.getResourceType(redisKey);
        Long ttl = resourceTypeExpiration.get(resourceType);
        if (ttl == null) {
            return cacheExpiration;
        }
        return Duration.ofMillis(ttl);
    }

    @Builder(toBuilder = true)
    @VisibleForTesting
    record Result(
            byte[] body,
            String etag,
            Long createdAt,
            Long updatedAt,
            String contentType,
            Boolean compress,
            Long contentLength,
            String resourceType,
            String author,
            boolean synced) {
        public static final Result DELETED_SYNCED = new Result(null, null, null, null,
                null, null, null, null, null, true);
        public static final Result DELETED_NOT_SYNCED = new Result(null, null, null, null,
                null, null, null, null, null, false);

        public boolean exists() {
            return body != null;
        }

        public Result toStub() {
            return new Result(ArrayUtils.EMPTY_BYTE_ARRAY, etag, createdAt, updatedAt, contentType, false,
                    0L, resourceType, author, synced);
        }
    }

    public record ResourceStream(InputStream inputStream, String etag, String contentType, long contentLength)
            implements Closeable {

        @Override
        public void close() throws IOException {
            inputStream.close();
        }

        @Nullable
        private static ResourceStream fromResult(Result item, EtagHeader etagHeader) {
            if (!item.exists()) {
                return null;
            }

            etagHeader.validate(item.etag);

            return new ResourceStream(
                    new ByteArrayInputStream(item.body),
                    item.etag(),
                    item.contentType(),
                    item.body.length);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    public static class Settings {
        /**
         * Max allowed size in bytes for a resource.
         */
        private int maxSize;
        /**
         * Max size in bytes to cache resource in Redis.
         */
        private int maxSizeToCache;
        /**
         * Period in milliseconds, how frequently check for resources to sync.
         */
        private int syncPeriod;
        /**
         * Delay in milliseconds for a resource to be written back in object storage after last modification. A rate-limit
         * counter is written back this long after its first change since the last write-back, so one written more
         * often still reaches object storage.
         */
        private long syncDelay;
        /**
         * How many resources to sync in one go.
         */
        private int syncBatch;
        /**
         * Default expiration in milliseconds for synced resources in Redis.
         */
        private long cacheExpiration;
        /**
         * Compress resources with gzip if their size in bytes more or equal to this value.
         */
        private int compressionMinSize;
        /**
         * Expiration in milliseconds per resource type.
         */
        private Map<String, Long> resourceTypesExpiration = new HashMap<>();

        public Settings() {
        }

        public Settings(int maxSize, int maxSizeToCache, int syncPeriod, long syncDelay, int syncBatch, long cacheExpiration, int compressionMinSize) {
            this.maxSize = maxSize;
            this.maxSizeToCache = maxSizeToCache;
            this.syncPeriod = syncPeriod;
            this.syncDelay = syncDelay;
            this.syncBatch = syncBatch;
            this.cacheExpiration = cacheExpiration;
            this.compressionMinSize = compressionMinSize;
        }
    }
}