package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.GlobalSettings;
import com.epam.aidial.core.openapi.annotations.ApiExtension;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.config.SchemaMigrationNaming;
import com.epam.aidial.core.server.data.AdminApplyStatus;
import com.epam.aidial.core.server.data.AdminManifest;
import com.epam.aidial.core.server.data.ConfigFileMigrateRequest;
import com.epam.aidial.core.server.data.ConfigFileMigrateResponse;
import com.epam.aidial.core.server.data.ConfigFileMigrateResult;
import com.epam.aidial.core.server.data.ConfigFileMigrateStatus;
import com.epam.aidial.core.server.data.ValidationResult;
import com.epam.aidial.core.server.data.ValidationStatus;
import com.epam.aidial.core.server.security.ConfigAuthorizationService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.LockService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Admin-triggered, on-demand copy of file-defined config entities into the {@code platform} blob
 * bucket. Migration is never automatic on startup — that would resurrect an API-deleted entity on
 * restart. Reuses {@link AdminApplyController}'s per-kind write pipeline.
 */
public class ConfigFileMigrateController {

    private static final List<String> ALL_TYPES = List.of(
            "settings", "schemas", "catalog_schemas", "interceptors", "roles", "keys", "routes",
            "models", "toolsets", "applications");

    private static final List<ManagedTypeSpec> MANAGED_TYPE_SPECS = List.of(
            new ManagedTypeSpec("interceptors", "Interceptor", ResourceTypes.INTERCEPTOR, Config::getInterceptors),
            new ManagedTypeSpec("roles", "Role", ResourceTypes.ROLE, Config::getRoles),
            new ManagedTypeSpec("keys", "Key", ResourceTypes.PROJECT_KEY, Config::getKeys),
            new ManagedTypeSpec("routes", "Route", ResourceTypes.ROUTE, Config::getRoutes),
            new ManagedTypeSpec("models", "Model", ResourceTypes.MODEL, Config::getModels),
            new ManagedTypeSpec("toolsets", "ToolSet", ResourceTypes.TOOL_SET, Config::getToolsets),
            new ManagedTypeSpec("applications", "Application", ResourceTypes.APPLICATION, Config::getApplications));

    private static final List<SchemaTypeSpec> SCHEMA_TYPE_SPECS = List.of(
            new SchemaTypeSpec("schemas", "Schema", ResourceTypes.APP_TYPE_SCHEMA,
                    Config::getApplicationTypeSchemas, Config::getApplicationSchemaAliasesById),
            new SchemaTypeSpec("catalog_schemas", "CatalogSchema", ResourceTypes.CATALOG_SCHEMA,
                    Config::getCatalogSchemas, Config::getCatalogSchemaAliasesById));

    private final ProxyContext context;
    private final ConfigAuthorizationService authorizationService;
    private final MergedConfigStore mergedConfigStore;
    private final AsyncTaskExecutor taskExecutor;
    private final LockService lockService;
    private final AdminApplyController applier;

