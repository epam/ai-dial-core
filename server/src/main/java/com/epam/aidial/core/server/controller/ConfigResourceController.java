package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Role;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.config.Settings;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.config.ConfigPostProcessor;
import com.epam.aidial.core.server.config.InvalidEntityRecord;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.config.SecretFieldProcessor;
import com.epam.aidial.core.server.config.ValidationWarning;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.security.ConfigAuthorizationService;
import com.epam.aidial.core.server.security.EntityBucketBinding;
import com.epam.aidial.core.server.security.Operation;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiFunction;

/**
 * Controller for the {@code /v1/{type}/{bucket}/{path}} CONFIG_RESOURCE route — gates on
 * the {@link EntityBucketBinding} allowlist and {@link ConfigAuthorizationService}, then
 * dispatches GET to per-type read handlers and POST/PUT/DELETE for models, interceptors,
 * roles, keys, routes, schemas, and the settings singleton (singleton has no POST surface).
 */
@Slf4j
public class ConfigResourceController implements Controller {

    private static final String SETTINGS_TYPE = "settings";
    private static final String SETTINGS_SINGLETON_NAME = "global";
    private static final String SETTINGS_ALLOW = "GET, PUT, DELETE";
    private static final String WRITE_ALLOW = "GET, POST, PUT, DELETE";

    private final ProxyContext context;
    private final ConfigAuthorizationService authorizationService;
    private final MergedConfigStore mergedConfigStore;
    private final ResourceService resourceService;
    private final AsyncTaskExecutor taskExecutor;
    private final SecretFieldProcessor secretFieldProcessor;
    private final boolean softValidation;
    private final ApiKeyStore apiKeyStore;
    private final String entityType;
    private final String bucket;
    private final String path;

    public ConfigResourceController(ProxyContext context,
                                    ConfigAuthorizationService authorizationService,
                                    MergedConfigStore mergedConfigStore,
                                    ResourceService resourceService,
                                    AsyncTaskExecutor taskExecutor,
                                    SecretFieldProcessor secretFieldProcessor,
                                    boolean softValidation,
                                    ApiKeyStore apiKeyStore,
                                    String entityType,
                                    String bucket,
                                    String path) {
        this.context = context;
        this.authorizationService = authorizationService;
        this.mergedConfigStore = mergedConfigStore;
        this.resourceService = resourceService;
        this.taskExecutor = taskExecutor;
        this.secretFieldProcessor = secretFieldProcessor;
        this.softValidation = softValidation;
        this.apiKeyStore = apiKeyStore;
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

        if (method == HttpMethod.GET || method == HttpMethod.HEAD) {
            return handleGet();
        }
        if (SETTINGS_TYPE.equals(entityType)) {
            // Singleton has its own write surface: PUT-upsert + idempotent DELETE; POST is 405.
            if (method == HttpMethod.PUT) {
                return handleSettingsPut();
            }
            if (method == HttpMethod.DELETE) {
                return handleSettingsDelete();
            }
            return respondMethodNotAllowed();
        }
        if (method == HttpMethod.POST) {
            return handlePost();
        }
        if (method == HttpMethod.PUT) {
            return handlePut();
        }
        if (method == HttpMethod.DELETE) {
            return handleDelete();
        }

        return respondMethodNotAllowed();
    }

