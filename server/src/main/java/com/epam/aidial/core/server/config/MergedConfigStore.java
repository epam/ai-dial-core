package com.epam.aidial.core.server.config;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.GlobalSettings;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Role;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.data.ResourceEvent;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Metrics;
import io.vertx.core.Vertx;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * {@link ConfigStore} implementation that builds the runtime {@link Config} as the
 * union of {@link FileConfigStore} and API-managed entities loaded from
 * {@link ResourceService}. Per design 02 §4: file entries keep simple-name keys
 * ("gpt-4"); API entries use canonical-ID keys ("models/public/gpt-4"). Both
 * coexist in the same {@code Config} maps.
 *
 * <p>Slice 2S.9 adds the invalid-entity sibling store. Per-entity failures route
 * through {@code onInvalidEntity} (default {@code abort}; opt-in {@code skip}).
 * Under {@code skip} the offender is removed from the merged {@code Config} and
 * recorded in {@link #getInvalidEntities()} for the listing/health/metrics
 * visibility channels (design 02 §4.1, §4.3).
 *
 * <p>Lifecycle: construct → {@link #init} (binds the file store, runs the explicit
 * initial rebuild, flips {@code initialized}) → {@link #requestRebuild} fires on
 * every subsequent file-poll callback (debounced 500 ms) and {@link #reload}
 * runs synchronously for the admin reload endpoint.
 */
@Slf4j
public final class MergedConfigStore implements ConfigStore {

    private static final long REBUILD_DEBOUNCE_MS = 500;
    private static final long NO_PENDING_TIMER = -1L;
    private static final String REASON_PARSE = "parse_error";
    private static final String REASON_VALIDATION = "validation_error";
    public static final String REASON_DECRYPTION = "decryption_error";

    public static final String MODE_ABORT = "abort";
    public static final String MODE_SKIP = "skip";

    private static final List<ResourceTypes> MANAGED_TYPES = List.of(
            ResourceTypes.MODEL,
            ResourceTypes.APP_TYPE_SCHEMA,
            ResourceTypes.INTERCEPTOR,
            ResourceTypes.ROLE,
            ResourceTypes.PROJECT_KEY,
            ResourceTypes.ROUTE);

    private final Vertx vertx;
    private final ResourceService resourceService;
    private final ApiKeyStore apiKeyStore;
    private final EntityLocationStrategy locationStrategy;
    private final SecretFieldProcessor secretFieldProcessor;
    private final String onInvalidEntity;
    private final boolean softValidation;
    private final String thisPodId;

    private FileConfigStore fileConfigStore;
    private volatile Config config;
    private volatile Map<ResourceTypes, Map<String, InvalidEntityRecord>> invalidEntities = Map.of();
    private volatile boolean settingsFromApi;
    private volatile boolean initialized;
    private final AtomicLong pendingRebuildTimerId = new AtomicLong(NO_PENDING_TIMER);
    private final Map<ResourceTypes, Map<String, Counter>> skipCounters = new EnumMap<>(ResourceTypes.class);

    public MergedConfigStore(Vertx vertx, ResourceService resourceService,
                             ApiKeyStore apiKeyStore, EntityLocationStrategy locationStrategy,
                             SecretFieldProcessor secretFieldProcessor,
                             String onInvalidEntity) {
        this(vertx, resourceService, apiKeyStore, locationStrategy, secretFieldProcessor, onInvalidEntity, false, "");
    }

    public MergedConfigStore(Vertx vertx, ResourceService resourceService,
                             ApiKeyStore apiKeyStore, EntityLocationStrategy locationStrategy,
                             SecretFieldProcessor secretFieldProcessor,
                             String onInvalidEntity,
                             boolean softValidation) {
        this(vertx, resourceService, apiKeyStore, locationStrategy, secretFieldProcessor, onInvalidEntity, softValidation, "");
    }

    public MergedConfigStore(Vertx vertx, ResourceService resourceService,
                             ApiKeyStore apiKeyStore, EntityLocationStrategy locationStrategy,
                             SecretFieldProcessor secretFieldProcessor,
                             String onInvalidEntity,
                             boolean softValidation,
                             String thisPodId) {
        this.vertx = vertx;
        this.resourceService = resourceService;
        this.apiKeyStore = apiKeyStore;
        this.locationStrategy = locationStrategy;
        this.secretFieldProcessor = secretFieldProcessor;
        this.onInvalidEntity = MODE_SKIP.equalsIgnoreCase(onInvalidEntity) ? MODE_SKIP : MODE_ABORT;
        this.softValidation = softValidation;
        this.thisPodId = thisPodId == null ? "" : thisPodId;

        Gauge.builder("dial_config_skipped_entities", this, MergedConfigStore::countInvalidEntities)
                .description("Number of entities skipped from in-memory Config (design 02 §4.1)")
                .register(Metrics.globalRegistry);

        for (ResourceTypes type : MANAGED_TYPES) {
            Map<String, Counter> perReason = new HashMap<>();
            for (String reason : List.of(REASON_PARSE, REASON_VALIDATION, REASON_DECRYPTION)) {
                perReason.put(reason, Counter.builder("dial_config_skip_events_total")
                        .description("Total number of entity skip events since pod start")
                        .tag("type", type.urlSegment())
                        .tag("reason", reason)
                        .register(Metrics.globalRegistry));
            }
            skipCounters.put(type, perReason);
        }
    }

    /**
     * Bind the file store and perform the explicit initial merged rebuild.
     * {@link FileConfigStore} fires its constructor-time {@code load(true)} before
     * any external callback can register, so the merged store seeds itself once
     * here; subsequent reloads flow through {@link #requestRebuild} via the
     * file store's {@code onReloadCallbacks} hook.
     */
    public synchronized void init(FileConfigStore fileConfigStore) {
        this.fileConfigStore = fileConfigStore;
        rebuild();
        initialized = true;
        resourceService.subscribeAllResources(this::onResourceEvent);
    }

    /**
     * Cross-replica rebuild trigger (design 02 §11.1). Filters self-events via
     * {@link #thisPodId} and other-pod events for non-{@link #MANAGED_TYPES} resources;
     * any survivor enqueues a debounced {@link #requestRebuild()}. Malformed or
     * encrypted-bucket URLs (which {@link ResourceDescriptorFactory#fromAnyUrl}
     * rejects without an encryption service) are silently dropped — none of them
     * carry MANAGED_TYPES content.
     */
    private void onResourceEvent(ResourceEvent event) {
        String senderPodId = event.getSenderPodId();
        if (senderPodId != null && senderPodId.equals(thisPodId)) {
            return;
        }
        ResourceDescriptor descriptor;
        try {
            descriptor = ResourceDescriptorFactory.fromAnyUrl(event.getUrl(), null);
        } catch (Exception parseError) {
            log.debug("Ignoring resource event with unparseable URL: {}", event.getUrl(), parseError);
            return;
        }
        if (!MANAGED_TYPES.contains(descriptor.getType())
                && descriptor.getType() != ResourceTypes.GLOBAL_SETTINGS) {
            return;
        }
        requestRebuild();
    }

    @Override
    public Config get() {
        return config;
    }

    @Override
    public synchronized Config reload() {
        fileConfigStore.reload();
        // fileConfigStore.reload() fires onReloadCallbacks → requestRebuild(), which schedules a
        // 500ms debounce timer. Cancel it: the rebuild() below produces the authoritative merged
        // config and a debounced rerun would be a redundant addProjectKeys + listResources sweep.
        cancelPendingRebuild();
        return rebuild();
    }

    /**
     * In-memory derived state; never persisted. Empty map per type when no
     * entities are skipped. Returned as an unmodifiable view.
     */
    public Map<ResourceTypes, Map<String, InvalidEntityRecord>> getInvalidEntities() {
        return invalidEntities;
    }

    /** Currently-effective failure mode: {@link #MODE_ABORT} or {@link #MODE_SKIP}. */
    public String getOnInvalidEntity() {
        return onInvalidEntity;
    }

    /**
     * Whether the {@code globalInterceptors} / {@code retriableErrorCodes} fields in the
     * current {@link Config} were sourced from the API-managed settings singleton blob
     * ({@code platform/settings/global}) rather than from the file-defined defaults.
     * Drives the {@code source} label in the settings GET projection (design 03 §1).
     */
    public boolean isSettingsFromApi() {
        return settingsFromApi;
    }

    /**
     * Soft-validation mode for write controllers (slice 2S.13). When {@code true},
     * cross-reference violations on POST/PUT log a warning and proceed with the write;
     * the next merged-config rebuild's skip path records the entity in
     * {@link #getInvalidEntities()}. When {@code false} (strict mode), violations
     * abort the write with HTTP 422.
     */
    public boolean isSoftValidation() {
        return softValidation;
    }

    /**
     * Exposes the {@link SecretFieldProcessor} used during rebuilds so write controllers can
     * apply the same encryption/merge semantics as the rebuild pipeline (design 04 §2).
     */
    public SecretFieldProcessor getSecretFieldProcessor() {
        return secretFieldProcessor;
    }

    /**
     * Non-blocking, 500 ms trailing-edge debounced rebuild trigger. No-op until
     * {@link #init} has completed — pre-init triggers are subsumed by the
     * explicit initial rebuild that {@code init} performs.
     *
     * <p>Runs on the Vert.x event loop (topic event handler and the file-store reload callback
     * both fire there); must remain non-blocking. {@link #rebuildNow} on a worker thread holds the
     * intrinsic monitor for the full rebuild duration (tens to hundreds of ms), so this path uses
     * an {@link AtomicLong} CAS on {@link #pendingRebuildTimerId} instead of {@code synchronized}
     * — only the inner {@code executeBlocking} body acquires the monitor, on a worker thread.
     */
    public void requestRebuild() {
        if (!initialized) {
            return;
        }
        long newTimerId = vertx.setTimer(REBUILD_DEBOUNCE_MS, this::onRebuildTimerFire);
        long previous = pendingRebuildTimerId.getAndSet(newTimerId);
        if (previous != NO_PENDING_TIMER) {
            vertx.cancelTimer(previous);
        }
    }

    private void onRebuildTimerFire(long firingId) {
        // Bail out if we were superseded between scheduling and firing — the replacement timer
        // will run (or already has). Belt-and-suspenders against Vert.x's best-effort cancelTimer.
        if (!pendingRebuildTimerId.compareAndSet(firingId, NO_PENDING_TIMER)) {
            return;
        }
        vertx.<Config>executeBlocking(() -> {
            synchronized (this) {
                return rebuild();
            }
        }, false).onFailure(error -> log.warn("Failed to rebuild merged config", error));
    }

    /**
     * Synchronous, debounce-bypassing rebuild for the API write path on the writer pod
     * (design 02 §4). Cancels any pending debounced rebuild then runs the merge inline
     * on the calling thread; does NOT re-read the file (the API write does not touch
     * on-disk config). Only call after {@link #init} has completed and from off-the-event-loop
     * threads (e.g., inside {@code taskExecutor.submit(...)}).
     *
     * <p>Keys-controller ordering invariant (3S.2): on DELETE, the correct sequence is
     * delete blob → {@code apiKeyStore.removeKey(secret)} → {@code rebuildNow()}, ensuring
     * the key is absent from {@link com.epam.aidial.core.server.security.ApiKeyStore} before
     * the new {@link Config} becomes visible.
     */
    public synchronized Config rebuildNow() {
        cancelPendingRebuild();
        return rebuild();
    }

    private void cancelPendingRebuild() {
        long previous = pendingRebuildTimerId.getAndSet(NO_PENDING_TIMER);
        if (previous != NO_PENDING_TIMER) {
            vertx.cancelTimer(previous);
        }
    }

    private Config rebuild() {
        Config base = fileConfigStore.get();
        // Shallow-clone of the file-derived Config: maps that the post-processor or this rebuild
        // mutates (routes via sortRoutes; toolsets via per-entity removal; maps that we add API
        // entries to) get fresh copies so the file store's Config stays untouched. Map values
        // (Model, Interceptor, Role, Route, ToolSet) are shared instances — setName is idempotent
        // for already-named file entries.
        Config merged = new Config();
        Map<String, Model> models = new LinkedHashMap<>(base.getModels());
        Map<String, Interceptor> interceptors = new LinkedHashMap<>(base.getInterceptors());
        Map<String, Role> roles = new HashMap<>(base.getRoles());
        Map<String, Key> keys = new HashMap<>(base.getKeys());
        LinkedHashMap<String, Route> routes = new LinkedHashMap<>(base.getRoutes());
        Map<String, String> schemas = new LinkedHashMap<>(base.getApplicationTypeSchemas());
        merged.setApplications(base.getApplications());
        merged.setToolsets(new LinkedHashMap<>(base.getToolsets()));
        merged.setRetriableErrorCodes(base.getRetriableErrorCodes());
        merged.setGlobalInterceptors(base.getGlobalInterceptors());

        Map<String, JsonNode> blobBodies = new HashMap<>();
        Map<ResourceTypes, Map<String, InvalidEntityRecord>> pendingInvalid = new EnumMap<>(ResourceTypes.class);

        for (ResourceTypes type : MANAGED_TYPES) {
            for (String scope : locationStrategy.listScopes(type)) {
                String bucket = locationStrategy.resolveBucket(type, scope);
                if (bucket == null) {
                    continue;
                }
                String bucketLocation = bucket + ResourceDescriptor.PATH_SEPARATOR;
                ResourceDescriptor folder = ResourceDescriptorFactory.fromDecoded(type, bucket, bucketLocation, "");
                List<Pair<ResourceItemMetadata, String>> items =
                        resourceService.listResources(folder, ignored -> { });

                for (Pair<ResourceItemMetadata, String> item : items) {
                    String name = item.getKey().getName();
                    String body = item.getValue();
                    if (body == null) {
                        continue;
                    }
                    String canonicalId = canonicalId(type, bucket, name);
                    JsonNode node;
                    try {
                        // Parse once into JsonNode; reused below for typed deserialization and as the
                        // payload echoed back through the invalid-entity sibling store on semantic failures.
                        node = ProxyUtil.BLOB_MAPPER.readTree(body);
                    } catch (Exception parseError) {
                        recordInvalid(pendingInvalid, type, canonicalId, name,
                                "JSON parse failure: " + parseError.getMessage(),
                                List.of(new ValidationWarning("body", parseError.getMessage())),
                                null, REASON_PARSE, "api");
                        continue;
                    }

                    ResourceDescriptor descriptor = ResourceDescriptorFactory.fromDecoded(
                            type, bucket, bucketLocation, name);
                    AddedEntity added;
                    try {
                        added = addBlobEntity(type, canonicalId, node,
                                models, interceptors, roles, keys, routes, schemas);
                    } catch (Exception parseError) {
                        recordInvalid(pendingInvalid, type, canonicalId, name,
                                "JSON parse failure: " + parseError.getMessage(),
                                List.of(new ValidationWarning("body", parseError.getMessage())),
                                node, REASON_PARSE, "api");
                        continue;
                    }

                    if (added != null && added.entity() != null) {
                        try {
                            secretFieldProcessor.decryptFields(added.entity(), descriptor);
                        } catch (Exception decryptError) {
                            // Roll back the partial insertion so decryption-failure entities never
                            // reach addProjectKeys (locked 2S.9 invariant).
                            removeAddedEntity(type, canonicalId, models, interceptors, roles, keys, routes, schemas);
                            recordInvalid(pendingInvalid, type, canonicalId, name,
                                    "Decryption failure: " + decryptError.getMessage(),
                                    List.of(new ValidationWarning("body", decryptError.getMessage())),
                                    node, REASON_DECRYPTION, "api");
                            continue;
                        }
                    }
                    blobBodies.put(canonicalId, node);
                }
            }
        }

        merged.setModels(models);
        merged.setInterceptors(interceptors);
        merged.setRoles(roles);
        merged.setKeys(keys);
        merged.setRoutes(routes);
        merged.setApplicationTypeSchemas(schemas);

        // Semantic pass — under MODE_SKIP, route per-entity violations to invalidEntities and
        // continue; under MODE_ABORT, the post-processor throws and the rebuild aborts (this.config
        // stays at the previous value because we only swap below).
        BiConsumer<ResourceTypes, InvalidEntityException> onSkip = MODE_SKIP.equals(onInvalidEntity)
                ? (type, error) -> {
                    // Only MANAGED_TYPES surface through the invalidEntities sibling store (design 02 §4.3
                    // layered model). APPLICATION and TOOL_SET use lazy validation in 3S.1, not this path.
                    if (!MANAGED_TYPES.contains(type)) {
                        log.warn("Skipped {} '{}' from merged Config: {}", type.urlSegment(),
                                error.getMapKey(), error.getMessage());
                        return;
                    }
                    String mapKey = error.getMapKey();
                    boolean fromApi = mapKey.contains("/");
                    String canonicalId = fromApi
                            ? mapKey
                            : canonicalId(type, locationStrategy.resolveBucket(type, EntityLocationStrategy.PLATFORM_SCOPE), mapKey);
                    String simpleName = fromApi ? lastSegment(mapKey) : mapKey;
                    recordInvalid(pendingInvalid, type, canonicalId, simpleName,
                            error.getMessage(), error.getWarnings(), blobBodies.get(canonicalId),
                            REASON_VALIDATION, fromApi ? "api" : "file");
                }
                : null;
        ConfigPostProcessor.processSemantic(merged, apiKeyStore, onSkip);

        // ConfigPostProcessor sets entity.name = mapKey: canonical ID for API entries
        // ("models/public/foo"), simple name for file entries ("gpt-4"). This is the OQ-23 contract:
        // canonical IDs surface on legacy /openai/models, /openai/deployments, and rate-limit
        // role-limit lookups for API-managed deployments. The new admin Configuration API listing
        // controller projects simpleName(mapKey) independently per design 03 §4.

        Map<ResourceTypes, Map<String, InvalidEntityRecord>> finalInvalid =
                pendingInvalid.isEmpty() ? Map.of() : Collections.unmodifiableMap(pendingInvalid);
        boolean overlayFromApi = applySettingsOverlay(merged);
        this.config = merged;
        this.invalidEntities = finalInvalid;
        this.settingsFromApi = overlayFromApi;
        return merged;
    }

    /**
     * Singleton overlay (design 02 §4): when the API-managed settings blob exists at
     * {@code platform/settings/global}, its fields replace the file-derived
     * {@code globalInterceptors} / {@code retriableErrorCodes} on the merged {@link Config}.
     * GlobalSettings is intentionally NOT in {@link #MANAGED_TYPES} — it is a singleton overlay,
     * not a union-by-key like other types. Returns {@code true} iff the blob is present.
     */
    private boolean applySettingsOverlay(Config merged) {
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.GLOBAL_SETTINGS, ResourceDescriptor.PLATFORM_BUCKET,
                ResourceDescriptor.PLATFORM_LOCATION, "global");
        String body = resourceService.getResource(descriptor);
        if (body == null) {
            return false;
        }
        try {
            GlobalSettings settings = ProxyUtil.BLOB_MAPPER.readValue(body, GlobalSettings.class);
            merged.setGlobalInterceptors(
                    settings.getGlobalInterceptors() == null ? List.of() : settings.getGlobalInterceptors());
            merged.setRetriableErrorCodes(
                    settings.getRetriableErrorCodes() == null ? Set.of() : settings.getRetriableErrorCodes());
            return true;
        } catch (Exception parseError) {
            log.warn("Failed to parse settings singleton blob", parseError);
            return false;
        }
    }

    private static int countInvalidEntities(MergedConfigStore self) {
        int total = 0;
        for (Map<String, InvalidEntityRecord> perType : self.invalidEntities.values()) {
            total += perType.size();
        }
        return total;
    }

    private void recordInvalid(Map<ResourceTypes, Map<String, InvalidEntityRecord>> invalid,
                               ResourceTypes type, String canonicalId, String simpleName,
                               String reason, List<ValidationWarning> warnings,
                               JsonNode payload, String reasonClass, String source) {
        InvalidEntityRecord record = new InvalidEntityRecord(simpleName, canonicalId, reason,
                List.copyOf(warnings), source, payload);
        invalid.computeIfAbsent(type, k -> new HashMap<>()).put(canonicalId, record);
        skipCounters.get(type).get(reasonClass).increment();
        log.warn("Skipped {} '{}' from merged Config: {}", type.urlSegment(), canonicalId, reason);
    }

    private static String lastSegment(String key) {
        int slash = key.lastIndexOf('/');
        return slash < 0 ? key : key.substring(slash + 1);
    }

    static String canonicalId(ResourceTypes type, String bucket, String name) {
        return type.urlSegment() + ResourceDescriptor.PATH_SEPARATOR + bucket + ResourceDescriptor.PATH_SEPARATOR + name;
    }

    private static AddedEntity addBlobEntity(ResourceTypes type, String canonicalId, JsonNode node,
                                             Map<String, Model> models, Map<String, Interceptor> interceptors,
                                             Map<String, Role> roles, Map<String, Key> keys,
                                             LinkedHashMap<String, Route> routes, Map<String, String> schemas)
            throws JsonProcessingException {
        switch (type) {
            case MODEL -> {
                Model entity = ProxyUtil.BLOB_MAPPER.treeToValue(node, Model.class);
                warnIfReplaced(type, canonicalId, models.put(canonicalId, entity));
                return new AddedEntity(entity);
            }
            case INTERCEPTOR -> {
                Interceptor entity = ProxyUtil.BLOB_MAPPER.treeToValue(node, Interceptor.class);
                warnIfReplaced(type, canonicalId, interceptors.put(canonicalId, entity));
                return new AddedEntity(entity);
            }
            case ROLE -> {
                Role entity = ProxyUtil.BLOB_MAPPER.treeToValue(node, Role.class);
                warnIfReplaced(type, canonicalId, roles.put(canonicalId, entity));
                return new AddedEntity(entity);
            }
            case PROJECT_KEY -> {
                Key entity = ProxyUtil.BLOB_MAPPER.treeToValue(node, Key.class);
                warnIfReplaced(type, canonicalId, keys.put(canonicalId, entity));
                return new AddedEntity(entity);
            }
            case ROUTE -> {
                Route entity = ProxyUtil.BLOB_MAPPER.treeToValue(node, Route.class);
                warnIfReplaced(type, canonicalId, routes.put(canonicalId, entity));
                return new AddedEntity(entity);
            }
            case APP_TYPE_SCHEMA -> {
                warnIfReplaced(type, canonicalId, schemas.put(canonicalId, node.toString()));
                return null;
            }
            default -> {
                /* GLOBAL_SETTINGS is a singleton — design 02 §4 leaves union-by-key out of scope. */
                return null;
            }
        }
    }

    private static void warnIfReplaced(ResourceTypes type, String canonicalId, Object previous) {
        if (previous != null) {
            log.warn("Duplicate canonical ID during merged Config rebuild: {} '{}' overwrote a prior entry",
                    type.urlSegment(), canonicalId);
        }
    }

    private static void removeAddedEntity(ResourceTypes type, String canonicalId,
                                          Map<String, Model> models, Map<String, Interceptor> interceptors,
                                          Map<String, Role> roles, Map<String, Key> keys,
                                          LinkedHashMap<String, Route> routes, Map<String, String> schemas) {
        switch (type) {
            case MODEL -> models.remove(canonicalId);
            case INTERCEPTOR -> interceptors.remove(canonicalId);
            case ROLE -> roles.remove(canonicalId);
            case PROJECT_KEY -> keys.remove(canonicalId);
            case ROUTE -> routes.remove(canonicalId);
            case APP_TYPE_SCHEMA -> schemas.remove(canonicalId);
            default -> { /* no-op */ }
        }
    }

    private record AddedEntity(Object entity) { }
}
