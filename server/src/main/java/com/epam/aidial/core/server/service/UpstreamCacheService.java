package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.data.cache.CacheBreakpointContext;
import com.epam.aidial.core.server.data.cache.CachePolicy;
import com.epam.aidial.core.server.data.cache.CachedUpstreamEntry;
import com.epam.aidial.core.storage.blobstore.BlobStorageUtil;
import com.epam.aidial.core.storage.service.LockService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.BatchResult;
import org.redisson.api.RBatch;
import org.redisson.api.RMap;
import org.redisson.api.RMapAsync;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.StringCodec;
import org.redisson.codec.CompositeCodec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class UpstreamCacheService {

    private static final Pattern PREFIX_PATH = Pattern.compile("^prefix\\.body\\.(?<nodeName>(tools|messages))$");
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

    private static final String CUSTOM_FIELDS_NODE = "custom_fields";
    private static final String CACHE_BREAKPOINT_NODE = "cache_breakpoint";
    private static final String UPSTREAM_ENDPOINT_FIELD = "upstream_endpoint";
    private static final String PREFIX_PATH_FIELD = "prefix_path";


    private static final Set<String> ALL_FIELDS = Set.of(UPSTREAM_ENDPOINT_FIELD, PREFIX_PATH_FIELD);

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

    public CacheBreakpointContext buildCacheBreakpointContext(ObjectNode body, CachePolicy policy, Model model) {
        boolean autoCaching = policy == CachePolicy.AUTO_CACHING;
        List<String> fieldsOrder = model.getFieldsHashingOrder();
        MessageDigest messageDigest = createMessageDigest();
        List<String> breakpoints = new ArrayList<>();
        Map<String, String> prefixToHash = new HashMap<>();
        for (String field : fieldsOrder) {
            Matcher matcher = PREFIX_PATH.matcher(field);
            if (!matcher.matches()) {
                log.warn("Unsupported prefix path: {}", field);
                continue;
            }
            String nodeName = matcher.group("nodeName");
            JsonNode node = body.get(nodeName);
            if (node == null || !node.isArray()) {
                // embedding request is not supported yet
                continue;
            }
            ArrayNode arrayNode = (ArrayNode) node;
            for (int index = 0; index < arrayNode.size(); index++) {
                ObjectNode objectNode = (ObjectNode) arrayNode.get(index);
                for (Iterator<Map.Entry<String, JsonNode>> it = objectNode.fields(); it.hasNext(); ) {
                    Map.Entry<String, JsonNode> entry = it.next();
                    if (entry.getKey().equals(CUSTOM_FIELDS_NODE)) {
                        continue;
                    }
                    if (entry.getKey().equals("custom_content")) {
                        // include attachments only
                        if (entry.getValue().has("attachments")) {
                            messageDigest.update(entry.getValue().get("attachments").toString().getBytes(StandardCharsets.UTF_8));
                        }
                    } else {
                        messageDigest.update(entry.getValue().toString().getBytes(StandardCharsets.UTF_8));
                    }
                }
                String hash = toString(messageDigest.digest());
                String prefix = field + "[" + index + "]";
                if (autoCaching || (objectNode.has(CUSTOM_FIELDS_NODE) && objectNode.get(CUSTOM_FIELDS_NODE).has(CACHE_BREAKPOINT_NODE))) {
                    breakpoints.add(prefix);
                }
                prefixToHash.put(prefix, hash);
            }

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
                        return new CachedUpstreamEntry(fields.get(UPSTREAM_ENDPOINT_FIELD), fields.get(PREFIX_PATH_FIELD));
                    }
                }
            }
        }
        // take the last breakpoint
        String prefixPath = breakpoints.get(breakpoints.size() - 1);
        return new CachedUpstreamEntry(null, prefixPath);
    }

    public void updateEntry(String hash, CachedUpstreamEntry entry, Model model, String expireAtStr) {
        String key = getEntryKey(model.getName(), hash);
        Map<String, String> fields = new HashMap<>();
        fields.put(UPSTREAM_ENDPOINT_FIELD, entry.endpoint());
        fields.put(PREFIX_PATH_FIELD, entry.prefixPath());
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

    private static Instant expireAt(String val) {
        if (val == null) {
            return null;
        }
        try {
            return Instant.ofEpochSecond(Long.parseLong(val));
        } catch (NumberFormatException e) {
            log.error("Invalid expireAt datetime format: " + val);
            return null;
        }
    }

    private static String toString(byte[] digest) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : digest) {
            hexString.append(String.format("%02x", b));
        }
        return hexString.toString();
    }

    private String getEntryKey(String modelName, String hash) {
        return "upstream_cache:" + BlobStorageUtil.toStoragePath(prefix, BlobStorageUtil.toStoragePath(modelName, hash));
    }

    @SneakyThrows
    private static MessageDigest createMessageDigest() {
        return MessageDigest.getInstance("SHA-1");
    }

}