    private Future<?> handleGet() throws JsonProcessingException {
        Config config = context.getConfig();
        boolean admin = authorizationService.isAdmin(context);
        boolean revealSecrets = "true".equals(context.getRequest().getParam("reveal_secrets"));
        if (revealSecrets && !authorizationService.isSecurityAdmin(context)) {
            context.respond(HttpStatus.FORBIDDEN, "reveal_secrets requires security-admin role");
            return Future.succeededFuture();
        }
        // Bucket-aware authz already gated non-admin readers off platform/, so source is always emitted
        // for platform/ types. For public/ types, source is Owner-only.
        return switch (entityType) {
            case "models" -> handleSingleOrList(
                    config.getModels(), ResourceTypes.MODEL,
                    (key, model) -> projectItem(model, displayName(key), fromApi(key), admin, revealSecrets));
            case "interceptors" -> handleSingleOrList(
                    config.getInterceptors(), ResourceTypes.INTERCEPTOR,
                    (key, interceptor) -> projectItem(interceptor, displayName(key), fromApi(key), true, revealSecrets));
            case "roles" -> handleSingleOrList(
                    config.getRoles(), ResourceTypes.ROLE,
                    (key, role) -> projectItem(role, displayName(key), fromApi(key), true, revealSecrets));
            case "keys" -> handleSingleOrList(
                    config.getKeys(), ResourceTypes.PROJECT_KEY,
                    (key, value) -> projectItem(value, displayName(key), fromApi(key), true, revealSecrets));
            case "routes" -> handleSingleOrList(
                    config.getRoutes(), ResourceTypes.ROUTE,
                    (key, route) -> projectItem(route, displayName(key), fromApi(key), true, revealSecrets));
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
        // Sort + dedup by the Config map key so file entries (simple-name keys) and API entries
        // (canonical-ID keys) appear as distinct rows when both share a simple name; invalid records
        // merge by their canonical ID and yield to a valid in-Config entry on collision.
        Map<String, ObjectNode> byKey = new TreeMap<>();
        for (Map.Entry<String, T> entry : source.entrySet()) {
            byKey.put(entry.getKey(), projector.apply(entry.getKey(), entry.getValue()));
        }
        for (InvalidEntityRecord record : invalid.values()) {
            byKey.putIfAbsent(record.getCanonicalId(), projectInvalidItem(record, admin));
        }
        ArrayNode items = ProxyUtil.MAPPER.createArrayNode();
        byKey.values().forEach(items::add);
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

    /**
     * Projected {@code name} value: full canonical ID for API-managed entries (so the listing
     * row's name is copy-paste-friendly into chat-completion / canonical URLs), simple name for
     * file-defined entries (their only addressable form). See design 03 §4 (amended 2026-05-08).
     */
    private String displayName(String key) {
        return fromApi(key) ? key : simpleName(key);
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
            Map<String, ObjectNode> byKey = new TreeMap<>();
            for (Map.Entry<String, String> entry : schemas.entrySet()) {
                byKey.put(entry.getKey(),
                        projectSchemaItem(displayName(entry.getKey()), entry.getValue(),
                                fromApi(entry.getKey()), admin));
            }
            for (InvalidEntityRecord record : invalid.values()) {
                byKey.putIfAbsent(record.getCanonicalId(), projectInvalidItem(record, admin));
            }
            ArrayNode items = ProxyUtil.MAPPER.createArrayNode();
            byKey.values().forEach(items::add);
            context.respond(HttpStatus.OK, listEnvelope(items));
            return Future.succeededFuture();
        }
        // Canonical-ID first, simple-name fallback (see handleSingleOrList).
        String schemaJson = schemas.get(canonicalId());
        if (schemaJson != null) {
            context.respond(HttpStatus.OK, projectSchemaItem(canonicalId(), schemaJson, true, admin));
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
        body.set("retriableErrorCodes", ProxyUtil.MAPPER.valueToTree(config.getRetriableErrorCodes()));
        body.put("name", SETTINGS_SINGLETON_NAME);
        body.put("status", "valid");
        String source;
        if (mergedConfigStore.isSettingsFromApi()) {
            source = "api";
        } else if (!config.getGlobalInterceptors().isEmpty() || !config.getRetriableErrorCodes().isEmpty()) {
            source = "file";
        } else {
            source = "default";
        }
        body.put("source", source);
        context.respond(HttpStatus.OK, body);
        return Future.succeededFuture();
    }

    private Future<?> handleSettingsPut() {
        if (!SETTINGS_SINGLETON_NAME.equals(path)) {
            context.respond(HttpStatus.NOT_FOUND);
            return Future.succeededFuture();
        }
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.GLOBAL_SETTINGS, ResourceDescriptor.PLATFORM_BUCKET,
                ResourceDescriptor.PLATFORM_LOCATION, SETTINGS_SINGLETON_NAME);

        context.getRequest().body().compose(body -> {
            JsonNode requestNode = parseJsonBody(body);
            if (!requestNode.isObject()) {
                throw new HttpException(HttpStatus.BAD_REQUEST, "Request body must be a JSON object");
            }
            // Deserialize through the typed Settings POJO so unknown fields are dropped and types
            // are validated; re-serialize so the blob is canonical (locked field set, no extras).
            Settings settings = treeToEntity(requestNode, Settings.class);
            String blobBody = serializeForBlob(settings);
            return taskExecutor.submit(() -> {
                ResourceItemMetadata meta = resourceService.putResource(descriptor, blobBody, EtagHeader.ANY);
                mergedConfigStore.rebuildNow();
                return meta;
            });
        }).onSuccess(meta -> context.putHeader(HttpHeaders.ETAG, meta.getEtag())
                .respond(HttpStatus.OK, createNameEnvelope(SETTINGS_SINGLETON_NAME)))
                .onFailure(this::handleWriteError);

        return Future.succeededFuture();
    }

    private Future<?> handleSettingsDelete() {
        if (!SETTINGS_SINGLETON_NAME.equals(path)) {
            context.respond(HttpStatus.NOT_FOUND);
            return Future.succeededFuture();
        }
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.GLOBAL_SETTINGS, ResourceDescriptor.PLATFORM_BUCKET,
                ResourceDescriptor.PLATFORM_LOCATION, SETTINGS_SINGLETON_NAME);

        taskExecutor.submit(() -> {
            // Idempotent — deleteResource returns false when the blob is absent; both outcomes
            // collapse to 204 since the post-state (no API blob) is identical.
            resourceService.deleteResource(descriptor, EtagHeader.ANY);
            mergedConfigStore.rebuildNow();
            return true;
        }).onSuccess(v -> context.respond(HttpStatus.NO_CONTENT)).onFailure(this::handleWriteError);

        return Future.succeededFuture();
    }

