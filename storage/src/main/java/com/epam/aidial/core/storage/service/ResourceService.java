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
import com.epam.aidial.core.storage.util.Base58;
import com.epam.aidial.core.storage.util.Compression;
import com.epam.aidial.core.storage.util.EtagBuilder;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.epam.aidial.core.storage.util.RedisUtil;
import com.epam.aidial.core.storage.util.UrlUtil;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Sets;
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
import org.redisson.api.RMap;
import org.redisson.api.RMapAsync;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
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
        this.syncTimer = timerService.scheduleWithFixedDelay(settings.syncPeriod, settings.syncPeriod, this::sync);
        this.tempCleanupTimer = timerService.scheduleWithFixedDelay(TEMP_CLEANUP_PERIOD, TEMP_CLEANUP_PERIOD, this::cleanupTempFolder);
    }

    @SneakyThrows
    @Override
    public void close() {
        syncTimer.close();
        tempCleanupTimer.close();
    }

    public ResourceTopic.Subscription subscribeResources(Collection<ResourceDescriptor> resources,
                                                         Consumer<ResourceEvent> subscriber) {
        return topic.subscribe(resources, subscriber);
    }

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

    public ResourceFolderMetadata getFolderMetadata(ResourceDescriptor descriptor, String token, int limit, boolean recursive) {
        String blobKey = blobKey(descriptor);
        PageSet<? extends StorageMetadata> set = blobStore.list(blobKey, token, limit, recursive);

        if (set.isEmpty() && !descriptor.isRootFolder()) {
            return null;
        }

        List<MetadataBase> resources = set.stream()
                // blob store never returns folder however local FS provider may return
                .filter(meta -> !recursive || meta.getType() == StorageType.BLOB)
                .map(meta -> storageToResourceMetadata(meta, descriptor)).toList();
        // marker can be a percent encoded or decoded string
        // we should encode the marker any way to get back the original string from a query parameter
        String nextMarker = UrlUtil.encodePath(set.getNextMarker());
        return new ResourceFolderMetadata(descriptor, resources, nextMarker);
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
    @SneakyThrows
    public void load(List<ResourceItemMetadata> items, List<Pair<ResourceItemMetadata, String>> result) {
        final int size = items.size();
        int batches = size / BATCH_SIZE;
        int rem = size % BATCH_SIZE;
        int cur = 0;

        List<Future<List<Pair<ResourceItemMetadata, String>>>> futures = new ArrayList<>();
        for (int i = 0; i < batches; i++) {
            int end = cur + BATCH_SIZE;
            int start = cur;
            Future<List<Pair<ResourceItemMetadata, String>>> future = VIRTUAL_THREAD_PER_TASK_EXECUTOR
                    .submit(() -> runReadBatch(start, end, items));
            futures.add(future);
            cur = end;
        }
        if (rem > 0) {
            int end = cur + rem;
            int start = cur;
            Future<List<Pair<ResourceItemMetadata, String>>> future = VIRTUAL_THREAD_PER_TASK_EXECUTOR
                    .submit(() -> runReadBatch(start, end, items));
            futures.add(future);
        }

        for (Future<List<Pair<ResourceItemMetadata, String>>> future : futures) {
            result.addAll(future.get());
        }
    }

    @SneakyThrows
    private List<Pair<ResourceItemMetadata, String>> runReadBatch(int start, int end, List<ResourceItemMetadata> items) {
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
            map.getAllAsync(REDIS_FIELDS);
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
            } else {
                Pair<ResourceItemMetadata, String> pair = Pair.of(toResourceItemMetadata(metadata.getDescriptor(), redisResult),
                        new String(redisResult.body, StandardCharsets.UTF_8));
                result.add(pair);
            }
        }
        log.debug("Number of missing resources in Redis cache: {}", missed.size());
        List<Future<Pair<ResourceItemMetadata, String>>> futures = new ArrayList<>();
        for (ResourceItemMetadata metadata : missed) {
            Future<Pair<ResourceItemMetadata, String>> future = VIRTUAL_THREAD_PER_TASK_EXECUTOR
                    .submit(() -> getResourceWithMetadata(metadata.getDescriptor(), EtagHeader.ANY, false));
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
        return (FileMetadata) new FileMetadata(resource, result.contentLength(), result.contentType())
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

        etagHeader.validate(result.etag);

        if (result.exists()) {
            return Pair.of(toResourceItemMetadata(descriptor, result), result.body);
        }

        return null;
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
            if (blob == null) {
                redisPut(key, Result.DELETED_SYNCED);
                return null;
            }

            Payload payload = blob.getPayload();
            BlobMetadata metadata = blob.getMetadata();
            String etag = extractEtag(metadata);
            String contentType = metadata.getContentMetadata().getContentType();
            Long length = metadata.getContentMetadata().getContentLength();

            if (length <= maxSizeToCache) {
                result = blobToResult(blob, metadata);
                redisPut(key, result);
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
        log.debug("Cleaning up temp folder");
        try {
            LocalDateTime now = LocalDateTime.now();
            String token = null;
            exit: do {
                PageSet<? extends StorageMetadata> set = blobStore.list(TEMP_FOLDER, token, PAGE_SIZE, true);
                for (StorageMetadata meta : set) {
                    if (meta.getType() != StorageType.BLOB) {
                        continue;
                    }
                    String path = meta.getName();
                    if (isExpiredTempFile(path, now)) {
                        try {
                            blobStore.delete(path);
                        } catch (Throwable e) {
                            log.warn("Failed to delete expired temp file: {}", path, e);
                        }
                    } else {
                        break exit;
                    }

                }
                token = set.getNextMarker();
            } while (token != null);
        } catch (Throwable e) {
            log.warn("Failed to clean up temp folder", e);
        }

        return null;
    }

    private static boolean isExpiredTempFile(String path, LocalDateTime now) {
        String prefix = TEMP_FOLDER + ResourceDescriptor.PATH_SEPARATOR;
        if (!path.startsWith(prefix)) {
            throw invalidTempPathException(path);
        }

        int folderStart = prefix.length();
        int folderEnd = path.indexOf(ResourceDescriptor.PATH_SEPARATOR, folderStart);
        if (folderEnd < 0) {
            throw invalidTempPathException(path);
        }

        String folder = path.substring(folderStart, folderEnd);
        LocalDateTime uploadTs;
        try {
            uploadTs = LocalDateTime.parse(folder, TEMP_FOLDER_FORMATTER);
        } catch (DateTimeParseException e) {
            log.warn("Unexpected folder name in temp folder: {}", folder);
            throw invalidTempPathException(path);
        }

        return uploadTs.plus(TEMP_FILE_TTL).isBefore(now);
    }

    private static IllegalArgumentException invalidTempPathException(String path) {
        return new IllegalArgumentException("Invalid path to temp file: " + path);
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

    public boolean deleteResource(ResourceDescriptor descriptor, EtagHeader etag) {
        return deleteResource(descriptor, etag, true);
    }

    public boolean deleteResource(ResourceDescriptor descriptor, EtagHeader etag, boolean lock) {
        return deleteResource(descriptor, etag, lock, null);
    }

    public boolean deleteResource(ResourceDescriptor descriptor, EtagHeader etag, boolean lock,
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
                Duration expiration = getExpiration(redisKey);
                map.expire(expiration);
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
        map.putAll(fields);

        if (result.synced) { // cleanup because it is already synced
            Duration expiration = getExpiration(key);
            map.expire(expiration);
            set.remove(key);
        }
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

    @Builder
    private record Result(
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
         * Delay in milliseconds for a resource to be written back in object storage after last modification.
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