    public ConfigFileMigrateController(ProxyContext context,
                                       ConfigAuthorizationService authorizationService,
                                       MergedConfigStore mergedConfigStore,
                                       AsyncTaskExecutor taskExecutor,
                                       LockService lockService,
                                       AdminApplyController applier) {
        this.context = context;
        this.authorizationService = authorizationService;
        this.mergedConfigStore = mergedConfigStore;
        this.taskExecutor = taskExecutor;
        this.lockService = lockService;
        this.applier = applier;
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/admin/config/file/migrate",
            operationId = "migrateFileConfig",
            tags = {"Admin"},
            requestBody = @ApiSchema(implementation = ConfigFileMigrateRequest.class),
            responses = {
                    @ApiResponse(code = 200, description = "Migration report", body = @ApiSchema(implementation = ConfigFileMigrateResponse.class)),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 500)
            },
            extensions = {
                    @ApiExtension(name = "x-preview", value = "true")
            }
    )
    public Future<?> handle() {
        if (!authorizationService.isAdmin(context)) {
            context.respond(HttpStatus.FORBIDDEN, "Forbidden");
            return Future.succeededFuture();
        }
        context.getRequest().body()
                .onSuccess(this::process)
                .onFailure(error -> context.respond(HttpStatus.BAD_REQUEST,
                        "Failed to read request body: " + error.getMessage()));
        return Future.succeededFuture();
    }

    private void process(Buffer body) {
        ConfigFileMigrateRequest request;
        try {
            String text = body.toString(StandardCharsets.UTF_8);
            request = ProxyUtil.MAPPER.readValue(text.isEmpty() ? "{}" : text, ConfigFileMigrateRequest.class);
        } catch (JsonProcessingException e) {
            context.respond(HttpStatus.BAD_REQUEST, "Invalid JSON at " + locationOf(e));
            return;
        }

        Set<String> requestedTypes;
        try {
            requestedTypes = resolveTypes(request.types());
        } catch (IllegalArgumentException e) {
            context.respond(HttpStatus.BAD_REQUEST, e.getMessage());
            return;
        }
        boolean dryRun = Boolean.TRUE.equals(request.dryRun());

        taskExecutor.submit(() -> lockService.underBucketLocks(MergedConfigStore.ADMIN_BUCKET_LOCATIONS,
                () -> runMigration(requestedTypes, dryRun)))
                .onSuccess(response -> context.respond(HttpStatus.OK, response))
                .onFailure(error -> {
                    if (error instanceof HttpException ex) {
                        context.respond(ex);
                    } else {
                        context.respond(HttpStatus.INTERNAL_SERVER_ERROR, error.getMessage());
                    }
                });
    }

    private static Set<String> resolveTypes(List<String> types) {
        if (types == null || types.isEmpty() || types.contains("all")) {
            return new LinkedHashSet<>(ALL_TYPES);
        }
        Set<String> result = new LinkedHashSet<>();
        for (String type : types) {
            if (!ALL_TYPES.contains(type)) {
                throw new IllegalArgumentException("Unsupported type: " + type);
            }
            result.add(type);
        }
        return result;
    }

    private ConfigFileMigrateResponse runMigration(Set<String> requestedTypes, boolean dryRun) {
        Config fileConfig = mergedConfigStore.getFileSourcedConfig();
        if (fileConfig == null) {
            return new ConfigFileMigrateResponse(List.of());
        }
        Config live = mergedConfigStore.get();
        Config scratch = AdminApplyController.newScratch(mergedConfigStore);
        List<ConfigFileMigrateResult> results = new ArrayList<>();
        // Real (non-dry) run: candidates accumulate here instead of being written immediately, so
        // AdminApplyController.applyEntries can apply and flush them as a single partial-update swap.
        List<AdminManifest> toApply = new ArrayList<>();

        if (requestedTypes.contains("settings")) {
            collectSettings(fileConfig, scratch, dryRun, toApply, results);
        }
        for (SchemaTypeSpec spec : SCHEMA_TYPE_SPECS) {
            if (requestedTypes.contains(spec.typeKey())) {
                collectSchemas(spec, fileConfig, live, scratch, dryRun, toApply, results);
            }
        }
        for (ManagedTypeSpec spec : MANAGED_TYPE_SPECS) {
            if (requestedTypes.contains(spec.typeKey())) {
                collectManaged(spec, fileConfig, live, scratch, dryRun, toApply, results);
            }
        }

        if (!toApply.isEmpty()) {
            // A referencing kind (e.g. Model) must apply after a kind it can reference (e.g.
            // Interceptor) — see AdminApplyController.DEPENDENCY_ORDER_COMPARATOR.
            toApply.sort(AdminApplyController.DEPENDENCY_ORDER_COMPARATOR);
            List<AdminApplyController.EntityResult> appliedEntriesResults = applier.applyEntries(toApply, scratch);
            for (AdminApplyController.EntityResult result : appliedEntriesResults) {
                results.add(toMigrateResult(result));
            }
        }
        return new ConfigFileMigrateResponse(results);
    }

    private static ConfigFileMigrateResult toMigrateResult(AdminApplyController.EntityResult result) {
        boolean migrated = AdminApplyStatus.APPLIED.equals(result.status())
                || AdminApplyStatus.APPLIED_INVALID.equals(result.status());
        ConfigFileMigrateStatus status = migrated ? ConfigFileMigrateStatus.MIGRATED : ConfigFileMigrateStatus.FAILED;
        return new ConfigFileMigrateResult(result.entityId(), status, result.error());
    }

    private static ConfigFileMigrateStatus skippedStatus(boolean dryRun) {
        return dryRun ? ConfigFileMigrateStatus.WOULD_SKIP : ConfigFileMigrateStatus.SKIPPED;
    }

    private static ConfigFileMigrateStatus failedStatus(boolean dryRun) {
        return dryRun ? ConfigFileMigrateStatus.WOULD_FAIL : ConfigFileMigrateStatus.FAILED;
    }

    private void collectManaged(ManagedTypeSpec spec, Config fileConfig, Config live, Config scratch, boolean dryRun,
                                List<AdminManifest> toApply, List<ConfigFileMigrateResult> results) {
        Map<String, ?> liveEntities = spec.entities().apply(live);
        for (Map.Entry<String, ?> entry : spec.entities().apply(fileConfig).entrySet()) {
            String shortName = entry.getKey();
            String canonicalId = MergedConfigStore.canonicalId(spec.resourceType(), ResourceDescriptor.PLATFORM_BUCKET, shortName);
            if (liveEntities.containsKey(canonicalId)) {
                results.add(new ConfigFileMigrateResult(canonicalId, skippedStatus(dryRun), "already in blob"));
                continue;
            }
            // scratch (cloned from the live merged Config) still holds the file entry we're about to
            // migrate, keyed by its own short name — shadow it out first (mirroring what the real
            // blob-write path does once the migrated entity lands) so AdminApplyController's dup-id
            // check doesn't see this entity's own pre-migration self as a different entity already
            // claiming the short name.
            spec.entities().apply(scratch).remove(shortName);
            JsonNode specNode = ProxyUtil.MAPPER.valueToTree(entry.getValue());
            collect(new AdminManifest(spec.kind(), canonicalId, specNode), scratch, dryRun, toApply, results);
        }
    }

    private void collectSchemas(SchemaTypeSpec spec, Config fileConfig, Config live, Config scratch, boolean dryRun,
                                List<AdminManifest> toApply, List<ConfigFileMigrateResult> results) {
        Map<String, String> liveAliasesById = spec.aliasesById().apply(live);
        for (Map.Entry<String, String> entry : spec.schemas().apply(fileConfig).entrySet()) {
            String id = entry.getKey();
            if (liveAliasesById.containsKey(id)) {
                results.add(new ConfigFileMigrateResult(id, skippedStatus(dryRun), "already in blob"));
                continue;
            }
            JsonNode body;
            try {
                body = ProxyUtil.MAPPER.readTree(entry.getValue());
            } catch (JsonProcessingException e) {
                results.add(new ConfigFileMigrateResult(id, failedStatus(dryRun), "Failed to parse schema body"));
                continue;
            }
            String canonicalId = MergedConfigStore.canonicalId(spec.resourceType(), ResourceDescriptor.PLATFORM_BUCKET,
                    SchemaMigrationNaming.mintName(spec.resourceType(), body));
            collect(new AdminManifest(spec.kind(), canonicalId, body), scratch, dryRun, toApply, results);
        }
    }

    private void collectSettings(Config fileConfig, Config scratch, boolean dryRun,
                                 List<AdminManifest> toApply, List<ConfigFileMigrateResult> results) {
        String canonicalId = MergedConfigStore.canonicalId(ResourceTypes.GLOBAL_SETTINGS,
                ResourceDescriptor.PLATFORM_BUCKET, "global");
        if (mergedConfigStore.isSettingsFromApi()) {
            results.add(new ConfigFileMigrateResult(canonicalId, skippedStatus(dryRun), "already in blob"));
            return;
        }
        GlobalSettings settings = new GlobalSettings();
        settings.setGlobalInterceptors(fileConfig.getGlobalInterceptors());
        settings.setRetriableErrorCodes(fileConfig.getRetriableErrorCodes());
        JsonNode spec = ProxyUtil.MAPPER.valueToTree(settings);
        collect(new AdminManifest("Settings", canonicalId, spec), scratch, dryRun, toApply, results);
    }

    /**
     * dry-run: validates immediately (no write) via {@link AdminApplyController#validateOnly} and
     * reports the outcome right away. Real run: defers to {@code toApply}, applied and flushed once
     * for the whole batch by {@link AdminApplyController#applyEntries} at the end of {@link
     * #runMigration}.
     */
    private void collect(AdminManifest manifest, Config scratch, boolean dryRun,
                         List<AdminManifest> toApply, List<ConfigFileMigrateResult> results) {
        if (!dryRun) {
            toApply.add(manifest);
            return;
        }
        ValidationResult validation = AdminApplyController.validateOnly(manifest, scratch, mergedConfigStore.isSoftValidation());
        if (ValidationStatus.VALID.equals(validation.status())) {
            results.add(new ConfigFileMigrateResult(manifest.name(), ConfigFileMigrateStatus.WOULD_MIGRATE, null));
            AdminApplyController.mutateScratch(scratch, manifest);
        } else {
            results.add(new ConfigFileMigrateResult(manifest.name(), ConfigFileMigrateStatus.WOULD_FAIL, validation.error()));
        }
    }

    private static String locationOf(JsonProcessingException e) {
        return e.getLocation() == null
                ? "unknown location"
                : "line " + e.getLocation().getLineNr() + ", column " + e.getLocation().getColumnNr();
    }

    /**
     * One accessor per managed, name-addressed type, applied to three different {@link Config}
     * instances depending on purpose: {@code fileConfig} (entities to migrate), {@code live}
     * (idempotency check), and {@code scratch} (shadow removal ahead of the write) — see {@link
     * #collectManaged}.
     */
    private record ManagedTypeSpec(String typeKey, String kind, ResourceTypes resourceType,
                                   Function<Config, Map<String, ?>> entities) {}

    /**
     * One spec per schema type. Unlike {@link ManagedTypeSpec}, presence is checked against the
     * {@code $id → canonicalId} alias index, not the raw schema map, and there is no scratch shadow
     * to remove (see {@link #collectSchemas}) — so two accessors are needed here, not one.
     */
    private record SchemaTypeSpec(String typeKey, String kind, ResourceTypes resourceType,
                                  Function<Config, Map<String, String>> schemas,
                                  Function<Config, Map<String, String>> aliasesById) {}
}