    private Future<?> handlePost() {
        WriteSpec spec = prepareWrite();
        if (spec == null) {
            return Future.succeededFuture();
        }
        ResourceDescriptor descriptor = spec.descriptor();
        String name = path;
        EtagHeader etag = ProxyUtil.etag(context.getRequest());

        context.getRequest().body().compose(body -> {
            JsonNode requestNode = parseJsonBody(body);
            if (spec.hasEncryptedFields()) {
                try {
                    secretFieldProcessor.validateNoMaskSentinel(requestNode, spec.entityClass());
                } catch (IllegalArgumentException e) {
                    throw new HttpException(HttpStatus.BAD_REQUEST, e.getMessage());
                }
            }
            return taskExecutor.submit(() -> {
                String blobBody;
                Key keyEntity = null;
                String keySecret = null;
                Object entity = null;
                if (spec.entityClass() == null) {
                    blobBody = requestNode.toString();
                } else {
                    entity = treeToEntity(requestNode, spec.entityClass());
                    if (entity instanceof Model m) {
                        checkCrossReferences(m);
                    }
                    if (spec.isKey()) {
                        keyEntity = (Key) entity;
                        validateKeyForApiWrite(keyEntity, "POST");
                        // Capture before encryptFields mutates Key.key to ciphertext in place;
                        // ApiKeyStore is indexed by plaintext secret (see ApiKeyStore.getApiKeyData).
                        keySecret = keyEntity.getKey();
                    }
                    if (spec.hasEncryptedFields()) {
                        secretFieldProcessor.encryptFields(entity, descriptor);
                    }
                    blobBody = serializeForBlob(entity);
                }
                try (LockService.Lock ignored = resourceService.lockResource(descriptor)) {
                    ResourceItemMetadata existing = resourceService.getResourceMetadata(descriptor);
                    // Honor client-supplied conditional headers (If-Match / If-None-Match: *) before
                    // the implicit create-only 409: this yields RFC-compliant 412 PRECONDITION_FAILED
                    // when a client opts in via etag. Headerless POST falls through to 409.
                    etag.validate(existing == null ? null : existing.getEtag());
                    if (existing != null) {
                        throw new HttpException(HttpStatus.CONFLICT,
                                "Resource already exists: " + descriptor.getUrl());
                    }
                    ResourceItemMetadata meta = resourceService.putResource(
                            descriptor, blobBody, EtagHeader.ANY, null, false);
                    if (keySecret != null) {
                        apiKeyStore.addOrUpdateKey(keySecret, apiKeyData(keyEntity));
                    }
                    mergedConfigStore.rebuildNow();
                    return meta;
                }
            });
        }).onSuccess(meta -> context.putHeader(HttpHeaders.ETAG, meta.getEtag())
                .respond(HttpStatus.CREATED, createNameEnvelope(name)))
                .onFailure(this::handleWriteError);

        return Future.succeededFuture();
    }

