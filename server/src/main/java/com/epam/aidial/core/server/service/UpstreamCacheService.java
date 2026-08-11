package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Features;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.data.cache.CacheBreakpointContext;
import com.epam.aidial.core.server.data.cache.CachePolicy;
import com.epam.aidial.core.server.data.cache.CachedUpstreamEntry;
import com.epam.aidial.core.server.function.request.CacheKey;
import com.epam.aidial.core.server.function.request.RequestObject;
import com.epam.aidial.core.storage.blobstore.BlobStorageUtil;
import com.epam.aidial.core.storage.service.LockService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.BatchResult;
import org.redisson.api.RBatch;
import org.redisson.api.RMap;
import org.redisson.api.RMapAsync;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.StringCodec;
import org.redisson.codec.CompositeCodec;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

@Slf4j
public class UpstreamCacheService {

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

    private static final String UPSTREAM_ENDPOINT_FIELD = "upstream_endpoint";
    private static final String UPSTREAM_ID_FIELD = "upstream_id";
    private static final String PREFIX_PATH_FIELD = "prefix_path";
    private static final String EXTRA_METADATA_FIELD = "extra_metadata";


    private static final Set<String> ALL_FIELDS = Set.of(UPSTREAM_ENDPOINT_FIELD, UPSTREAM_ID_FIELD, PREFIX_PATH_FIELD, EXTRA_METADATA_FIELD);

    private static final int BATCH_SIZE = 64;

    private static final Codec REDIS_MAP_CODEC = new CompositeCodec(
            StringCodec.INSTANCE,
            StringCodec.INSTANCE);

    private final RedissonClient redisClient;

    private final LockService lockService;

    private final LongSupplier clock;

    private final String prefix;

    public UpstreamCacheService(RedissonClient redisClient, LockService lockService, LongSupplier clock, String prefix) {
        this.redisClient = redisClient;
        this.lockService = lockService;
        this.clock = clock;
        this.prefix = prefix;
    }

    public CacheBreakpointContext buildCacheBreakpointContext(RequestObject request, CachePolicy policy, Model model,
                                                                InterfaceType interfaceType) {
        boolean autoCaching = isAutoCaching(model);
        List<String> fieldsOrder = interfaceType.getFieldsHashingOrder();
        List<String> breakpoints = new ArrayList<>();
        Map<String, String> prefixToHash = new HashMap<>();
        for (CacheKey cacheKey : request.buildCacheKeys(fieldsOrder)) {
            String path = cacheKey.path();
            if (autoCaching || cacheKey.hasBreakpoint()) {
                breakpoints.add(path);
            }
            prefixToHash.put(path, cacheKey.hash());
        }
        return new CacheBreakpointContext(breakpoints, prefixToHash, policy);
    }

    public CachedUpstreamEntry getCacheEntry(CacheBreakpointContext cacheBreakpointContext, Model model) {
        List<String> breakpoints = cacheBreakpointContext.breakpoints();
        if (breakpoints.isEmpty()) {
            // model doesn't support caching
            return null;
        }
        Map<String, String> prefixToHash = cacheBreakpointContext.prefixToHash();
        for (int i = breakpoints.size() - 1; i >= 0;) {
            RBatch batch = redisClient.createBatch();
            int count = 0;
            for (; i >= 0 && count < BATCH_SIZE; count++, i--) {
                String prefixPath = breakpoints.get(i);
                String hash = prefixToHash.get(prefixPath);
                String key = getEntryKey(model.getName(), hash);
                RMapAsync<String, String> rmap = batch.getMap(key, REDIS_MAP_CODEC);
                rmap.isExistsAsync();
            }
            BatchResult<?> result = batch.execute();
            List<?> responses = result.getResponses();
            for (int j = 0; j < responses.size(); j++) {
                Boolean exists = (Boolean) responses.get(j);
                if (exists) {
                    int index = i + count - j;
                    String prefixPath = breakpoints.get(index);
                    String hash = prefixToHash.get(prefixPath);
                    String key = getEntryKey(model.getName(), hash);
                    RMap<String, String> map = redisClient.getMap(key, REDIS_MAP_CODEC);
                    Map<String, String> fields = map.getAll(ALL_FIELDS);
                    if (!fields.isEmpty()) {
                        return new CachedUpstreamEntry(
                                fields.get(UPSTREAM_ENDPOINT_FIELD),
                                fields.get(UPSTREAM_ID_FIELD),
                                fields.get(PREFIX_PATH_FIELD),
                                fields.get(EXTRA_METADATA_FIELD));
                    }
                }
            }
        }
        // take the last breakpoint
        String prefixPath = breakpoints.get(breakpoints.size() - 1);
        return new CachedUpstreamEntry(null, null, prefixPath, null);
    }

    public void updateEntry(String hash, CachedUpstreamEntry entry, Model model, String expireAtStr) {
        String key = getEntryKey(model.getName(), hash);
        Map<String, String> fields = new HashMap<>();
        fields.put(UPSTREAM_ENDPOINT_FIELD, entry.endpoint());
        fields.put(PREFIX_PATH_FIELD, entry.prefixPath());
        if (entry.id() != null) {
            fields.put(UPSTREAM_ID_FIELD, entry.id());
        }
        if (entry.extraMetadata() != null) {
            fields.put(EXTRA_METADATA_FIELD, entry.extraMetadata());
        }
        Instant expireAt = expireAt(expireAtStr);

        try (var ignore = lockService.lock(key)) {

            RMap<String, String> map = redisClient.getMap(key, REDIS_MAP_CODEC);

            map.putAll(fields);
            long ttl = map.remainTimeToLive();

            if (expireAt == null && ttl == -1) {
                expireAt = Instant.ofEpochMilli(clock.getAsLong()).plus(DEFAULT_TTL);
            }

            if (expireAt != null) {
                map.expire(expireAt);
            }
        }
    }

    private boolean isAutoCaching(Model model) {
        Features features = model.getFeatures();
        if (features == null) {
            return false;
        }
        Boolean autoCaching = features.getAutoCachingSupported();
        return autoCaching != null && autoCaching;
    }

    private static Instant expireAt(String val) {
        if (val == null) {
            return null;
        }
        try {
            return Instant.ofEpochSecond(Long.parseLong(val));
        } catch (NumberFormatException e) {
            log.warn("Invalid expireAt datetime format: " + val);
            return null;
        }
    }

    private String getEntryKey(String modelName, String hash) {
        return "upstream_cache:" + BlobStorageUtil.toStoragePath(prefix, BlobStorageUtil.toStoragePath(modelName, hash));
    }
}
