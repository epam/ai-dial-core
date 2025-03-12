package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ModelType;
import com.epam.aidial.core.server.data.cache.CachePolicy;
import com.epam.aidial.core.server.data.cache.CachedUpstreamEntry;
import com.epam.aidial.core.storage.service.LockService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class UpstreamCacheService {

    private static final Pattern PREFIX_PATH = Pattern.compile("^prefix\\.body\\.(?<nodeName>(tools|messages))$");
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

    private static final String CUSTOM_FIELDS_NODE = "custom_fields";
    private static final String CACHE_BREAKPOINT_NODE = "cache_breakpoint";
    private static final String UPSTREAM_ENDPOINT_ATTR = "upstream_endpoint";
    private static final String PREFIX_PATH_ATTR = "prefix_path";

    private final RedissonClient redisClient;

    private final LockService lockService;

    public UpstreamCacheService(RedissonClient redisClient, LockService lockService) {
        this.redisClient = redisClient;
        this.lockService = lockService;
    }

    public CachedUpstreamEntry getCacheEntry(ObjectNode body, CachePolicy cachePolicy, Model model) {
        List<String> fieldsOrder = model.getFieldsHashingOrder();
        MessageDigest messageDigest = createMessageDigest();
        List<Breakpoint> breakpoints = new ArrayList<>();
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
                if (cachePolicy == CachePolicy.AUTO_CACHING
                        || (objectNode.has(CUSTOM_FIELDS_NODE) && objectNode.get(CUSTOM_FIELDS_NODE).has(CACHE_BREAKPOINT_NODE))) {
                    breakpoints.add(new Breakpoint(prefix, hash));
                }
                prefixToHash.put(prefix, hash);
            }

        }
        if (breakpoints.isEmpty()) {
            // embedding request
            CachedUpstreamEntry entry = new CachedUpstreamEntry();
            entry.setPolicy(cachePolicy);
            entry.setPrefixToHash(prefixToHash);
            return entry;
        }
        for (int i = breakpoints.size() - 1; i >= 0; i--) {
            Breakpoint breakpoint = breakpoints.get(i);
            String key = getEntryKey(model.getName(), breakpoint.hash);
            RMap<String, String> map = redisClient.getMap(key);
            if (!map.isEmpty()) {
                CachedUpstreamEntry entry = toEntry(map);
                entry.setHash(breakpoint.hash);
                entry.setPolicy(cachePolicy);
                entry.setPrefixToHash(prefixToHash);
                return entry;
            }
        }
        // take the last breakpoint
        Breakpoint last = breakpoints.get(breakpoints.size() - 1);
        CachedUpstreamEntry entry = new CachedUpstreamEntry();
        entry.setPrefixPath(last.prefix);
        entry.setHash(last.hash);
        entry.setPolicy(cachePolicy);
        entry.setPrefixToHash(prefixToHash);
        return entry;
    }

    public void updateEntry(CachedUpstreamEntry entry, Model model) {
        String hash = entry.getPrefixToHash().get(entry.getPrefixPath());
        if (hash == null) {
            if (model.getType() != ModelType.EMBEDDING) {
                log.warn("prefix is not found: {}", entry.getPrefixPath());
            }
            return;
        }
        String key = getEntryKey(model.getName(), hash);
        Map<String, String> fields = new HashMap<>();
        fields.put(UPSTREAM_ENDPOINT_ATTR, entry.getEndpoint());
        fields.put(PREFIX_PATH_ATTR, entry.getPrefixPath());
        try (var ignore = lockService.lock(key)) {
            RMap<String, String> map = redisClient.getMap(key);
            boolean isEmpty = map.isEmpty();
            map.putAll(fields);
            if (entry.getExpireAt() != null) {
                try {
                    Instant expireAt = Instant.ofEpochSecond(Long.parseLong(entry.getExpireAt()));
                    map.expire(expireAt);
                } catch (NumberFormatException e) {
                    log.error("Invalid expireAt datetime format: " + entry.getExpireAt());
                    map.expire(DEFAULT_TTL);
                }
            } else if (isEmpty) {
                // adapter didn't return expireAt for a new cache entry
                map.expire(DEFAULT_TTL);
            }
        }
    }

    private static CachedUpstreamEntry toEntry(RMap<String, String> map) {
        CachedUpstreamEntry entry = new CachedUpstreamEntry();
        entry.setEndpoint(map.get(UPSTREAM_ENDPOINT_ATTR));
        entry.setPrefixPath(map.get(PREFIX_PATH_ATTR));
        return entry;
    }

    private static String toString(byte[] digest) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : digest) {
            hexString.append(String.format("%02x", b));
        }
        return hexString.toString();
    }

    private static String getEntryKey(String modelName, String hash) {
        return modelName + ":" + hash;
    }

    @SneakyThrows
    private static MessageDigest createMessageDigest() {
        return MessageDigest.getInstance("SHA-1");
    }

    private record Breakpoint(String prefix, String hash) {}
}