    private Future<?> handlePut() {
        WriteSpec spec = prepareWrite();
        if (spec == null) {
            return Future.succeededFuture();
        }
        ResourceDescriptor descriptor = spec.descriptor();
        String name = path;
        EtagHeader etag = ProxyUtil.etag(context.getRequest());

        context.getRequest().body().compose(body -> {
            JsonNode requestNode = parseJsonBody(body);
            return taskExecutor.submit(() -> {
                try (LockService.Lock ignored = resourceService.lockResource(descriptor)) {
                    String existingBody = resourceService.getResource(descriptor, EtagHeader.ANY, false);
                    if (existingBody == null) {
                        throw new HttpException(HttpStatus.NOT_FOUND,
                                "Resource not found: " + descriptor.getUrl());
                    }
                    String blobBody;
                    Key keyEntity = null;
                    String keySecret = null;
                    if (spec.entityClass() == null) {
                        blobBody = requestNode.toString();
                    } else {
                        if (!requestNode.isObject()) {
                            throw new HttpException(HttpStatus.BAD_REQUEST,
                                    "Request body must be a JSON object");
                        }
                        JsonNode source;
                        if (spec.hasEncryptedFields()) {
                            JsonNode existingBlobNode;
                            try {
                                existingBlobNode = ProxyUtil.BLOB_MAPPER.readTree(existingBody);
                            } catch (JsonProcessingException e) {
                                // Don't echo getOriginalMessage() — the stored blob can carry
                                // ciphertext or other content we don't want to surface verbatim.
                                throw new HttpException(HttpStatus.INTERNAL_SERVER_ERROR,
                                        "Stored entity is malformed at " + locationOf(e));
                            }
                            source = secretFieldProcessor.mergePreservingOmittedSecrets(
                                    existingBlobNode, requestNode, spec.entityClass());
                        } else {
                            source = requestNode;
                        }
                        Object entity = treeToEntity(source, spec.entityClass());
                        if (entity instanceof Model m) {
                            checkCrossReferences(m);
                        }
                        if (spec.isKey()) {
                            keyEntity = (Key) entity;
                            validateKeyForApiWrite(keyEntity, "PUT");
                            // Capture before encryptFields mutates Key.key to ciphertext in place.
                            keySecret = keyEntity.getKey();
                        }
                        if (spec.hasEncryptedFields()) {
                            secretFieldProcessor.encryptFields(entity, descriptor);
                        }
                        blobBody = serializeForBlob(entity);
                    }
                    ResourceItemMetadata meta = resourceService.putResource(
                            descriptor, blobBody, etag, null, false);
                    if (keySecret != null) {
                        apiKeyStore.addOrUpdateKey(keySecret, apiKeyData(keyEntity));
                    }
                    mergedConfigStore.rebuildNow();
                    return meta;
                }
            });
        }).onSuccess(meta -> context.putHeader(HttpHeaders.ETAG, meta.getEtag())
                .respond(HttpStatus.OK, createNameEnvelope(name)))
                .onFailure(this::handleWriteError);

        return Future.succeededFuture();
    }

