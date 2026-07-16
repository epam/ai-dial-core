package com.epam.aidial.core.server.security;

import com.epam.aidial.core.config.IpAddressRanges;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.server.config.FileConfigStore;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.util.RedisUtil;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import static com.epam.aidial.core.server.security.ApiKeyGenerator.generateKey;
import static com.epam.aidial.core.storage.resource.ResourceDescriptor.PATH_SEPARATOR;

/**
 * The store keeps per request and project API key data.
 * <p>
 *     Per request key is assigned during the request and terminated in the end of the request.
 *     Project keys are hosted by external secure storage and might be periodically updated by {@link FileConfigStore}.
 * </p>
 */
@Slf4j
public class ApiKeyStore {

    public static final String API_KEY_DATA_BUCKET = "api_key_data";
    public static final String API_KEY_DATA_LOCATION = API_KEY_DATA_BUCKET + PATH_SEPARATOR;

    private final AsyncTaskExecutor taskExecutor;
    private final RedissonClient redis;
    private final String prefix;

    private final Duration ttl;

    /**
     * Serializes per-entry point-writes against the full rebuild's swap. The merged store adopts this
     * same lock as its {@code rebuildLock} (via {@link #getMutationLock()}), so a point-write either
     * completes before rebuild's blob scan (visible to it) or blocks until after the swap and applies
     * to the new map — surviving either way. Reentrant so rebuild's own thread can re-enter mutators.
     */
    private final ReentrantLock mutationLock = new ReentrantLock();

    public ApiKeyStore(AsyncTaskExecutor taskExecutor, RedissonClient redis, String prefix, JsonObject settings) {
        this.taskExecutor = taskExecutor;
        this.redis = redis;
        this.prefix = prefix;
        this.ttl = Duration.ofSeconds(settings.getInteger("ttl", 1800));
    }

    /**
     * Project API keys, keyed by secret value for O(1) auth lookup (OQ-12). The reference is rebuilt and
     * atomically swapped on full reload; per-entry mutations serialize via {@link #mutationLock}.
     */
    private volatile ConcurrentHashMap<String, ApiKeyData> keys = new ConcurrentHashMap<>();

    /**
     * Assigns a new generated per request key to the {@link ApiKeyData}.
     * <p>
     *     Note. The method is blocking and shouldn't be run in the event loop thread.
     * </p>
     */
    public void assignPerRequestApiKey(ApiKeyData data) {
        assignPerRequestApiKey(data, ttl);
    }

    public void assignPerRequestApiKey(ApiKeyData data, Duration customTtl) {
        String perRequestKey = generateKey();
        data.setPerRequestKey(perRequestKey);
        String json = ProxyUtil.convertToString(data);
        String redisKey = toRedisKey(perRequestKey);
        RBucket<String> bucket = redis.getBucket(redisKey, StringCodec.INSTANCE);
        if (!bucket.setIfAbsent(json)) {
            throw new IllegalStateException(String.format("API key %s already exists in Redis storage", perRequestKey));
        }
        bucket.expire(customTtl);
    }

    public void updatePerRequestApiKey(String key, Function<String, String> fn) {
        if (key == null) {
            throw new IllegalArgumentException("Per request API key is undefined");
        }
        String redisKey = toRedisKey(key);
        RBucket<String> bucket = redis.getBucket(redisKey, StringCodec.INSTANCE);
        // lock free
        while (true) {
            String oldJson = bucket.get();
            if (oldJson == null) {
                throw new IllegalArgumentException("Per request key is not found: " + key);
            }

            String newJson = fn.apply(oldJson);

            if (Objects.equals(oldJson, newJson) || bucket.compareAndSet(oldJson, newJson)) {
                break;
            }
        }
        bucket.expire(ttl);
    }

    /**
     * Returns API key data for the given key.
     *
     * @param key API key could be either project or per request key.
     * @return the future of data associated with the given key.
     */
    public Future<ApiKeyData> getApiKeyData(String key, String clientIpAddress) {
        ApiKeyData apiKeyData = keys.get(key);
        if (apiKeyData != null) {
            return validateIpAddressRange(apiKeyData, clientIpAddress);
        }
        String redisKey = toRedisKey(key);
        return taskExecutor.submit(() -> {
            RBucket<String> bucket = redis.getBucket(redisKey, StringCodec.INSTANCE);
            String json = bucket.get();
            return ProxyUtil.convertToObject(json, ApiKeyData.class);
        }).compose(result -> {
            if (result == null) {
                return Future.failedFuture(new HttpException(HttpStatus.UNAUTHORIZED, "Unknown api key"));
            }
            return Future.succeededFuture(result);
        });
    }

    private static Future<ApiKeyData> validateIpAddressRange(ApiKeyData apiKeyData, String clientIpAddress) {
        log.debug("Api key {} is accessed by client IP {}", apiKeyData.getOriginalKey().getProject(), clientIpAddress);
        IpAddressRanges ranges = apiKeyData.getOriginalKey().getAllowedIpAddressRanges();
        if (ranges == null || ranges.isAddressInRange(clientIpAddress)) {
            return Future.succeededFuture(apiKeyData);
        }
        return Future.failedFuture(new HttpException(HttpStatus.FORBIDDEN,
                String.format("Access is forbidden from IP address: %s", clientIpAddress)));
    }

