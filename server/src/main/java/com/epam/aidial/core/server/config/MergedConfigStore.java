package com.epam.aidial.core.server.config;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.GlobalSettings;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Role;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsEncryptionService;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.service.ExternalServiceService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.data.ResourceEvent;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Metrics;
import io.vertx.core.Vertx;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * {@link ConfigStore} implementation that builds the runtime {@link Config} as the
 * union of {@link FileConfigStore} and API-managed entities loaded from
 * {@link ResourceService}. Per design 02 §4: file entries keep simple-name keys
 * ("gpt-4"); API entries use canonical-ID keys ("models/platform/gpt-4"). Both
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
            ResourceTypes.CATALOG_SCHEMA,
            ResourceTypes.INTERCEPTOR,
            ResourceTypes.ROLE,
            ResourceTypes.PROJECT_KEY,
            ResourceTypes.ROUTE,
            ResourceTypes.APPLICATION,
            ResourceTypes.TOOL_SET);

    private static final Set<String> MANAGED_URL_SEGMENTS = managedUrlSegments();

    private static Set<String> managedUrlSegments() {
        Set<String> segments = new HashSet<>();
        for (ResourceTypes type : MANAGED_TYPES) {
            segments.add(type.urlSegment());
        }
        segments.add(ResourceTypes.GLOBAL_SETTINGS.urlSegment());
        return Set.copyOf(segments);
    }

    /**
     * Bucket locations the admin write/read paths serialize on cluster-wide via
     * {@link LockService#underBucketLocks}. The write controllers
     * ({@code ConfigResourceController}, {@code AdminApplyController}) hold these around blob
     * puts/deletes; this store holds them around the full-rebuild blob scan in
     * {@link #reload}/{@link #rebuildNow}/the debounced timer-fire so a peer pod's mid-batch
     * apply cannot leak partial state into the scan (design 02 §4.4 cross-pod dirty read).
     *
     * <p>The constant is shared so reader and writer lock-key sets stay byte-identical — divergent
     * sets would silently fail to serialize across the boundary. {@link #init} (startup) and the
     * replica fast path ({@link #applyReplicaEvent}) deliberately do NOT take these outer bucket
     * locks. The replica path instead holds the inner {@link #rebuildLock} across its whole
     * snapshot-plus-mutation sequence (one atomic critical section per replica event), so a
     * concurrent rebuild cannot interleave between the prior-secret snapshot and the
     * {@link ApiKeyStore} mutations; the {@code apply*} methods re-enter that same reentrant lock.
     */
    public static final List<String> ADMIN_BUCKET_LOCATIONS = List.of(
            ResourceDescriptor.PUBLIC_LOCATION,
            ResourceDescriptor.PLATFORM_LOCATION);

    private final Vertx vertx;
    private final AsyncTaskExecutor taskExecutor;
    private final ResourceService resourceService;
    private final ApiKeyStore apiKeyStore;
    private final EntityLocationStrategy locationStrategy;
    private final SecretFieldProcessor secretFieldProcessor;
    private final ExternalServiceService externalServiceService;
    private final ResourceAuthSettingsEncryptionService resourceAuthSettingsEncryptionService;
    private final LockService lockService;
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
    // Serializes rebuild() across init/reload/rebuildNow/timer-fire callsites. ReentrantLock
    // (vs. synchronized) lets virtual threads park instead of pin their carrier while waiting —
    // rebuild() does blob-storage IO that can take tens to hundreds of ms. Shared with
    // ApiKeyStore (its mutation lock) so per-entry key point-writes serialize against the entire
    // rebuild (scan → addProjectKeys → swap), closing the lost-update window where a point-write
    // would land in the orphaned pre-swap map (FINDING #1).
    private final ReentrantLock rebuildLock;

    public MergedConfigStore(Vertx vertx, AsyncTaskExecutor taskExecutor, ResourceService resourceService,
                             ApiKeyStore apiKeyStore, EntityLocationStrategy locationStrategy,
                             SecretFieldProcessor secretFieldProcessor,
                             LockService lockService,
                             String onInvalidEntity,
                             ExternalServiceService externalServiceService,
                             ResourceAuthSettingsEncryptionService resourceAuthSettingsEncryptionService) {
        this(vertx, taskExecutor, resourceService, apiKeyStore, locationStrategy, secretFieldProcessor,
                lockService, onInvalidEntity, false, "", externalServiceService, resourceAuthSettingsEncryptionService);
    }

    public MergedConfigStore(Vertx vertx, AsyncTaskExecutor taskExecutor, ResourceService resourceService,
                             ApiKeyStore apiKeyStore, EntityLocationStrategy locationStrategy,
                             SecretFieldProcessor secretFieldProcessor,
                             LockService lockService,
                             String onInvalidEntity,
                             boolean softValidation,
                             ExternalServiceService externalServiceService,
                             ResourceAuthSettingsEncryptionService resourceAuthSettingsEncryptionService) {
        this(vertx, taskExecutor, resourceService, apiKeyStore, locationStrategy, secretFieldProcessor,
                lockService, onInvalidEntity, softValidation, "", externalServiceService, resourceAuthSettingsEncryptionService);
    }

    public MergedConfigStore(Vertx vertx, AsyncTaskExecutor taskExecutor, ResourceService resourceService,
                             ApiKeyStore apiKeyStore, EntityLocationStrategy locationStrategy,
                             SecretFieldProcessor secretFieldProcessor,
                             LockService lockService,
                             String onInvalidEntity,
                             boolean softValidation,
                             String thisPodId,
                             ExternalServiceService externalServiceService,
                             ResourceAuthSettingsEncryptionService resourceAuthSettingsEncryptionService) {
        this.vertx = vertx;
        this.taskExecutor = taskExecutor;
        this.resourceService = resourceService;
        this.apiKeyStore = Objects.requireNonNull(apiKeyStore, "apiKeyStore must not be null");
        // Adopt ApiKeyStore's mutation lock so rebuild and key point-writes serialize on the same monitor.
        this.rebuildLock = Objects.requireNonNull(apiKeyStore.getMutationLock(),
                "apiKeyStore.getMutationLock() must not be null (mock fixtures must stub it with a real ReentrantLock)");
        this.locationStrategy = locationStrategy;
        this.secretFieldProcessor = secretFieldProcessor;
        this.externalServiceService = externalServiceService;
        this.resourceAuthSettingsEncryptionService = resourceAuthSettingsEncryptionService;
        this.lockService = lockService;
        this.onInvalidEntity = MODE_SKIP.equalsIgnoreCase(onInvalidEntity) ? MODE_SKIP : MODE_ABORT;
        this.softValidation = softValidation;
        this.thisPodId = thisPodId == null ? "" : thisPodId;

        Gauge.builder("dial_config_skipped_entities", this, MergedConfigStore::countInvalidEntities)
                .description("Number of entities skipped from in-memory Config (design 02 §4.1)")
                .register(Metrics.globalRegistry);

        // GLOBAL_SETTINGS is not in MANAGED_TYPES (it's a singleton overlay, not a union-by-key),
        // but its parse failures still surface through recordInvalid → skipCounters and the
        // invalidEntities sibling store, so its counter slot must be pre-registered too.
        List<ResourceTypes> typesForCounters = new ArrayList<>(MANAGED_TYPES);
        typesForCounters.add(ResourceTypes.GLOBAL_SETTINGS);
        for (ResourceTypes type : typesForCounters) {
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
     * Returns the file-sourced {@link Config} as last loaded by {@link FileConfigStore},
     * with no API-managed overlay applied. Used by the {@code /v1/admin/config/file/*}
     * inspection surface to project file/default values for the singleton settings
     * (which the API blob would otherwise shadow in the merged {@code Config}).
     */
    public Config getFileSourcedConfig() {
        return fileConfigStore == null ? null : fileConfigStore.get();
    }

    /**
     * Bind the file store and perform the explicit initial merged rebuild.
     * {@link FileConfigStore} fires its constructor-time {@code load(true)} before
     * any external callback can register, so the merged store seeds itself once
     * here; subsequent reloads flow through {@link #requestRebuild} via the
     * file store's {@code onReloadCallbacks} hook.
     */
    public void init(FileConfigStore fileConfigStore) {
        rebuildLock.lock();
        try {
            this.fileConfigStore = fileConfigStore;
            rebuild();
            initialized = true;
            resourceService.subscribeAllResources(this::onResourceEvent);
        } finally {
            rebuildLock.unlock();
        }
    }

    /**
     * Cross-replica fast-path entry (design 02 §4 — slice 4S.5). Filters self-events via
     * {@link #thisPodId} and other-pod events for non-{@link #MANAGED_TYPES} resources;
     * any survivor is dispatched off-callback-thread onto {@link #taskExecutor} which
     * invokes {@link #applyReplicaEvent} (fetch the body, decrypt, single-entity mutate).
     * Malformed or encrypted-bucket URLs (which {@link ResourceDescriptorFactory#fromAnyUrl}
     * rejects without an encryption service) are silently dropped — none of them carry
     * MANAGED_TYPES content. Dispatch failures fall back to {@link #requestRebuild()}.
     */
    private void onResourceEvent(ResourceEvent event) {
        String senderPodId = event.getSenderPodId();
        if (senderPodId != null && senderPodId.equals(thisPodId)) {
            return;
        }
        if (!isManagedEventUrl(event.getUrl())) {
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
        // MANAGED_TYPES gates the type but not the bucket, whereas rebuild() only scans the platform
        // bucket (via locationStrategy). Scope the replica fast path the same way: APPLICATION/TOOL_SET
        // (and, harmlessly, the other managed types) also resolve for the public bucket, so without
        // this a public-bucket app/toolset publication event would be applied into the merged Config on
        // peer pods — leaking a public resource into deployment/short-name resolution until the next
        // platform-only rebuild.
        ResourceTypes managedType = (ResourceTypes) descriptor.getType();
        String managedBucket = locationStrategy.resolveBucket(managedType, EntityLocationStrategy.PLATFORM_SCOPE);
        if (managedBucket == null || !managedBucket.equals(descriptor.getBucketName())) {
            return;
        }
        ResourceEvent.Action action = event.getAction();
        Map<String, String> eventMetadata = event.getMetadata();
        taskExecutor.submit(() -> {
            applyReplicaEvent(descriptor, action, eventMetadata);
            return null;
        }).onFailure(error -> {
            log.warn("Replica event dispatch failed for {}; falling back to full rebuild",
                    descriptor.getUrl(), error);
            requestRebuild();
        });
    }

    /**
     * Cheap firehose pre-filter: event URLs carry the unencoded {@code type.urlSegment()} as their
     * first path segment ({@link ResourceDescriptor#getUrl} encodes it via
     * {@code UrlUtil.encodePathSegment}, and all managed segments are {@code [a-z_]} so are never
     * percent-encoded). Drops the vast majority of non-managed events (conversations, prompts,
     * files, …) before the {@link ResourceDescriptorFactory#fromAnyUrl} parse runs.
     */
    static boolean isManagedEventUrl(String url) {
        if (url == null) {
            return false;
        }
        int slash = url.indexOf('/');
        if (slash <= 0) {
            return false;
        }
        return MANAGED_URL_SEGMENTS.contains(url.substring(0, slash));
    }

    /**
     * Cross-replica partial-update fast path (slice 4S.5). Mirrors the writer-pod ordering
     * invariants: PROJECT_KEY DELETE snapshots the plaintext secret from the current
     * {@link Config#getKeys()} before {@link #applyEntityDelete} (which builds a new Config
     * without the entry), then calls {@link ApiKeyStore#removeKey}; PROJECT_KEY CREATE/UPDATE
     * decrypts and calls {@link ApiKeyStore#addOrUpdateKey} BEFORE {@link #applyEntityWrite}.
     * GLOBAL_SETTINGS routes to {@link #applySettingsWrite} / {@link #applySettingsDelete}.
     * APP_TYPE_SCHEMA bypasses {@code decryptFields} — schemas are raw JSON strings, not POJOs
     * with {@code @EncryptedField} annotations. A null body on CREATE/UPDATE means the writer
     * deleted the entity before this replica could fetch it (pub/sub race) and is treated as
     * DELETE. Any exception falls back to {@link #requestRebuild()} — the polling SLA covers
     * eventual convergence even if the fast path bails.
     *
     * <p>Package-private for direct test invocation; production callsite is
     * {@link #onResourceEvent} via {@link AsyncTaskExecutor#submit}.
     */
    void applyReplicaEvent(ResourceDescriptor descriptor, ResourceEvent.Action action,
                           @Nullable Map<String, String> eventMetadata) {
        try {
            ResourceTypes type = (ResourceTypes) descriptor.getType();
            boolean isSchema = type == ResourceTypes.APP_TYPE_SCHEMA || type == ResourceTypes.CATALOG_SCHEMA;

            if (action == ResourceEvent.Action.DELETE) {
                applyReplicaEventDelete(type, descriptor, isSchema, eventMetadata);
                return;
            }
            String body = resourceService.getResource(descriptor);
            if (body == null) {
                // Body race: writer deleted the resource before this replica fetched it.
                applyReplicaEventDelete(type, descriptor, isSchema, eventMetadata);
                return;
            }
            JsonNode node = ProxyUtil.BLOB_MAPPER.readTree(body);
            // Blob fetch + readTree above stay OUTSIDE the lock (IO). Everything that reads or
            // mutates shared in-memory state — the GLOBAL_SETTINGS dispatch, entity deserialization,
            // the prior-secret snapshot, and the ApiKeyStore/Config mutations — runs under a single
            // rebuildLock critical section so a concurrent rebuild cannot interleave between the
            // snapshot and the mutations (FINDING #2). The apply*/addOrUpdateKey/removeKey calls
            // re-enter the same reentrant lock.
            rebuildLock.lock();
            try {
                if (type == ResourceTypes.GLOBAL_SETTINGS) {
                    GlobalSettings settings = ProxyUtil.BLOB_MAPPER.treeToValue(node, GlobalSettings.class);
                    applySettingsWrite(settings);
                    return;
                }
                Object entity = deserializeReplicaEntity(type, node);
                if (!(entity instanceof String)) {
                    decryptManagedEntity(type, entity, descriptor);
                }
                String mapKey = resolveReplicaMapKey(descriptor, node, isSchema, eventMetadata);
                // Evict the old $id when a schema was updated with a different $id.
                if (isSchema && eventMetadata != null) {
                    String oldSchemaId = eventMetadata.get("old_$id");
                    if (oldSchemaId != null && !oldSchemaId.equals(mapKey)) {
                        applyEntityDeleteLocked(type, oldSchemaId);
                    }
                }
                if (type == ResourceTypes.PROJECT_KEY) {
                    Key key = (Key) entity;
                    String secret = key.getKey();
                    // Snapshot the prior secret BEFORE applyEntityWrite swaps the config (mirrors
                    // applyReplicaDelete). On rotation (secret changed) the old auth bearer must be
                    // revoked so the previous secret no longer authenticates (FINDING #2).
                    Config snapshot = this.config;
                    Key prior = snapshot == null ? null : snapshot.getKeys().get(mapKey);
                    String oldSecret = prior == null ? null : prior.getKey();
                    if (secret != null && !secret.isBlank()) {
                        ApiKeyData data = new ApiKeyData();
                        data.setOriginalKey(key);
                        apiKeyStore.addOrUpdateKey(secret, data);
                    }
                    if (oldSecret != null && !oldSecret.isBlank() && !oldSecret.equals(secret)) {
                        apiKeyStore.removeKey(oldSecret);
                    }
                }
                applyEntityWrite(type, mapKey, entity);
            } finally {
                rebuildLock.unlock();
            }
        } catch (Exception error) {
            log.warn("Failed to apply replica event for {}; falling back to full rebuild",
                    descriptor.getUrl(), error);
            requestRebuild();
        }
    }

    private void applyReplicaEventDelete(ResourceTypes type, ResourceDescriptor descriptor,
                                         boolean isSchema, @Nullable Map<String, String> eventMetadata) {
        if (isSchema) {
            applyReplicaSchemaDelete(type, eventMetadata);
        } else {
            applyReplicaDelete(type, mapKeyFor(descriptor));
        }
    }

    /**
     * Handles a schema-type replica delete, using the {@code $id} carried in {@code eventMetadata}
     * when available. Falls back to a full rebuild when the metadata is absent — the blob body is
     * gone at delete time so the map key cannot be derived any other way.
     */
    private void applyReplicaSchemaDelete(ResourceTypes type, @Nullable Map<String, String> eventMetadata) {
        String schemaId = eventMetadata != null ? eventMetadata.get("$id") : null;
        if (schemaId == null) {
            requestRebuild();
            return;
        }
        applyReplicaDelete(type, schemaId);
    }

    private void applyReplicaDelete(ResourceTypes type, String mapKey) {
        if (type == ResourceTypes.GLOBAL_SETTINGS) {
            applySettingsDelete();
            return;
        }
        // Hold rebuildLock across snapshot → removeKey → applyEntityDelete so a concurrent rebuild
        // cannot interleave between the plaintext-secret snapshot and the mutations and resurrect
        // the deleted key into the ApiKeyStore (FINDING #2). removeKey/applyEntityDelete re-enter
        // the same reentrant lock.
        rebuildLock.lock();
        try {
            if (type == ResourceTypes.PROJECT_KEY) {
                Config snapshot = this.config;
                Key existing = snapshot == null ? null : snapshot.getKeys().get(mapKey);
                String secret = existing == null ? null : existing.getKey();
                if (secret != null && !secret.isBlank()) {
                    apiKeyStore.removeKey(secret);
                }
            }
            applyEntityDelete(type, mapKey);
        } finally {
            rebuildLock.unlock();
        }
    }

    private static Object deserializeReplicaEntity(ResourceTypes type, JsonNode node)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        return switch (type) {
            case MODEL -> ProxyUtil.BLOB_MAPPER.treeToValue(node, Model.class);
            case INTERCEPTOR -> ProxyUtil.BLOB_MAPPER.treeToValue(node, Interceptor.class);
            case ROLE -> ProxyUtil.BLOB_MAPPER.treeToValue(node, Role.class);
            case PROJECT_KEY -> ProxyUtil.BLOB_MAPPER.treeToValue(node, Key.class);
            case ROUTE -> ProxyUtil.BLOB_MAPPER.treeToValue(node, Route.class);
            case APPLICATION -> ProxyUtil.BLOB_MAPPER.treeToValue(node, Application.class);
            case TOOL_SET -> ProxyUtil.BLOB_MAPPER.treeToValue(node, ToolSet.class);
            case APP_TYPE_SCHEMA, CATALOG_SCHEMA -> node.toString();
            default -> throw new IllegalArgumentException("Unsupported replica type: " + type);
        };
    }

    /**
     * Decrypts a managed entity read from blob storage before it enters the merged {@link Config}.
     * {@code APPLICATION}/{@code TOOL_SET} secrets are not {@code @EncryptedField}-annotated — they
     * are encrypted per-resource by their own write paths ({@link ExternalServiceService}/
     * {@link ResourceAuthSettingsEncryptionService}), keyed by the entity's own bucket — so they
     * must be decrypted the same way here rather than via {@link SecretFieldProcessor}, which is a
     * no-op for these two types (no {@code @EncryptedField} fields). Every other managed type keeps
     * going through {@link SecretFieldProcessor}.
     */
    private void decryptManagedEntity(ResourceTypes type, Object entity, ResourceDescriptor descriptor) {
        switch (type) {
            case APPLICATION -> externalServiceService.decryptSecrets(descriptor, (Application) entity);
            case TOOL_SET -> resourceAuthSettingsEncryptionService.decrypt(descriptor.getUrl(),
                    new BucketInfo(descriptor.getBucketName(), descriptor.getBucketLocation()),
                    ((ToolSet) entity).getAuthSettings());
            default -> secretFieldProcessor.decryptFields(entity, descriptor);
        }
    }

    @Override
    public Config get() {
        return config;
    }

    @Override
    public Config reload() {
        // Distributed bucket lock so the admin-triggered reload cannot scan blob storage while a
        // peer pod's apply* is mid-batch (design 02 §4.4). Runs on a virtual thread via
        // ConfigController's taskExecutor, so the blocking Redis lock is safe here.
        return lockService.underBucketLocks(ADMIN_BUCKET_LOCATIONS, () -> {
            rebuildLock.lock();
            try {
                fileConfigStore.reload();
                // fileConfigStore.reload() fires onReloadCallbacks → requestRebuild(), which schedules a
                // 500ms debounce timer. Cancel it: the rebuild() below produces the authoritative merged
                // config and a debounced rerun would be a redundant addProjectKeys + listResources sweep.
                cancelPendingRebuild();
                return rebuild();
            } finally {
                rebuildLock.unlock();
            }
        });
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
     * Drives the blob-only {@code 404} path on {@code GET /v1/settings/platform/global}
     * (slice U.1): when {@code false}, the per-entity endpoint returns {@code 404} and
     * operators consult {@code /v1/admin/config/file/settings/global} for the file/default view.
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
     * both fire there); must remain non-blocking. The actual rebuild is dispatched onto
     * {@link AsyncTaskExecutor} (virtual threads) and serialized via {@link #rebuildLock},
     * so this method only does an {@link AtomicLong} CAS on {@link #pendingRebuildTimerId}.
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
        // Distributed bucket lock so the debounced scan cannot read partial-batch blob state while a
        // peer pod's apply* is mid-batch (design 02 §4.4). underBucketLocks blocks on Redis, but we
        // are on a virtual thread (taskExecutor) so that is fine.
        taskExecutor.submit(() -> lockService.underBucketLocks(ADMIN_BUCKET_LOCATIONS, () -> {
            rebuildLock.lock();
            try {
                return rebuild();
            } finally {
                rebuildLock.unlock();
            }
        })).onFailure(error -> log.warn("Failed to rebuild merged config", error));
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
    public Config rebuildNow() {
        // Distributed bucket lock: same partial-state hazard as reload/timer — a full blob scan on
        // this pod must not interleave with a peer pod's in-flight multi-blob apply (design 02 §4.4).
        return lockService.underBucketLocks(ADMIN_BUCKET_LOCATIONS, () -> {
            rebuildLock.lock();
            try {
                cancelPendingRebuild();
                return rebuild();
            } finally {
                rebuildLock.unlock();
            }
        });
    }

    private void cancelPendingRebuild() {
        long previous = pendingRebuildTimerId.getAndSet(NO_PENDING_TIMER);
        if (previous != NO_PENDING_TIMER) {
            vertx.cancelTimer(previous);
        }
    }

    /**
     * Partial-update fast path for the API write controller (slice 4S.4 / OQ-32). Mutates the
     * single entity at {@code mapKey} of the given {@code type} in the merged {@link Config}
     * without re-scanning blob storage. {@code decryptedEntity} is the post-decryption Java entity
     * (or the JSON-string body for {@code APP_TYPE_SCHEMA}) — the controller has already validated
     * cross-references in strict mode and called {@code apiKeyStore.addOrUpdateKey} for keys.
     *
     * <p>For {@code INTERCEPTOR} writes the previously-broken models in {@code invalidEntities} are
     * resurrected when the new interceptor satisfies their references. For other types no transitive
     * effect runs.
     */
    public Config applyEntityWrite(ResourceTypes type, String mapKey, Object decryptedEntity) {
        rebuildLock.lock();
        try {
            return applyEntityWriteLocked(type, mapKey, decryptedEntity);
        } finally {
            rebuildLock.unlock();
        }
    }

    /**
     * Partial-update fast path for DELETE (slice 4S.4). {@code INTERCEPTOR} delete cascades
     * cross-reference revalidation across the model map and routes newly-orphaned models through
     * the {@code invalidEntities} sibling store. Other types have no transitive effect.
     */
    public Config applyEntityDelete(ResourceTypes type, String mapKey) {
        rebuildLock.lock();
        try {
            return applyEntityDeleteLocked(type, mapKey);
        } finally {
            rebuildLock.unlock();
        }
    }

    /**
     * Bulk partial-update for {@code AdminApplyController} (slice 4S.4). Runs each entry under a
     * single {@code rebuildLock} acquisition. Failures (validation/coercion exceptions per entry)
     * surface in the returned {@code canonicalId → reason} map; survivors are applied.
     *
     * <p>Each touched type-map is cloned <strong>once</strong> at the top of the batch and mutated
     * in place across all entries — avoids the {@code O(batch × map)} clone cost that would arise
     * from cloning the map per entry. Per-entry failures roll back the entity slot at
     * {@code (type, mapKey)} so subsequent entries observe the pre-change state, matching
     * single-entity atomicity.
     */
    public Map<String, String> applyBatch(List<EntityChange> changes) {
        Map<String, String> failures = new LinkedHashMap<>();
        rebuildLock.lock();
        try {
            Config next = shallowClone(this.config);
            Map<ResourceTypes, Map<String, InvalidEntityRecord>> nextInvalid = cloneInvalidDeep(this.invalidEntities);

            EnumSet<ResourceTypes> touched = EnumSet.noneOf(ResourceTypes.class);
            for (EntityChange change : changes) {
                touched.add(change.type());
            }
            for (ResourceTypes type : touched) {
                cloneTypeMap(next, type);
            }
            // INTERCEPTOR writes resurrect previously-invalid models (mutate next.models);
            // INTERCEPTOR deletes cascade to model invalidation (also mutate next.models).
            // Clone the models map upfront when INTERCEPTOR is in the batch but MODEL isn't.
            if (touched.contains(ResourceTypes.INTERCEPTOR) && !touched.contains(ResourceTypes.MODEL)) {
                next.setModels(new LinkedHashMap<>(next.getModels()));
            }

            BiConsumer<ResourceTypes, InvalidEntityException> onSkip = skipRouter(nextInvalid);
            for (EntityChange change : changes) {
                try {
                    applyChangeInPlace(next, nextInvalid, change, onSkip);
                } catch (Exception error) {
                    failures.put(change.mapKey(), error.getMessage());
                }
            }

            this.config = next;
            this.invalidEntities = freeze(nextInvalid);
        } finally {
            rebuildLock.unlock();
        }
        return failures;
    }

    /**
     * In-place per-entry apply for {@link #applyBatch}. Snapshots the entity slot at
     * {@code (type, mapKey)} and the matching invalid-entity record so a validation/coercion
     * throw rolls back the per-entry mutation, leaving prior-entry effects intact in {@code next}.
     * Cross-type mutations (resurrection / cascade) cannot partial-fail — the validate helper
     * throws before the cross-type step runs.
     */
    private void applyChangeInPlace(Config next,
                                    Map<ResourceTypes, Map<String, InvalidEntityRecord>> nextInvalid,
                                    EntityChange change,
                                    BiConsumer<ResourceTypes, InvalidEntityException> onSkip) {
        ResourceTypes type = change.type();
        String mapKey = change.mapKey();
        Object decryptedEntity = change.decryptedEntity();

        Object previousEntity = peekEntity(next, type, mapKey);
        Map<String, InvalidEntityRecord> perType = nextInvalid.get(type);
        InvalidEntityRecord previousInvalid = perType == null ? null : perType.get(mapKey);

        try {
            clearInvalid(nextInvalid, type, mapKey);
            if (decryptedEntity == null) {
                removeEntityInPlace(next, type, mapKey);
                if (type == ResourceTypes.INTERCEPTOR) {
                    ConfigPostProcessor.cascadeInterceptorDelete(next, onSkip);
                }
                return;
            }
            putEntityInPlace(next, type, mapKey, decryptedEntity);
            switch (type) {
                case MODEL -> ConfigPostProcessor.validateSingleModel(next, mapKey, onSkip);
                case INTERCEPTOR -> {
                    ConfigPostProcessor.validateSingleInterceptor(next, mapKey);
                    resurrectInvalidModels(next, nextInvalid);
                }
                case ROLE -> ConfigPostProcessor.validateSingleRole(next, mapKey);
                case APPLICATION -> ConfigPostProcessor.validateSingleApplication(next, mapKey);
                case TOOL_SET -> ConfigPostProcessor.validateSingleToolSet(next, mapKey);
                case PROJECT_KEY -> { /* no post-processing */ }
                case APP_TYPE_SCHEMA -> ConfigPostProcessor.validateSingleSchema(next, ResourceTypes.APP_TYPE_SCHEMA, mapKey);
                case CATALOG_SCHEMA  -> ConfigPostProcessor.validateSingleSchema(next, ResourceTypes.CATALOG_SCHEMA,  mapKey);
                case ROUTE -> ConfigPostProcessor.sortRoutesInPlace(next);
                default -> throw new IllegalArgumentException("Unsupported type for partial update: " + type);
            }
        } catch (RuntimeException error) {
            if (previousEntity == null) {
                removeEntityInPlace(next, type, mapKey);
            } else {
                putEntityInPlace(next, type, mapKey, previousEntity);
            }
            if (previousInvalid != null) {
                nextInvalid.computeIfAbsent(type, k -> new HashMap<>()).put(mapKey, previousInvalid);
            }
            throw error;
        }
    }

    /**
     * Settings singleton overlay write (slice 4S.4). Applies {@code globalInterceptors} +
     * {@code retriableErrorCodes} fields from the supplied {@link GlobalSettings}, flips
     * {@code settingsFromApi} to {@code true}.
     */
    public Config applySettingsWrite(GlobalSettings settings) {
        rebuildLock.lock();
        try {
            Config next = shallowClone(this.config);
            next.setGlobalInterceptors(settings.getGlobalInterceptors() == null
                    ? List.of() : settings.getGlobalInterceptors());
            next.setRetriableErrorCodes(settings.getRetriableErrorCodes() == null
                    ? Set.of() : settings.getRetriableErrorCodes());
            this.config = next;
            this.settingsFromApi = true;
            return next;
        } finally {
            rebuildLock.unlock();
        }
    }

    /**
     * Settings singleton overlay delete (slice 4S.4). Restores {@code globalInterceptors} +
     * {@code retriableErrorCodes} from the file-derived {@link Config} and flips
     * {@code settingsFromApi} to {@code false}.
     */
    public Config applySettingsDelete() {
        rebuildLock.lock();
        try {
            Config base = fileConfigStore.get();
            Config next = shallowClone(this.config);
            next.setGlobalInterceptors(base.getGlobalInterceptors());
            next.setRetriableErrorCodes(base.getRetriableErrorCodes());
            this.config = next;
            this.settingsFromApi = false;
            return next;
        } finally {
            rebuildLock.unlock();
        }
    }

    private Config applyEntityWriteLocked(ResourceTypes type, String mapKey, Object decryptedEntity) {
        Config next = shallowClone(this.config);
        Map<ResourceTypes, Map<String, InvalidEntityRecord>> nextInvalid = cloneInvalidDeep(this.invalidEntities);

        // Clone the touched type-map once so the previous Config (still visible to readers via
        // volatile config) keeps its reference-shared map instance intact. INTERCEPTOR also
        // mutates next.models via resurrection, so clone that too.
        cloneTypeMap(next, type);
        if (type == ResourceTypes.INTERCEPTOR) {
            next.setModels(new LinkedHashMap<>(next.getModels()));
        }

        BiConsumer<ResourceTypes, InvalidEntityException> onSkip = skipRouter(nextInvalid);
        EntityChange change = new EntityChange(type, mapKey, decryptedEntity);
        applyChangeInPlace(next, nextInvalid, change, onSkip);

        this.config = next;
        this.invalidEntities = freeze(nextInvalid);
        return next;
    }

    private Config applyEntityDeleteLocked(ResourceTypes type, String mapKey) {
        Config next = shallowClone(this.config);
        Map<ResourceTypes, Map<String, InvalidEntityRecord>> nextInvalid = cloneInvalidDeep(this.invalidEntities);

        cloneTypeMap(next, type);
        if (type == ResourceTypes.INTERCEPTOR) {
            // Cascade mutates next.models; clone so the previous Config's models map stays intact.
            next.setModels(new LinkedHashMap<>(next.getModels()));
        }

        BiConsumer<ResourceTypes, InvalidEntityException> onSkip = skipRouter(nextInvalid);
        EntityChange change = new EntityChange(type, mapKey, null);
        applyChangeInPlace(next, nextInvalid, change, onSkip);

        this.config = next;
        this.invalidEntities = freeze(nextInvalid);
        return next;
    }

    private BiConsumer<ResourceTypes, InvalidEntityException> skipRouter(
            Map<ResourceTypes, Map<String, InvalidEntityRecord>> nextInvalid) {
        if (!MODE_SKIP.equals(onInvalidEntity)) {
            return null;
        }
        // Partial-update writes are always API-sourced — this path never touches file config.
        return (skippedType, error) -> recordSkippedByMapKey(nextInvalid, skippedType, error, id -> null, true);
    }

    /**
     * Records a {@link InvalidEntityException} routed via {@code onSkip}. The map key's shape still
     * tells us the canonical id and display name: a key carrying a {@code '/'} already is the
     * canonical id, while a bare key is a short name we expand via {@code type/platform/name}. That
     * derivation is independent of source, but the {@code source} label itself needs an explicit
     * {@code fromApi} — for the five short-name-keyed types a file entry and a blob entry now share
     * the identical (bare) key shape, so the caller must supply real provenance instead of guessing.
     * {@code payloadByCanonicalId} supplies the parsed blob body (rebuild) or {@code null} (partial update).
     */
    private void recordSkippedByMapKey(Map<ResourceTypes, Map<String, InvalidEntityRecord>> nextInvalid,
                                       ResourceTypes type, InvalidEntityException error,
                                       Function<String, JsonNode> payloadByCanonicalId, boolean fromApi) {
        String mapKey = error.getMapKey();
        String canonicalId = mapKey.contains("/")
                ? mapKey
                : canonicalId(type, locationStrategy.resolveBucket(type, EntityLocationStrategy.PLATFORM_SCOPE), mapKey);
        String simpleName = lastSegment(mapKey);
        recordInvalid(nextInvalid, type, canonicalId, simpleName, error.getMessage(), error.getWarnings(),
                payloadByCanonicalId.apply(canonicalId), REASON_VALIDATION, fromApi ? "api" : "file");
    }

    private void resurrectInvalidModels(Config next, Map<ResourceTypes, Map<String, InvalidEntityRecord>> nextInvalid) {
        Map<String, InvalidEntityRecord> invalidModels = nextInvalid.get(ResourceTypes.MODEL);
        if (invalidModels == null || invalidModels.isEmpty()) {
            return;
        }
        // Iterate a snapshot to allow modification of the live map below.
        for (Map.Entry<String, InvalidEntityRecord> entry : new ArrayList<>(invalidModels.entrySet())) {
            String canonicalId = entry.getKey();
            InvalidEntityRecord record = entry.getValue();
            JsonNode payload = record.getPayload();
            if (payload == null) {
                continue; // parse-error case — body unavailable for resurrection
            }
            try {
                Model candidate = ProxyUtil.BLOB_MAPPER.treeToValue(payload, Model.class);
                ResourceDescriptor descriptor = descriptorFromCanonicalId(ResourceTypes.MODEL, canonicalId);
                secretFieldProcessor.decryptFields(candidate, descriptor);
                List<ValidationWarning> warnings = new ArrayList<>();
                next.getModels().put(canonicalId, candidate);
                ConfigPostProcessor.validateCrossReferences(candidate, next, warnings);
                if (warnings.isEmpty()) {
                    invalidModels.remove(canonicalId);
                } else {
                    // Still broken — roll back the speculative insert.
                    next.getModels().remove(canonicalId);
                }
            } catch (Exception resurrectError) {
                log.debug("Model '{}' could not be resurrected on interceptor write: {}",
                        canonicalId, resurrectError.getMessage());
                // Leave in invalidEntities for the next full rebuild to retry.
            }
        }
        if (invalidModels.isEmpty()) {
            nextInvalid.remove(ResourceTypes.MODEL);
        }
    }

    private static Config shallowClone(Config base) {
        Config next = new Config();
        next.setModels(base.getModels());
        next.setInterceptors(base.getInterceptors());
        next.setRoles(base.getRoles());
        next.setKeys(base.getKeys());
        next.setRoutes(base.getRoutes());
        next.setApplicationTypeSchemas(base.getApplicationTypeSchemas());
        next.setCatalogSchemas(base.getCatalogSchemas());
        next.setApplications(base.getApplications());
        next.setToolsets(base.getToolsets());
        next.setRetriableErrorCodes(base.getRetriableErrorCodes());
        next.setGlobalInterceptors(base.getGlobalInterceptors());
        return next;
    }

    private static Map<ResourceTypes, Map<String, InvalidEntityRecord>> cloneInvalidDeep(
            Map<ResourceTypes, Map<String, InvalidEntityRecord>> source) {
        Map<ResourceTypes, Map<String, InvalidEntityRecord>> clone = new EnumMap<>(ResourceTypes.class);
        for (Map.Entry<ResourceTypes, Map<String, InvalidEntityRecord>> entry : source.entrySet()) {
            clone.put(entry.getKey(), new HashMap<>(entry.getValue()));
        }
        return clone;
    }

    private static void clearInvalid(Map<ResourceTypes, Map<String, InvalidEntityRecord>> invalid,
                                     ResourceTypes type, String mapKey) {
        Map<String, InvalidEntityRecord> perType = invalid.get(type);
        if (perType != null) {
            perType.remove(mapKey);
        }
    }

    private static Map<ResourceTypes, Map<String, InvalidEntityRecord>> freeze(
            Map<ResourceTypes, Map<String, InvalidEntityRecord>> source) {
        source.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        return source.isEmpty() ? Map.of() : Collections.unmodifiableMap(source);
    }

    /**
     * Replaces the type-map for {@code type} on {@code config} with a fresh copy — callers then
     * mutate the new map in place across one or many entries. Untouched type-maps remain
     * reference-shared with the previous {@link Config} (volatile-swap idiom).
     */
    private static void cloneTypeMap(Config config, ResourceTypes type) {
        switch (type) {
            case MODEL -> config.setModels(new LinkedHashMap<>(config.getModels()));
            case INTERCEPTOR -> config.setInterceptors(new LinkedHashMap<>(config.getInterceptors()));
            case ROLE -> config.setRoles(new HashMap<>(config.getRoles()));
            case PROJECT_KEY -> config.setKeys(new HashMap<>(config.getKeys()));
            case ROUTE -> config.setRoutes(new LinkedHashMap<>(config.getRoutes()));
            case APP_TYPE_SCHEMA -> config.setApplicationTypeSchemas(new LinkedHashMap<>(config.getApplicationTypeSchemas()));
            case CATALOG_SCHEMA -> config.setCatalogSchemas(new LinkedHashMap<>(config.getCatalogSchemas()));
            case APPLICATION -> config.setApplications(new LinkedHashMap<>(config.getApplications()));
            case TOOL_SET -> config.setToolsets(new LinkedHashMap<>(config.getToolsets()));
            default -> throw new IllegalArgumentException("Unsupported type for partial update: " + type);
        }
    }

    private static Object peekEntity(Config config, ResourceTypes type, String mapKey) {
        return switch (type) {
            case MODEL -> config.getModels().get(mapKey);
            case INTERCEPTOR -> config.getInterceptors().get(mapKey);
            case ROLE -> config.getRoles().get(mapKey);
            case PROJECT_KEY -> config.getKeys().get(mapKey);
            case ROUTE -> config.getRoutes().get(mapKey);
            case APP_TYPE_SCHEMA -> config.getApplicationTypeSchemas().get(mapKey);
            case CATALOG_SCHEMA -> config.getCatalogSchemas().get(mapKey);
            case APPLICATION -> config.getApplications().get(mapKey);
            case TOOL_SET -> config.getToolsets().get(mapKey);
            default -> throw new IllegalArgumentException("Unsupported type for partial update: " + type);
        };
    }

    private static void putEntityInPlace(Config config, ResourceTypes type, String mapKey, Object entity) {
        switch (type) {
            case MODEL -> config.getModels().put(mapKey, (Model) entity);
            case INTERCEPTOR -> config.getInterceptors().put(mapKey, (Interceptor) entity);
            case ROLE -> config.getRoles().put(mapKey, (Role) entity);
            case PROJECT_KEY -> config.getKeys().put(mapKey, (Key) entity);
            case ROUTE -> config.getRoutes().put(mapKey, (Route) entity);
            case APP_TYPE_SCHEMA -> config.getApplicationTypeSchemas().put(mapKey, schemaBody(entity));
            case CATALOG_SCHEMA  -> config.getCatalogSchemas().put(mapKey, schemaBody(entity));
            case APPLICATION -> config.getApplications().put(mapKey, (Application) entity);
            case TOOL_SET -> config.getToolsets().put(mapKey, (ToolSet) entity);
            default -> throw new IllegalArgumentException("Unsupported type for partial update: " + type);
        }
    }

    private static void removeEntityInPlace(Config config, ResourceTypes type, String mapKey) {
        switch (type) {
            case MODEL -> config.getModels().remove(mapKey);
            case INTERCEPTOR -> config.getInterceptors().remove(mapKey);
            case ROLE -> config.getRoles().remove(mapKey);
            case PROJECT_KEY -> config.getKeys().remove(mapKey);
            case ROUTE -> config.getRoutes().remove(mapKey);
            case APP_TYPE_SCHEMA -> config.getApplicationTypeSchemas().remove(mapKey);
            case CATALOG_SCHEMA -> config.getCatalogSchemas().remove(mapKey);
            case APPLICATION -> config.getApplications().remove(mapKey);
            case TOOL_SET -> config.getToolsets().remove(mapKey);
            default -> throw new IllegalArgumentException("Unsupported type for partial update: " + type);
        }
    }

    public static String extractSchemaId(JsonNode node) {
        JsonNode id = node.get("$id");
        return id != null && id.isTextual() ? id.asText() : null;
    }

    private static String schemaBody(Object entity) {
        if (entity instanceof String s) {
            return s;
        }
        if (entity instanceof JsonNode n) {
            return n.toString();
        }
        return entity == null ? null : entity.toString();
    }

    private static ResourceDescriptor descriptorFromCanonicalId(ResourceTypes type, String canonicalId) {
        // Canonical IDs are the resource URL form; ResourceDescriptorFactory.fromAnyUrl handles the
        // platform/public bucket-location convention. The {@code type} parameter is retained for the
        // callsite's type-safety contract (assert the resolved descriptor type matches).
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromAnyUrl(canonicalId, null);
        if (descriptor.getType() != type) {
            throw new IllegalArgumentException("Canonical ID type mismatch: expected "
                    + type + " got " + descriptor.getType() + " from " + canonicalId);
        }
        return descriptor;
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
        Map<String, String> catalogSchemas = new LinkedHashMap<>(base.getCatalogSchemas());
        Map<String, Application> applications = new LinkedHashMap<>(base.getApplications());
        Map<String, ToolSet> toolsets = new LinkedHashMap<>(base.getToolsets());
        merged.setRetriableErrorCodes(base.getRetriableErrorCodes());
        merged.setGlobalInterceptors(base.getGlobalInterceptors());
        // Wire the (still-being-populated) local maps onto merged now rather than after the blob
        // scan below — same references either way, but it lets addBlobEntity/removeAddedEntity
        // dispatch off a single Config parameter instead of a positional map per type (they used
        // to take 9-11 map parameters each).
        merged.setModels(models);
        merged.setInterceptors(interceptors);
        merged.setRoles(roles);
        merged.setKeys(keys);
        merged.setRoutes(routes);
        merged.setApplicationTypeSchemas(schemas);
        merged.setCatalogSchemas(catalogSchemas);
        merged.setApplications(applications);
        merged.setToolsets(toolsets);

        Map<String, JsonNode> blobBodies = new HashMap<>();
        Map<ResourceTypes, Map<String, InvalidEntityRecord>> pendingInvalid = new EnumMap<>(ResourceTypes.class);
        // API-sourced project keys collected during the blob scan, keyed by canonical id. Kept
        // distinct from the file partition (base.getKeys(), keyed by secret) so the ApiKeyStore feed
        // can classify the source without inferring from map-key shape — file secrets may be Base64
        // and contain '/' (OQ-12). The merged keys map itself still folds both for config consumers.
        Map<String, Key> apiKeysByCanonicalId = new HashMap<>();
        // For models/interceptors/roles/applications/toolsets, a blob entry's map key (its short
        // name) is indistinguishable in shape from a file entry's — both are bare, slash-free names.
        // Track which (type, mapKey) pairs came from a blob this rebuild so the semantic pass can
        // classify a skipped entity as "api" vs "file" correctly. Only models, applications,
        // interceptors, and toolsets actually trigger onSkip (roles, schemas, keys, routes do not),
        // but we track all five short-name-keyed types for completeness.
        Map<ResourceTypes, Set<String>> apiSourcedKeys = new EnumMap<>(ResourceTypes.class);

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

                    // Schemas are keyed by their JSON-Schema $id, not by blob name or canonical id.
                    String mapKey = resolveRebuildMapKey(descriptor, node, canonicalId);
                    if (mapKey == null) {
                        continue;
                    }
                    Object added;
                    try {
                        added = addBlobEntity(merged, type, mapKey, node);
                    } catch (Exception parseError) {
                        recordInvalid(pendingInvalid, type, canonicalId, name,
                                "JSON parse failure: " + parseError.getMessage(),
                                List.of(new ValidationWarning("body", parseError.getMessage())),
                                node, REASON_PARSE, "api");
                        continue;
                    }

                    if (added != null) {
                        try {
                            decryptManagedEntity(type, added, descriptor);
                        } catch (Exception decryptError) {
                            // Roll back the partial insertion so decryption-failure entities never
                            // reach addProjectKeys (locked 2S.9 invariant).
                            removeAddedEntity(merged, type, mapKey);
                            recordInvalid(pendingInvalid, type, canonicalId, name,
                                    "Decryption failure: " + decryptError.getMessage(),
                                    List.of(new ValidationWarning("body", decryptError.getMessage())),
                                    node, REASON_DECRYPTION, "api");
                            continue;
                        }
                        if (type == ResourceTypes.PROJECT_KEY) {
                            apiKeysByCanonicalId.put(canonicalId, (Key) added);
                        }
                        if (isShortNameKeyed(type)) {
                            apiSourcedKeys.computeIfAbsent(type, t -> new HashSet<>()).add(mapKey);
                        }
                    }
                    blobBodies.put(canonicalId, node);
                }
            }
        }

        // Semantic pass — under MODE_SKIP, route per-entity violations to invalidEntities and
        // continue; under MODE_ABORT, the post-processor throws and the rebuild aborts (this.config
        // stays at the previous value because we only swap below).
        BiConsumer<ResourceTypes, InvalidEntityException> onSkip = MODE_SKIP.equals(onInvalidEntity)
                ? (type, error) -> {
                    // Skips surface through the invalidEntities sibling store only for MANAGED_TYPES
                    // (design 02 §4.3 layered model). APPLICATION/TOOL_SET have no cross-reference
                    // validation in processSemantic (they're validated lazily by ApplicationService/
                    // ToolSetService on write), but processApplications/processToolSets can still route a
                    // duplicate deployment-id skip here via skipOnDuplicate — and since they're now in
                    // MANAGED_TYPES that skip is recorded by recordSkippedByMapKey below, not just logged.
                    if (!MANAGED_TYPES.contains(type)) {
                        log.warn("Skipped {} '{}' from merged Config: {}", type.urlSegment(),
                                error.getMapKey(), error.getMessage());
                        return;
                    }
                    // For the short-name-keyed types, key shape no longer tells file from blob
                    // (see apiSourcedKeys above); everything else still resolves via shape alone.
                    boolean fromApi = error.getMapKey().contains("/")
                            || apiSourcedKeys.getOrDefault(type, Set.of()).contains(error.getMapKey());
                    recordSkippedByMapKey(pendingInvalid, type, error, blobBodies::get, fromApi);
                }
                : null;
        // File partition = base file keys (keyed by secret); API partition = PROJECT_KEY blob entries
        // collected above (keyed by canonical id). The merged Config.getKeys() folds both for config
        // consumers, but the ApiKeyStore feed stays explicitly source-classified (FINDING #7).
        ConfigPostProcessor.processSemantic(merged, apiKeyStore, base.getKeys(), apiKeysByCanonicalId, onSkip);

        // ConfigPostProcessor sets entity.name = mapKey. For models/interceptors/roles/
        // applications/toolsets the map key is the short name ("gpt-4"); for schemas it is
        // the decoded $id — in both cases file- and blob-sourced entries share the same map key.
        // Keys and routes are unaffected — their map key stays the canonical id, as before.

        boolean overlayFromApi = applySettingsOverlay(merged, pendingInvalid);
        Map<ResourceTypes, Map<String, InvalidEntityRecord>> finalInvalid =
                pendingInvalid.isEmpty() ? Map.of() : Collections.unmodifiableMap(pendingInvalid);
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
     * not a union-by-key like other types. Returns {@code true} iff the blob is present and
     * parses; on parse failure records an {@link InvalidEntityRecord} under {@code GLOBAL_SETTINGS}
     * (the singleton's canonical ID is {@code settings/platform/global}) so the broken blob is
     * visible via {@link #getInvalidEntities()} instead of silently disappearing from runtime.
     */
    private boolean applySettingsOverlay(Config merged,
                                         Map<ResourceTypes, Map<String, InvalidEntityRecord>> pendingInvalid) {
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.GLOBAL_SETTINGS, ResourceDescriptor.PLATFORM_BUCKET,
                ResourceDescriptor.PLATFORM_LOCATION, "global");
        String body = resourceService.getResource(descriptor);
        if (body == null) {
            return false;
        }
        String canonicalId = canonicalId(ResourceTypes.GLOBAL_SETTINGS, ResourceDescriptor.PLATFORM_BUCKET, "global");
        JsonNode payload;
        try {
            payload = ProxyUtil.BLOB_MAPPER.readTree(body);
        } catch (Exception parseError) {
            log.warn("Failed to parse settings singleton blob as JSON", parseError);
            recordInvalid(pendingInvalid, ResourceTypes.GLOBAL_SETTINGS, canonicalId, "global",
                    "JSON parse failure: " + parseError.getMessage(),
                    List.of(new ValidationWarning("body", parseError.getMessage())),
                    null, REASON_PARSE, "api");
            return false;
        }
        try {
            GlobalSettings settings = ProxyUtil.BLOB_MAPPER.treeToValue(payload, GlobalSettings.class);
            merged.setGlobalInterceptors(
                    settings.getGlobalInterceptors() == null ? List.of() : settings.getGlobalInterceptors());
            merged.setRetriableErrorCodes(
                    settings.getRetriableErrorCodes() == null ? Set.of() : settings.getRetriableErrorCodes());
            return true;
        } catch (Exception parseError) {
            log.warn("Failed to bind settings singleton blob to GlobalSettings", parseError);
            recordInvalid(pendingInvalid, ResourceTypes.GLOBAL_SETTINGS, canonicalId, "global",
                    "JSON parse failure: " + parseError.getMessage(),
                    List.of(new ValidationWarning("body", parseError.getMessage())),
                    payload, REASON_PARSE, "api");
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
        // Counter is pre-registered for the closed set {REASON_PARSE, REASON_VALIDATION,
        // REASON_DECRYPTION} × {MANAGED_TYPES + GLOBAL_SETTINGS}. Guard so an unforeseen
        // (type, reason) pair logs a warning and skips the metric instead of NPE'ing the
        // rebuild, which would leave this.config at a stale value with no observable cause.
        Map<String, Counter> perReason = skipCounters.get(type);
        Counter counter = perReason == null ? null : perReason.get(reasonClass);
        if (counter != null) {
            counter.increment();
        } else {
            log.warn("No skip counter registered for type '{}' / reason '{}' — metric skipped",
                    type.urlSegment(), reasonClass);
        }
        log.warn("Skipped {} '{}' from merged Config: {}", type.urlSegment(), canonicalId, reason);
    }

    private static String lastSegment(String key) {
        int slash = key.lastIndexOf('/');
        return slash < 0 ? key : key.substring(slash + 1);
    }

    public static String canonicalId(ResourceTypes type, String bucket, String name) {
        return type.urlSegment() + ResourceDescriptor.PATH_SEPARATOR + bucket + ResourceDescriptor.PATH_SEPARATOR + name;
    }

    public static String canonicalId(ResourceDescriptor descriptor) {
        return canonicalId((ResourceTypes) descriptor.getType(),
                descriptor.getBucketName(), descriptor.getName());
    }

    /**
     * The Config map key for non-schema types.
     * <ul>
     *   <li>Models/interceptors/roles/applications/toolsets → short name (last path segment)</li>
     *   <li>Every other non-schema type → canonical id</li>
     * </ul>
     * Throws {@link IllegalArgumentException} if called for a schema type — use
     * {@link #mapKeyForSchema(JsonNode)} for {@code APP_TYPE_SCHEMA} / {@code CATALOG_SCHEMA}.
     */
    public static String mapKeyFor(ResourceDescriptor descriptor) {
        ResourceTypes type = (ResourceTypes) descriptor.getType();
        if (type == ResourceTypes.APP_TYPE_SCHEMA || type == ResourceTypes.CATALOG_SCHEMA) {
            throw new IllegalArgumentException(
                    "Use mapKeyForSchema(JsonNode) for schema types; descriptor: " + descriptor.getUrl());
        }
        return isShortNameKeyed(type) ? descriptor.getName() : canonicalId(descriptor);
    }

    /**
     * Resolves the Config map key for a blob entry during a full rebuild. For schema types, returns
     * the {@code $id} extracted from the blob body, or {@code null} (and logs a warning) when the
     * body has no textual {@code $id}. For all other types, delegates to
     * {@link #mapKeyFor(ResourceDescriptor)}.
     */
    @Nullable
    private static String resolveRebuildMapKey(ResourceDescriptor descriptor, JsonNode node, String canonicalId) {
        ResourceTypes type = (ResourceTypes) descriptor.getType();
        if (type != ResourceTypes.APP_TYPE_SCHEMA && type != ResourceTypes.CATALOG_SCHEMA) {
            return mapKeyFor(descriptor);
        }
        String schemaId = extractSchemaId(node);
        if (schemaId == null) {
            log.warn("Skipping schema blob '{}': missing or non-textual $id field", canonicalId);
        }
        return schemaId;
    }

    /**
     * Resolves the Config map key for a replica CREATE/UPDATE event. For schema types, prefers the
     * {@code $id} carried in {@code eventMetadata} (set by the writer) and falls back to extracting
     * it from the blob body when metadata is absent (older-pod delivery). For all other types,
     * delegates to {@link #mapKeyFor(ResourceDescriptor)}.
     */
    private static String resolveReplicaMapKey(ResourceDescriptor descriptor, JsonNode node,
                                               boolean isSchema,
                                               @Nullable Map<String, String> eventMetadata) {
        if (!isSchema) {
            return mapKeyFor(descriptor);
        }
        String metaId = eventMetadata != null ? eventMetadata.get("$id") : null;
        return metaId != null ? metaId : mapKeyForSchema(node);
    }

    /**
     * The Config map key for schema types ({@code APP_TYPE_SCHEMA}, {@code CATALOG_SCHEMA}):
     * the JSON-Schema {@code $id} field extracted from the blob body.
     * Throws {@link IllegalArgumentException} when {@code body} has no textual {@code $id}.
     */
    public static String mapKeyForSchema(JsonNode body) {
        String schemaId = extractSchemaId(body);
        if (schemaId == null) {
            throw new IllegalArgumentException(
                    "Schema body must contain a non-null textual $id field");
        }
        return schemaId;
    }

    private static boolean isShortNameKeyed(ResourceTypes type) {
        return switch (type) {
            case MODEL, INTERCEPTOR, ROLE, APPLICATION, TOOL_SET -> true;
            default -> false;
        };
    }

    /**
     * Reads the type-map to mutate off {@code config} rather than taking one map parameter per
     * managed type — {@code config}'s maps are wired up by {@link #rebuild} before the blob scan
     * that calls this runs, so they're already the right (still being populated) instances.
     */
    private static Object addBlobEntity(Config config, ResourceTypes type, String mapKey, JsonNode node)
            throws JsonProcessingException {
        switch (type) {
            case MODEL -> {
                Model entity = ProxyUtil.BLOB_MAPPER.treeToValue(node, Model.class);
                warnIfReplaced(type, mapKey, config.getModels().put(mapKey, entity));
                return entity;
            }
            case INTERCEPTOR -> {
                Interceptor entity = ProxyUtil.BLOB_MAPPER.treeToValue(node, Interceptor.class);
                warnIfReplaced(type, mapKey, config.getInterceptors().put(mapKey, entity));
                return entity;
            }
            case ROLE -> {
                Role entity = ProxyUtil.BLOB_MAPPER.treeToValue(node, Role.class);
                warnIfReplaced(type, mapKey, config.getRoles().put(mapKey, entity));
                return entity;
            }
            case PROJECT_KEY -> {
                Key entity = ProxyUtil.BLOB_MAPPER.treeToValue(node, Key.class);
                warnIfReplaced(type, mapKey, config.getKeys().put(mapKey, entity));
                return entity;
            }
            case ROUTE -> {
                Route entity = ProxyUtil.BLOB_MAPPER.treeToValue(node, Route.class);
                warnIfReplaced(type, mapKey, config.getRoutes().put(mapKey, entity));
                return entity;
            }
            case APP_TYPE_SCHEMA -> {
                warnIfReplaced(type, mapKey, config.getApplicationTypeSchemas().put(mapKey, node.toString()));
                return null;
            }
            case CATALOG_SCHEMA -> {
                warnIfReplaced(type, mapKey, config.getCatalogSchemas().put(mapKey, node.toString()));
                return null;
            }
            case APPLICATION -> {
                Application entity = ProxyUtil.BLOB_MAPPER.treeToValue(node, Application.class);
                warnIfReplaced(type, mapKey, config.getApplications().put(mapKey, entity));
                return entity;
            }
            case TOOL_SET -> {
                ToolSet entity = ProxyUtil.BLOB_MAPPER.treeToValue(node, ToolSet.class);
                warnIfReplaced(type, mapKey, config.getToolsets().put(mapKey, entity));
                return entity;
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

    private static void removeAddedEntity(Config config, ResourceTypes type, String mapKey) {
        switch (type) {
            case MODEL -> config.getModels().remove(mapKey);
            case INTERCEPTOR -> config.getInterceptors().remove(mapKey);
            case ROLE -> config.getRoles().remove(mapKey);
            case PROJECT_KEY -> config.getKeys().remove(mapKey);
            case ROUTE -> config.getRoutes().remove(mapKey);
            case APP_TYPE_SCHEMA -> config.getApplicationTypeSchemas().remove(mapKey);
            case CATALOG_SCHEMA -> config.getCatalogSchemas().remove(mapKey);
            case APPLICATION -> config.getApplications().remove(mapKey);
            case TOOL_SET -> config.getToolsets().remove(mapKey);
            default -> { /* no-op */ }
        }
    }
}