    private Future<?> handleDelete() {
        WriteSpec spec = prepareWrite();
        if (spec == null) {
            return Future.succeededFuture();
        }
        ResourceDescriptor descriptor = spec.descriptor();
        EtagHeader etag = ProxyUtil.etag(context.getRequest());

        taskExecutor.submit(() -> {
            // Pre-read + delete must run under the same lock so the secret extracted for
            // apiKeyStore.removeKey matches the secret in the blob being deleted (no race
            // with a concurrent PUT swapping the key).
            try (LockService.Lock ignored = resourceService.lockResource(descriptor)) {
                String deletedSecret = null;
                if (spec.isKey()) {
                    String existing = resourceService.getResource(descriptor, EtagHeader.ANY, false);
                    if (existing != null) {
                        try {
                            JsonNode node = ProxyUtil.BLOB_MAPPER.readTree(existing);
                            Key key = ProxyUtil.BLOB_MAPPER.treeToValue(node, Key.class);
                            secretFieldProcessor.decryptFields(key, descriptor);
                            deletedSecret = key.getKey();
                        } catch (Exception e) {
                            // Corrupt blob or decrypt failure. Fall back to the in-memory snapshot
                            // so the DELETE ordering invariant (apiKeyStore.removeKey BEFORE the
                            // new merged Config becomes visible) is preserved — otherwise the live
                            // secret remains authenticatable until the next full rebuild.
                            log.warn("Could not extract key secret from blob, falling back to "
                                    + "in-memory snapshot: {}", e.getMessage());
                            String canonicalId = "keys/" + descriptor.getBucketName() + "/" + descriptor.getName();
                            Config snapshot = mergedConfigStore.get();
                            if (snapshot != null) {
                                Key inMemory = snapshot.getKeys().get(canonicalId);
                                if (inMemory != null && StringUtils.isNotBlank(inMemory.getKey())) {
                                    deletedSecret = inMemory.getKey();
                                }
                            }
                            if (deletedSecret == null) {
                                throw new HttpException(HttpStatus.INTERNAL_SERVER_ERROR,
                                        "Stored key entity is unreadable and no in-memory snapshot is available; "
                                                + "delete aborted to preserve auth-store ordering");
                            }
                        }
                    }
                }
                boolean deleted = resourceService.deleteResource(descriptor, etag, false);
                if (!deleted) {
                    throw new HttpException(HttpStatus.NOT_FOUND,
                            "Resource not found: " + descriptor.getUrl());
                }
                if (deletedSecret != null) {
                    apiKeyStore.removeKey(deletedSecret);
                }
                mergedConfigStore.rebuildNow();
                return true;
            }
        }).onSuccess(v -> context.respond(HttpStatus.NO_CONTENT)).onFailure(this::handleWriteError);

        return Future.succeededFuture();
    }

    private static ApiKeyData apiKeyData(Key key) {
        ApiKeyData data = new ApiKeyData();
        data.setOriginalKey(key);
        return data;
    }

    private static void validateKeyForApiWrite(Key key, String method) {
        if (StringUtils.isBlank(key.getKey())) {
            throw new HttpException(HttpStatus.BAD_REQUEST,
                    "Key.key must be provided explicitly on " + method);
        }
        validateProjectKey(key);
    }

