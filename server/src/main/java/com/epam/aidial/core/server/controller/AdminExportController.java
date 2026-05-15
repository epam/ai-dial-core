package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.security.ConfigAuthorizationService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.vertx.core.Future;
import io.vertx.core.http.HttpHeaders;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Admin-only snapshot of the in-memory {@link Config}. Default JSON; YAML when the request asks
 * for it via {@code ?format=yaml} or an {@code Accept: application/yaml} header.
 *
 * <p>Slice 2S.10 wires {@link ProxyUtil#MAPPER} with the {@code @EncryptedField} masking modifier
 * — every {@code Key.key}, {@code Upstream.key}, and {@code Upstream.extraData} value is emitted
 * as the {@code "***"} sentinel automatically. The round-trip via {@code writeValueAsString} is
 * retained because {@code applicationTypeSchemas} uses a custom serializer that calls
 * {@code writeRaw}, which {@code TokenBuffer} (used by {@code valueToTree}) does not support.
 *
 * <p>The serialization round-trip + optional YAML re-emit is dispatched via the shared
 * {@link AsyncTaskExecutor} so a config with hundreds of models/keys does not block the
 * Vert.x event loop — matches the pattern used by {@code AdminApplyController} and
 * {@code AdminValidateController}.
 */
public class AdminExportController implements Controller {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final String YAML_CONTENT_TYPE = "application/yaml";

    private final ProxyContext context;
    private final ConfigAuthorizationService authorizationService;
    private final AsyncTaskExecutor taskExecutor;

    public AdminExportController(ProxyContext context,
                                 ConfigAuthorizationService authorizationService,
                                 AsyncTaskExecutor taskExecutor) {
        this.context = context;
        this.authorizationService = authorizationService;
        this.taskExecutor = taskExecutor;
    }

    @Override
    public Future<?> handle() {
        if (!authorizationService.isAdmin(context)) {
            context.respond(HttpStatus.FORBIDDEN, "Forbidden");
            return Future.succeededFuture();
        }
        Config snapshot = context.getConfig();
        boolean yaml = isYamlRequested();
        taskExecutor.submit(() -> render(snapshot, yaml))
                .onSuccess(result -> {
                    context.putHeader(HttpHeaders.CONTENT_TYPE,
                            result.yaml() ? YAML_CONTENT_TYPE : Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON);
                    context.respond(HttpStatus.OK, result.body());
                })
                .onFailure(error -> {
                    if (error instanceof HttpException ex) {
                        context.respond(ex);
                    } else {
                        context.respond(HttpStatus.INTERNAL_SERVER_ERROR, error.getMessage());
                    }
                });
        return Future.succeededFuture();
    }

    /**
     * Returns the fully-serialized response body so the event loop only does a buffer write —
     * `ProxyContext.respond(HttpStatus, Object)` would otherwise re-invoke
     * {@code ProxyUtil.MAPPER.writeValueAsString} on the event loop and undo the offload.
     */
    private static Rendered render(Config config, boolean yaml) throws JsonProcessingException {
        ObjectNode body = buildExport(config);
        if (yaml) {
            return new Rendered(true, YAML_MAPPER.writeValueAsString(body));
        }
        return new Rendered(false, ProxyUtil.MAPPER.writeValueAsString(body));
    }

    private static ObjectNode buildExport(Config config) throws JsonProcessingException {
        // Round-trip via JSON string — applicationTypeSchemas uses a custom serializer that calls
        // writeRaw, which TokenBuffer (used by valueToTree) does not support.
        String json = ProxyUtil.MAPPER.writeValueAsString(config);
        ObjectNode body = (ObjectNode) ProxyUtil.MAPPER.readTree(json);
        // Config.keys is @JsonProperty(WRITE_ONLY); re-attach explicitly. Per-key masking is
        // handled by the @EncryptedField modifier on ProxyUtil.MAPPER. For file-mode entries the
        // map key IS the plaintext secret (pre-existing file convention), which Jackson would
        // emit verbatim as the JSON property name — bypassing the value-level mask. Project
        // those under a synthetic name keyed by project so the export doesn't leak.
        // Process file-mode entries via a stable-sorted list so the synthetic `-N` collision
        // suffix is deterministic across rebuilds — the underlying `Config.keys` map is a
        // HashMap whose iteration order is not stable, which would otherwise let two file-mode
        // keys sharing the same project swap suffixes between calls. Sort key for file entries
        // is (project, sha256(mapKey)) — the digest is used only for sort, never emitted, so no
        // plaintext-secret information leaks via the ordering.
        ObjectNode keys = body.putObject("keys");
        List<Map.Entry<String, Key>> fileEntries = new ArrayList<>();
        for (Map.Entry<String, Key> entry : config.getKeys().entrySet()) {
            String mapKey = entry.getKey();
            if (mapKey.startsWith("keys/")) {
                keys.set(mapKey, ProxyUtil.MAPPER.valueToTree(entry.getValue()));
            } else {
                fileEntries.add(entry);
            }
        }
        fileEntries.sort(Comparator
                .<Map.Entry<String, Key>, String>comparing(e -> nullsLast(e.getValue().getProject()))
                .thenComparing(e -> stableHash(e.getKey())));
        Map<String, Integer> fileNameDedup = new HashMap<>();
        for (Map.Entry<String, Key> entry : fileEntries) {
            Key value = entry.getValue();
            String base = "keys/file/" + (value.getProject() == null ? "unknown" : value.getProject());
            int idx = fileNameDedup.merge(base, 0, (prev, ignored) -> prev + 1);
            String propertyName = idx == 0 ? base : base + "-" + idx;
            keys.set(propertyName, ProxyUtil.MAPPER.valueToTree(value));
        }
        return body;
    }

    private static String nullsLast(String s) {
        return s == null ? "￿" : s;
    }

    private static String stableHash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private boolean isYamlRequested() {
        String fmt = context.getRequest().getParam("format");
        if ("yaml".equalsIgnoreCase(fmt)) {
            return true;
        }
        String accept = context.getRequest().getHeader(HttpHeaders.ACCEPT);
        return accept != null && accept.toLowerCase().contains("yaml");
    }

    private record Rendered(boolean yaml, String body) {
    }
}
