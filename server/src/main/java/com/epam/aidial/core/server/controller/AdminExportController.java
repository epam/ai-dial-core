package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.security.ConfigAuthorizationService;
import com.epam.aidial.core.server.util.ProxyUtil;
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
 */
public class AdminExportController implements Controller {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final String YAML_CONTENT_TYPE = "application/yaml";

    private final ProxyContext context;
    private final ConfigAuthorizationService authorizationService;

    public AdminExportController(ProxyContext context, ConfigAuthorizationService authorizationService) {
        this.context = context;
        this.authorizationService = authorizationService;
    }

    @Override
    public Future<?> handle() throws Exception {
        if (!authorizationService.isAdmin(context)) {
            context.respond(HttpStatus.FORBIDDEN, "Forbidden");
            return Future.succeededFuture();
        }
        ObjectNode body = buildExport(context.getConfig());
        if (isYamlRequested()) {
            String yaml = YAML_MAPPER.writeValueAsString(body);
            context.putHeader(HttpHeaders.CONTENT_TYPE, YAML_CONTENT_TYPE)
                    .respond(HttpStatus.OK, yaml);
        } else {
            context.respond(HttpStatus.OK, body);
        }
        return Future.succeededFuture();
    }

    private ObjectNode buildExport(Config config) throws JsonProcessingException {
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
}
