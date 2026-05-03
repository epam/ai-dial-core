package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.config.InvalidEntityRecord;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.config.ValidationWarning;
import com.epam.aidial.core.server.security.ConfigAuthorizationService;
import com.epam.aidial.core.server.security.EntityBucketBinding;
import com.epam.aidial.core.server.security.Operation;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.http.HttpMethod;

import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiFunction;

/**
 * Controller for the {@code /v1/{type}/{bucket}/{path}} CONFIG_RESOURCE route — gates on
 * the {@link EntityBucketBinding} allowlist and {@link ConfigAuthorizationService}, then
 * dispatches GET to per-type read handlers. Mutating verbs return 405 (Phase 2 implements
 * write paths).
 */
public class ConfigResourceController implements Controller {

    private static final String SETTINGS_TYPE = "settings";
    private static final String SETTINGS_SINGLETON_NAME = "global";
    private static final String SETTINGS_ALLOW = "GET, PUT, DELETE";

    private final ProxyContext context;
    private final ConfigAuthorizationService authorizationService;
    private final MergedConfigStore mergedConfigStore;
    private final String entityType;
    private final String bucket;
    private final String path;

    public ConfigResourceController(ProxyContext context,
                                    ConfigAuthorizationService authorizationService,
                                    MergedConfigStore mergedConfigStore,
                                    String entityType,
                                    String bucket,
                                    String path) {
        this.context = context;
        this.authorizationService = authorizationService;
        this.mergedConfigStore = mergedConfigStore;
        this.entityType = entityType;
        this.bucket = bucket;
        this.path = path;
    }

    @Override
    public Future<?> handle() throws Exception {
        if (!EntityBucketBinding.isAllowed(entityType, bucket)) {
            // Body-less 404 — must be indistinguishable from "entity not found" so an unauthenticated
            // probe cannot tell from the response whether the (type, bucket) pair is invalid or
            // merely empty. See 04-security-and-audit.md §1.2.
            context.respond(HttpStatus.NOT_FOUND);
            return Future.succeededFuture();
        }

        HttpMethod method = context.getRequest().method();
        Operation operation = (method == HttpMethod.GET || method == HttpMethod.HEAD)
                ? Operation.READ
                : Operation.WRITE;
        if (!authorizationService.isAuthorized(context, entityType, path, bucket, operation)) {
            context.respond(HttpStatus.FORBIDDEN, "Forbidden");
            return Future.succeededFuture();
        }

        if (method == HttpMethod.GET) {
            return handleGet();
        }

        return respondMethodNotAllowed();
    }

    private Future<?> handleGet() throws JsonProcessingException {
        Config config = context.getConfig();
        boolean admin = authorizationService.isAdmin(context);
        // Bucket-aware authz already gated non-admin readers off platform/, so source is always emitted
        // for platform/ types. For public/ types, source is Owner-only.
        return switch (entityType) {
            case "models" -> handleSingleOrList(
                    config.getModels(), ResourceTypes.MODEL,
                    (key, model) -> projectItem(model, simpleName(key), fromApi(key), admin));
            case "interceptors" -> handleSingleOrList(
                    config.getInterceptors(), ResourceTypes.INTERCEPTOR,
                    (key, interceptor) -> projectItem(interceptor, simpleName(key), fromApi(key), true));
            case "roles" -> handleSingleOrList(
                    config.getRoles(), ResourceTypes.ROLE,
                    (key, role) -> projectItem(role, simpleName(key), fromApi(key), true));
            case "keys" -> handleSingleOrList(
                    config.getKeys(), ResourceTypes.PROJECT_KEY,
                    (key, value) -> projectKeyItem(simpleName(key), value, fromApi(key)));
            case "routes" -> handleSingleOrList(
                    config.getRoutes(), ResourceTypes.ROUTE,
                    (key, route) -> projectItem(route, simpleName(key), fromApi(key), true));
            case "schemas" -> handleSchemaGet(config, admin);
            case SETTINGS_TYPE -> handleSettingsGet(config);
            default -> respondMethodNotAllowed();
        };
    }