    /**
     * Validate the write target and return its spec. Returns {@code null} after writing the
     * appropriate 4xx response when the request can't proceed — callers short-circuit on null.
     */
    private WriteSpec prepareWrite() {
        if (path == null || path.isEmpty() || path.endsWith("/")) {
            context.respond(HttpStatus.BAD_REQUEST, "Resource name must not be empty or a folder");
            return null;
        }
        return switch (entityType) {
            case "models" -> new WriteSpec(
                    ResourceDescriptorFactory.fromDecoded(ResourceTypes.MODEL,
                            ResourceDescriptor.PUBLIC_BUCKET, ResourceDescriptor.PUBLIC_LOCATION, path),
                    Model.class, true, false);
            case "interceptors" -> new WriteSpec(
                    ResourceDescriptorFactory.fromDecoded(ResourceTypes.INTERCEPTOR,
                            ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION, path),
                    Interceptor.class, false, false);
            case "roles" -> new WriteSpec(
                    ResourceDescriptorFactory.fromDecoded(ResourceTypes.ROLE,
                            ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION, path),
                    Role.class, false, false);
            case "keys" -> new WriteSpec(
                    ResourceDescriptorFactory.fromDecoded(ResourceTypes.PROJECT_KEY,
                            ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION, path),
                    Key.class, true, true);
            case "routes" -> new WriteSpec(
                    ResourceDescriptorFactory.fromDecoded(ResourceTypes.ROUTE,
                            ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION, path),
                    Route.class, true, false);
            case "schemas" -> new WriteSpec(
                    ResourceDescriptorFactory.fromDecoded(ResourceTypes.APP_TYPE_SCHEMA,
                            ResourceDescriptor.PUBLIC_BUCKET, ResourceDescriptor.PUBLIC_LOCATION, path),
                    null, false, false);
            default -> {
                respondWriteMethodNotAllowed();
                yield null;
            }
        };
    }

