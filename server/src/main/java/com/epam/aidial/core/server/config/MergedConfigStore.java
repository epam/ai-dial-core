package com.epam.aidial.core.server.config;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Role;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import io.vertx.core.Vertx;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link ConfigStore} implementation that builds the runtime {@link Config} as the
 * union of {@link FileConfigStore} and API-managed entities loaded from
 * {@link ResourceService}. Per design 02 §4: file entries keep simple-name keys
 * ("gpt-4"); API entries use canonical-ID keys ("models/public/gpt-4"). Both
 * coexist in the same {@code Config} maps.
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

    private FileConfigStore fileConfigStore;
    private volatile Config config;
    private volatile boolean initialized;
    private final AtomicLong pendingRebuildTimerId = new AtomicLong(NO_PENDING_TIMER);

    public MergedConfigStore(Vertx vertx, ResourceService resourceService,
                             ApiKeyStore apiKeyStore, EntityLocationStrategy locationStrategy) {
        this.vertx = vertx;
        this.resourceService = resourceService;
        this.apiKeyStore = apiKeyStore;
        this.locationStrategy = locationStrategy;
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
        cancelPendingRebuildLocked();
        return rebuild();
    }

    /**
     * Non-blocking, 500 ms trailing-edge debounced rebuild trigger. No-op until
     * {@link #init} has completed — pre-init triggers are subsumed by the
     * explicit initial rebuild that {@code init} performs.
     */
    public synchronized void requestRebuild() {
        if (!initialized) {
            return;
        }
        cancelPendingRebuildLocked();
        long timerId = vertx.setTimer(REBUILD_DEBOUNCE_MS, firingId -> {
            synchronized (this) {
                pendingRebuildTimerId.compareAndSet(firingId, NO_PENDING_TIMER);
            }
            vertx.<Config>executeBlocking(() -> {
                synchronized (this) {
                    return rebuild();
                }
            }, false).onFailure(error -> log.warn("Failed to rebuild merged config: {}", error.getMessage()));
        });
        pendingRebuildTimerId.set(timerId);
    }

    private void cancelPendingRebuildLocked() {
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

        List<Runnable> resetSimpleName = new ArrayList<>();

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
                    addBlobEntity(type, canonicalId(type, bucket, name), name, body,
                            models, interceptors, roles, keys, routes, schemas, resetSimpleName);
                }
            }
        }

        merged.setModels(models);
        merged.setInterceptors(interceptors);
        merged.setRoles(roles);
        merged.setKeys(keys);
        merged.setRoutes(routes);
        merged.setApplicationTypeSchemas(schemas);

        ConfigPostProcessor.process(merged, apiKeyStore);
        // ConfigPostProcessor sets entity.name = mapKey (canonical ID for API entries).
        // Reset name to the simple name so projections match the design 02 §4 / 04 §4.3 shape.
        resetSimpleName.forEach(Runnable::run);

        this.config = merged;
        return merged;
    }

    static String canonicalId(ResourceTypes type, String bucket, String name) {
        return type.urlSegment() + ResourceDescriptor.PATH_SEPARATOR + bucket + ResourceDescriptor.PATH_SEPARATOR + name;
    }

    private static void addBlobEntity(ResourceTypes type, String canonicalId, String simpleName, String body,
                                      Map<String, Model> models, Map<String, Interceptor> interceptors,
                                      Map<String, Role> roles, Map<String, Key> keys,
                                      LinkedHashMap<String, Route> routes, Map<String, String> schemas,
                                      List<Runnable> resetSimpleName) {
        switch (type) {
            case MODEL -> {
                Model entity = ProxyUtil.convertToObject(body, Model.class);
                models.put(canonicalId, entity);
                resetSimpleName.add(() -> entity.setName(simpleName));
            }
            case INTERCEPTOR -> {
                Interceptor entity = ProxyUtil.convertToObject(body, Interceptor.class);
                interceptors.put(canonicalId, entity);
                resetSimpleName.add(() -> entity.setName(simpleName));
            }
            case ROLE -> {
                Role entity = ProxyUtil.convertToObject(body, Role.class);
                roles.put(canonicalId, entity);
                resetSimpleName.add(() -> entity.setName(simpleName));
            }
            case PROJECT_KEY -> keys.put(canonicalId, ProxyUtil.convertToObject(body, Key.class));
            case ROUTE -> {
                Route entity = ProxyUtil.convertToObject(body, Route.class);
                routes.put(canonicalId, entity);
                resetSimpleName.add(() -> entity.setName(simpleName));
            }
            case APP_TYPE_SCHEMA -> schemas.put(canonicalId, body);
            default -> { /* GLOBAL_SETTINGS is a singleton — design 02 §4 leaves union-by-key out of scope. */ }
        }
    }
}