    private <T> Future<?> handleSingleOrList(Map<String, T> source,
                                             ResourceTypes resourceType,
                                             BiFunction<String, T, ObjectNode> projector) {
        Map<String, InvalidEntityRecord> invalid = mergedConfigStore.getInvalidEntities()
                .getOrDefault(resourceType, Map.of());
        boolean admin = authorizationService.isAdmin(context);

        if (path == null || path.isEmpty()) {
            return respondList(source, invalid, projector, admin);
        }
        // MergedConfigStore keys API entries by canonical ID ("models/public/gpt-4") and file
        // entries by simple name ("gpt-4"). Try canonical ID first, fall back to simple name —
        // see design 02 §4 union semantics.
        T item = source.get(canonicalId());
        if (item != null) {
            context.respond(HttpStatus.OK, projector.apply(canonicalId(), item));
            return Future.succeededFuture();
        }
        item = source.get(path);
        if (item != null) {
            context.respond(HttpStatus.OK, projector.apply(path, item));
            return Future.succeededFuture();
        }
        InvalidEntityRecord invalidRecord = invalid.get(canonicalId());
        if (invalidRecord != null) {
            context.respond(HttpStatus.OK, projectInvalidItem(invalidRecord, admin));
            return Future.succeededFuture();
        }
        context.respond(HttpStatus.NOT_FOUND);
        return Future.succeededFuture();
    }

    private String canonicalId() {
        return entityType + "/" + bucket + "/" + path;
    }

    private <T> Future<?> respondList(Map<String, T> source,
                                      Map<String, InvalidEntityRecord> invalid,
                                      BiFunction<String, T, ObjectNode> projector,
                                      boolean admin) {
        if (!isLimitValid()) {
            context.respond(HttpStatus.BAD_REQUEST, "Invalid 'limit' query parameter");
            return Future.succeededFuture();
        }
        // Sort valid entries by simple name; merge invalid records by simple name; collisions favor
        // the valid (in-Config) entry — invalid records are only kept for entries dropped from Config.
        Map<String, ObjectNode> bySimpleName = new TreeMap<>();
        for (Map.Entry<String, T> entry : source.entrySet()) {
            bySimpleName.put(simpleName(entry.getKey()), projector.apply(entry.getKey(), entry.getValue()));
        }
        for (InvalidEntityRecord record : invalid.values()) {
            bySimpleName.putIfAbsent(record.getSimpleName(), projectInvalidItem(record, admin));
        }
        ArrayNode items = ProxyUtil.MAPPER.createArrayNode();
        bySimpleName.values().forEach(items::add);
        context.respond(HttpStatus.OK, listEnvelope(items));
        return Future.succeededFuture();
    }

    /** Extract the simple name from a canonical ID ("models/public/gpt-4" → "gpt-4"); pass through otherwise. */
    private static String simpleName(String key) {
        int slash = key.lastIndexOf('/');
        return slash < 0 ? key : key.substring(slash + 1);
    }

    /**
     * A canonical-ID-shaped key — {@code <entityType>/<bucket>/<name>} — marks an API-managed entry.
     * File-defined entries either keep simple-name keys (most types) or use external URL keys
     * ({@code applicationTypeSchemas} keys are {@code $id} URLs); only the canonical prefix is
     * conclusive evidence of API origin.
     */
    private boolean fromApi(String key) {
        return key.startsWith(entityType + "/" + bucket + "/");
    }

    private Future<?> handleSchemaGet(Config config, boolean admin) throws JsonProcessingException {
        Map<String, String> schemas = config.getApplicationTypeSchemas();
        Map<String, InvalidEntityRecord> invalid = mergedConfigStore.getInvalidEntities()
                .getOrDefault(ResourceTypes.APP_TYPE_SCHEMA, Map.of());
        if (path == null || path.isEmpty()) {
            if (!isLimitValid()) {
                context.respond(HttpStatus.BAD_REQUEST, "Invalid 'limit' query parameter");
                return Future.succeededFuture();
            }
            Map<String, ObjectNode> bySimpleName = new TreeMap<>();
            for (Map.Entry<String, String> entry : schemas.entrySet()) {
                bySimpleName.put(simpleName(entry.getKey()),
                        projectSchemaItem(simpleName(entry.getKey()), entry.getValue(), fromApi(entry.getKey()), admin));
            }
            for (InvalidEntityRecord record : invalid.values()) {
                bySimpleName.putIfAbsent(record.getSimpleName(), projectInvalidItem(record, admin));
            }
            ArrayNode items = ProxyUtil.MAPPER.createArrayNode();
            bySimpleName.values().forEach(items::add);
            context.respond(HttpStatus.OK, listEnvelope(items));
            return Future.succeededFuture();
        }
        // Canonical-ID first, simple-name fallback (see handleSingleOrList).
        String schemaJson = schemas.get(canonicalId());
        if (schemaJson != null) {
            context.respond(HttpStatus.OK, projectSchemaItem(path, schemaJson, true, admin));
            return Future.succeededFuture();
        }
        schemaJson = schemas.get(path);
        if (schemaJson != null) {
            context.respond(HttpStatus.OK, projectSchemaItem(path, schemaJson, false, admin));
            return Future.succeededFuture();
        }
        InvalidEntityRecord invalidRecord = invalid.get(canonicalId());
        if (invalidRecord != null) {
            context.respond(HttpStatus.OK, projectInvalidItem(invalidRecord, admin));
            return Future.succeededFuture();
        }
        context.respond(HttpStatus.NOT_FOUND);
        return Future.succeededFuture();
    }

