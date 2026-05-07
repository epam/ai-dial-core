package com.epam.aidial.core.mcp.tools;

import com.epam.aidial.core.mcp.client.DialClient;
import com.epam.aidial.core.mcp.client.DialResponse;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-session resolver for the {@code private} bucket alias (spec 09 §6.2, §M7). Resolves once
 * per MCP session via {@code GET /v1/bucket}, caches the encrypted bucket id thereafter.
 *
 * <p>No eviction in M.1.0 — same as the {@code McpSessionLimiter} map-leak deferred in
 * M.0.2-pre Finding 3; both maps re-integrate with whatever TTL mechanism the transport adds
 * in M.5.0.
 */
public class SessionBucketCache {

    private final DialClient dialClient;
    private final ConcurrentHashMap<String, Mono<String>> cache = new ConcurrentHashMap<>();

    public SessionBucketCache(DialClient dialClient) {
        this.dialClient = dialClient;
    }

    public Mono<String> resolvePrivate(String sessionId, Map<String, String> authHeaders) {
        if (sessionId == null) {
            return Mono.error(new IllegalStateException(
                    "private bucket alias resolution requires a session id; the MCP session may not be initialized"));
        }
        return cache.computeIfAbsent(sessionId, sid -> dialClient
                .request(HttpMethod.GET, "/v1/bucket", authHeaders, Map.of(), null)
                .flatMap(SessionBucketCache::extractBucket)
                .doOnError(e -> cache.remove(sid))
                .cache());
    }

    private static Mono<String> extractBucket(DialResponse resp) {
        if (resp.statusCode() != 200) {
            return Mono.error(new IllegalStateException(
                    "GET /v1/bucket returned HTTP " + resp.statusCode() + ": " + resp.body()));
        }
        try {
            JsonNode node = McpJson.MAPPER.readTree(resp.body());
            JsonNode bucket = node.get("bucket");
            if (bucket == null || bucket.asText().isBlank()) {
                return Mono.error(new IllegalStateException("GET /v1/bucket returned no bucket field: " + resp.body()));
            }
            return Mono.just(bucket.asText());
        } catch (Exception e) {
            return Mono.error(e);
        }
    }
}