    private static void validateProjectKey(Key key) {
        // Mirrors ApiKeyStore#validateProjectKey (private there); duplicated to translate
        // IllegalArgumentException into HttpException without widening visibility upstream.
        if (StringUtils.isBlank(key.getProject())) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Project key is undefined");
        }
        if (StringUtils.isBlank(key.getRole()) && (key.getRoles() == null || key.getRoles().isEmpty())) {
            throw new HttpException(HttpStatus.BAD_REQUEST,
                    "Invalid key: at least one role must be assigned to the key " + key.getProject());
        }
    }

    private static JsonNode parseJsonBody(Buffer body) {
        String text = body == null ? "" : body.toString(StandardCharsets.UTF_8);
        try {
            return ProxyUtil.BLOB_MAPPER.readTree(text.isEmpty() ? "{}" : text);
        } catch (JsonProcessingException e) {
            // getOriginalMessage() echoes the offending token verbatim, which can include
            // submitted credentials (Key.key, Upstream.key, etc.). Surface only the location.
            throw new HttpException(HttpStatus.BAD_REQUEST, "Invalid JSON at " + locationOf(e));
        }
    }

    static <T> T treeToEntity(JsonNode node, Class<T> cls) {
        try {
            return ProxyUtil.BLOB_MAPPER.treeToValue(node, cls);
        } catch (JsonProcessingException e) {
            // Same rationale as parseJsonBody — the mapping error can embed the offending
            // field value, including secrets in a partially-typed request body.
            throw new HttpException(HttpStatus.BAD_REQUEST,
                    "Failed to parse entity at " + locationOf(e));
        }
    }

    private void handleWriteError(Throwable error) {
        if (error instanceof HttpException exception) {
            context.respond(exception);
        } else {
            context.respond(HttpStatus.INTERNAL_SERVER_ERROR, error.getMessage());
        }
    }

    /**
     * Cross-reference check for Model writes. Strict mode aborts with HTTP 422 carrying a
     * {@code {"validationWarnings":[...]}} JSON body. Soft mode logs and proceeds — the next
     * merged-config rebuild's skip path records the entity in
     * {@link MergedConfigStore#getInvalidEntities()}.
     */
    private void checkCrossReferences(Model entity) {
        Config snapshot = mergedConfigStore.get();
        if (snapshot == null) {
            return;
        }
        List<ValidationWarning> warnings = new ArrayList<>();
        ConfigPostProcessor.validateCrossReferences(entity, snapshot, warnings);
        if (warnings.isEmpty()) {
            return;
        }
        if (softValidation) {
            log.warn("Soft-mode cross-ref warnings for model '{}': {}", path, warnings);
            return;
        }
        ObjectNode body = ProxyUtil.MAPPER.createObjectNode();
        ArrayNode arr = body.putArray("validationWarnings");
        for (ValidationWarning warning : warnings) {
            ObjectNode w = arr.addObject();
            w.put("field", warning.getField());
            w.put("message", warning.getMessage());
        }
        throw new HttpException(HttpStatus.UNPROCESSABLE_ENTITY, body.toString());
    }

    static String serializeForBlob(Object entity) {
        try {
            return ProxyUtil.BLOB_MAPPER.writeValueAsString(entity);
        } catch (JsonProcessingException e) {
            // writeValueAsString failures don't carry a useful JsonLocation and the message
            // can echo entity field values — surface only the entity class to keep the trail.
            throw new HttpException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to serialize entity of type " + entity.getClass().getSimpleName());
        }
    }

    private static String locationOf(JsonProcessingException e) {
        return e.getLocation() == null
                ? "unknown location"
                : "line " + e.getLocation().getLineNr() + ", column " + e.getLocation().getColumnNr();
    }

    private ObjectNode createNameEnvelope(String name) {
        ObjectNode body = ProxyUtil.MAPPER.createObjectNode();
        body.put("name", name);
        return body;
    }

    private ObjectNode listEnvelope(ArrayNode items) {
        ObjectNode body = ProxyUtil.MAPPER.createObjectNode();
        body.put("entityType", entityType);
        body.put("bucket", bucket);
        body.set("items", items);
        body.put("hasMore", false);
        return body;
    }

    private ObjectNode projectItem(Object item, String name, boolean fromApi, boolean includeSource, boolean revealSecrets) {
        // BLOB_MAPPER pass-through emits stored values verbatim — when in-memory Config holds the
        // post-rebuild plaintext, this surfaces the secret (security-admin reveal flow). The default
        // MAPPER applies the masking serializer modifier and emits "***" instead.
        ObjectMapper mapper = revealSecrets ? ProxyUtil.BLOB_MAPPER : ProxyUtil.MAPPER;
        ObjectNode node = mapper.valueToTree(item);
        node.put("name", name);
        node.put("status", "valid");
        if (includeSource) {
            node.put("source", fromApi ? "api" : "file");
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
        Class<?> entityClass = entityClassFor(entityType);
        // Invalid blobs may not have been decrypted (decryption_error reason) so the raw payload may
        // contain ENC[...] envelopes — masking is unconditional here regardless of revealSecrets.
        ObjectNode payload = entityClass == null
                ? (record.getPayload() instanceof ObjectNode raw ? raw.deepCopy() : null)
                : SecretFieldProcessor.maskInPayload(record.getPayload(), entityClass);
        if (payload != null) {
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
        return node;
    }

    private static Class<?> entityClassFor(String entityType) {
        return switch (entityType) {
            case "models" -> Model.class;
            case "interceptors" -> Interceptor.class;
            case "roles" -> Role.class;
            case "keys" -> Key.class;
            case "routes" -> Route.class;
            default -> null;
        };
    }

    private Future<?> respondMethodNotAllowed() {
        if (SETTINGS_TYPE.equals(entityType)) {
            context.putHeader("Allow", SETTINGS_ALLOW);
        }
        context.respond(HttpStatus.METHOD_NOT_ALLOWED, "Not implemented");
        return Future.succeededFuture();
    }

    private Future<?> respondWriteMethodNotAllowed() {
        // Default 405 with the eventual Allow set for entity types not yet in the write switch.
        context.putHeader("Allow", WRITE_ALLOW);
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

    private record WriteSpec(
            ResourceDescriptor descriptor,
            Class<?> entityClass,        // null for schemas (raw JSON-string body)
            boolean hasEncryptedFields,
            boolean isKey
    ) {}

}