    private Future<?> handleSettingsGet(Config config) {
        if (path == null || path.isEmpty()) {
            // Singleton has no listing surface — design-locked 405 with full eventual Allow set.
            return respondMethodNotAllowed();
        }
        if (!SETTINGS_SINGLETON_NAME.equals(path)) {
            context.respond(HttpStatus.NOT_FOUND);
            return Future.succeededFuture();
        }
        ObjectNode body = ProxyUtil.MAPPER.createObjectNode();
        body.set("globalInterceptors", ProxyUtil.MAPPER.valueToTree(config.getGlobalInterceptors()));
        body.put("name", SETTINGS_SINGLETON_NAME);
        body.put("status", "valid");
        // Phase 1 has no MergedConfigStore, so "api" is unreachable. File-defines-fields is detected by
        // any non-default Config-level setting being populated; otherwise the projection is "default".
        body.put("source", config.getGlobalInterceptors().isEmpty() ? "default" : "file");
        context.respond(HttpStatus.OK, body);
        return Future.succeededFuture();
    }

    private ObjectNode listEnvelope(ArrayNode items) {
        ObjectNode body = ProxyUtil.MAPPER.createObjectNode();
        body.put("entityType", entityType);
        body.put("bucket", bucket);
        body.set("items", items);
        body.put("hasMore", false);
        return body;
    }

    private ObjectNode projectItem(Object item, String name, boolean fromApi, boolean includeSource) {
        ObjectNode node = ProxyUtil.MAPPER.valueToTree(item);
        node.put("name", name);
        node.put("status", "valid");
        if (includeSource) {
            node.put("source", fromApi ? "api" : "file");
        }
        return node;
    }

    private ObjectNode projectKeyItem(String name, Key key, boolean fromApi) {
        ObjectNode node = projectItem(key, name, fromApi, true);
        // Phase 1 has no ?reveal_secrets=true surface — mask the secret with the locked sentinel
        // (design 04 §2.5–§2.6). Phase 2 introduces @EncryptedField + reveal flow.
        if (node.has("key")) {
            node.put("key", "***");
        }
        return node;
    }

    private ObjectNode projectSchemaItem(String name, String json, boolean fromApi, boolean admin)
            throws JsonProcessingException {
        // applicationTypeSchemas stores raw JSON strings; parse for projection.
        JsonNode schema = ProxyUtil.MAPPER.readTree(json);
        ObjectNode node = ProxyUtil.MAPPER.createObjectNode();
        if (schema.isObject()) {
            node.setAll((ObjectNode) schema);
        } else {
            node.set("schema", schema);
        }
        node.put("name", name);
        node.put("status", "valid");
        if (admin) {
            node.put("source", fromApi ? "api" : "file");
        }
        return node;
    }

    private ObjectNode projectInvalidItem(InvalidEntityRecord record, boolean admin) {
        ObjectNode node = ProxyUtil.MAPPER.createObjectNode();
        if (record.getPayload() instanceof ObjectNode payload) {
            node.setAll(payload);
        }
        node.put("name", record.getSimpleName());
        node.put("status", "invalid");
        if (admin) {
            node.put("source", record.getSource());
            ArrayNode warnings = node.putArray("validationWarnings");
            for (ValidationWarning warning : record.getValidationWarnings()) {
                ObjectNode w = warnings.addObject();
                w.put("field", warning.getField());
                w.put("message", warning.getMessage());
            }
        }
        // Defensively mask "key" for invalid PROJECT_KEY entries — same rule as projectKeyItem.
        if (node.has("key")) {
            node.put("key", "***");
        }
        return node;
    }

    private Future<?> respondMethodNotAllowed() {
        if (SETTINGS_TYPE.equals(entityType)) {
            context.putHeader("Allow", SETTINGS_ALLOW);
        }
        context.respond(HttpStatus.METHOD_NOT_ALLOWED, "Not implemented");
        return Future.succeededFuture();
    }

    /** Phase 1 validates limit shape only — accepts absent or any positive integer (clamping ships in Phase 2). */
    private boolean isLimitValid() {
        String raw = context.getRequest().getParam("limit");
        if (raw == null) {
            return true;
        }
        try {
            return Integer.parseInt(raw) >= 1;
        } catch (NumberFormatException e) {
            return false;
        }
    }

}