    /**
     * Invalidates per request API key.
     * If api key belongs to a project the operation will not have affect.
     *
     * @param apiKeyData associated with the key to be invalidated.
     * @return the future of the invalidation result: <code>true</code> means the key is successfully invalidated.
     */
    public Future<Boolean> invalidatePerRequestApiKey(ApiKeyData apiKeyData) {
        String apiKey = apiKeyData.getPerRequestKey();
        if (apiKey != null) {
            String redisKey = toRedisKey(apiKey);
            return taskExecutor.submit(() -> {
                RBucket<String> bucket = redis.getBucket(redisKey);
                return bucket.delete();
            });
        }
        return Future.succeededFuture(true);
    }

    /**
     * Rebuilds the project-key store from two explicitly-classified partitions and atomically swaps
     * the {@code keys} reference once (single-atomic-swap invariant, per OQ-12).
     * <p>
     *     Note. The method is blocking and shouldn't be run in the event loop thread.
     * </p>
     *
     * @param fileKeysBySecret    file-sourced keys; the map key is the plaintext secret (pre-existing
     *                            file convention). The secret may be any string including Base64 with
     *                            '/', so no map-key shape is inferred — a blank {@link Key#getKey()} is
     *                            back-filled from the map key.
     * @param apiKeysByCanonicalId API-sourced keys; the map key is the canonical id, never the secret.
     *                            The secret must already be on {@link Key#getKey()} (post-decrypt);
     *                            blank entries are skipped (fail closed) — the canonical id is never
     *                            used as an auth bearer.
     */
    public void addProjectKeys(Map<String, Key> fileKeysBySecret, Map<String, Key> apiKeysByCanonicalId) {
        mutationLock.lock();
        try {
            addProjectKeysLocked(fileKeysBySecret, apiKeysByCanonicalId);
        } finally {
            mutationLock.unlock();
        }
    }

    private void addProjectKeysLocked(Map<String, Key> fileKeysBySecret, Map<String, Key> apiKeysByCanonicalId) {
        ConcurrentHashMap<String, ApiKeyData> apiKeyDataMap = new ConcurrentHashMap<>();
        for (Map.Entry<String, Key> entry : fileKeysBySecret.entrySet()) {
            String mapKey = entry.getKey();
            Key value = entry.getValue();
            validateProjectKey(value);
            if (StringUtils.isBlank(value.getKey())) {
                value.setKey(mapKey);
            }
            ApiKeyData apiKeyData = new ApiKeyData();
            apiKeyData.setOriginalKey(value);
            apiKeyDataMap.put(value.getKey(), apiKeyData);
            log.debug("Loading {}", value);
        }
        for (Map.Entry<String, Key> entry : apiKeysByCanonicalId.entrySet()) {
            Key value = entry.getValue();
            validateProjectKey(value);
            if (StringUtils.isBlank(value.getKey())) {
                log.warn("Skipping API-sourced project key '{}': Key.key is blank after decrypt; "
                        + "refusing to use canonical id as auth bearer", entry.getKey());
                continue;
            }
            ApiKeyData apiKeyData = new ApiKeyData();
            apiKeyData.setOriginalKey(value);
            apiKeyDataMap.put(value.getKey(), apiKeyData);
            log.debug("Loading {}", value);
        }
        keys = apiKeyDataMap;
    }

    /**
     * Shared mutation lock adopted by {@code MergedConfigStore} as its {@code rebuildLock} so
     * per-entry point-writes serialize against the entire rebuild (scan → build → swap). See
     * {@link #mutationLock}.
     */
    public ReentrantLock getMutationLock() {
        return mutationLock;
    }

    /**
     * Fast-path partial mutator used by API-managed key writes (Phase 2 keys controller).
     * Serialized against the full rebuild via {@link #mutationLock} so the put is never discarded
     * by a concurrent {@code addProjectKeys} map swap.
     */
    public void addOrUpdateKey(String secret, ApiKeyData data) {
        mutationLock.lock();
        try {
            keys.put(secret, data);
        } finally {
            mutationLock.unlock();
        }
    }

    /**
     * Fast-path partial mutator used by API-managed key deletes (Phase 2 keys controller).
     * Must be called after the corresponding blob {@code ResourceService.delete} returns
     * (per the keys-controller {@code DELETE} ordering invariant). Serialized against the full
     * rebuild via {@link #mutationLock}.
     */
    public void removeKey(String secret) {
        mutationLock.lock();
        try {
            keys.remove(secret);
        } finally {
            mutationLock.unlock();
        }
    }

    private void validateProjectKey(Key key) {
        if (StringUtils.isEmpty(key.getProject())) {
            throw new IllegalArgumentException("Project key is undefined");
        }
        if (StringUtils.isEmpty(key.getRole()) && (key.getRoles() == null || key.getRoles().isEmpty())) {
            throw new IllegalArgumentException("Invalid key: at least one role must be assigned to the key " + key.getProject());
        }
    }

    private String toRedisKey(String apiKey) {
        ResourceDescriptor resource = ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.API_KEY_DATA, API_KEY_DATA_BUCKET, API_KEY_DATA_LOCATION, apiKey);
        return RedisUtil.redisKey(resource, prefix);
    }

}
