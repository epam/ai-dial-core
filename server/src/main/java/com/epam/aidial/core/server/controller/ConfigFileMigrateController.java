package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.GlobalSettings;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.openapi.annotations.ApiExtension;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.config.SchemaMigrationNameResolver;
import com.epam.aidial.core.server.data.AdminApplyStatus;
import com.epam.aidial.core.server.data.AdminManifest;
import com.epam.aidial.core.server.data.ValidationResult;
import com.epam.aidial.core.server.data.ValidationStatus;
import com.epam.aidial.core.server.data.config.migration.ConfigFileMigrateRequest;
import com.epam.aidial.core.server.data.config.migration.ConfigFileMigrateResponse;
import com.epam.aidial.core.server.data.config.migration.ConfigFileMigrateResult;
import com.epam.aidial.core.server.data.config.migration.ConfigFileMigrateStatus;
import com.epam.aidial.core.server.security.ConfigAuthorizationService;
import com.epam.aidial.core.server.service.config.ConfigApplyService;
import com.epam.aidial.core.server.service.config.ConfigEntityCodec;
import com.epam.aidial.core.server.service.config.ConfigManifestSupport;
import com.epam.aidial.core.server.service.config.ConfigValidationService;
import com.epam.aidial.core.server.util.HashUtil;
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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static com.epam.aidial.core.server.service.config.ConfigEntityCodec.BLOB_MAPPER;

/**
 * Admin-triggered, on-demand copy of file-defined config entities into the {@code platform} blob
 * bucket. Migration is never automatic on startup — that would resurrect an API-deleted entity on
 * restart. Reuses {@link ConfigApplyService}'s per-kind write pipeline for the real run and
 * {@link ConfigValidationService} for the dry-run precheck, plus manifest-shape helpers (parsing,
 * scratch, dependency ordering) from {@link ConfigManifestSupport}.
 */
// for review env
public class ConfigFileMigrateController {

    public static final int KEY_HASH_LENGTH = 12;

    private static final List<String> ALL_TYPES = List.of(
            "settings", "schemas", "catalog_schemas", "interceptors", "roles", "keys", "routes",
            "models", "toolsets", "applications");

    private static final List<ManagedTypeSpec> MANAGED_TYPE_SPECS = List.of(
            new ManagedTypeSpec("interceptors", "Interceptor", ResourceTypes.INTERCEPTOR, Config::getInterceptors),
            new ManagedTypeSpec("roles", "Role", ResourceTypes.ROLE, Config::getRoles),
            new ManagedTypeSpec("routes", "Route", ResourceTypes.ROUTE, Config::getRoutes),
            new ManagedTypeSpec("models", "Model", ResourceTypes.MODEL, Config::getModels),
            new ManagedTypeSpec("toolsets", "ToolSet", ResourceTypes.TOOL_SET, Config::getToolsets),
            new ManagedTypeSpec("applications", "Application", ResourceTypes.APPLICATION, Config::getApplications));

    private static final List<SchemaTypeSpec> SCHEMA_TYPE_SPECS = List.of(
            new SchemaTypeSpec("schemas", "Schema", ResourceTypes.APP_TYPE_SCHEMA, Config::getApplicationTypeSchemas),
            new SchemaTypeSpec("catalog_schemas", "CatalogSchema", ResourceTypes.CATALOG_SCHEMA, Config::getCatalogSchemas));

    private final ProxyContext context;
    private final ConfigAuthorizationService authorizationService;
    private final MergedConfigStore mergedConfigStore;
    private final AsyncTaskExecutor taskExecutor;
    private final LockService lockService;
    private final ConfigApplyService applyService;
    private final ConfigValidationService validationService;
    private final ResourceService resourceService;

    public ConfigFileMigrateController(Proxy proxy, ProxyContext context) {
        this.context = context;
        this.authorizationService = proxy.getConfigAuthService();
        this.mergedConfigStore = (MergedConfigStore) proxy.getConfigStore();
        this.taskExecutor = proxy.getTaskExecutor();
        this.lockService = proxy.getLockService();
        this.applyService = proxy.getConfigApplyService();
        this.validationService = proxy.getConfigValidationService();
        this.resourceService = proxy.getResourceService();
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
        Config scratch = ConfigManifestSupport.newScratch(mergedConfigStore);
        List<ConfigFileMigrateResult> results = new ArrayList<>();
        // Real (non-dry) run: candidates accumulate here instead of being written immediately, so
        // ConfigApplyService.applyEntries can apply and flush them as a single partial-update swap.
        List<AdminManifest> toApply = new ArrayList<>();

        if (requestedTypes.contains("settings")) {
            collectSettings(fileConfig, scratch, dryRun, toApply, results);
        }
        if (requestedTypes.contains("keys")) {
            collectKeys(fileConfig, scratch, dryRun, toApply, results);
        }
        for (SchemaTypeSpec spec : SCHEMA_TYPE_SPECS) {
            if (requestedTypes.contains(spec.typeKey())) {
                collectSchemas(spec, fileConfig, scratch, dryRun, toApply, results);
            }
        }
        for (ManagedTypeSpec spec : MANAGED_TYPE_SPECS) {
            if (requestedTypes.contains(spec.typeKey())) {
                collectManaged(spec, fileConfig, live, scratch, dryRun, toApply, results);
            }
        }

        if (!toApply.isEmpty()) {
            // A referencing kind (e.g. Model) must apply after a kind it can reference (e.g.
            // Interceptor) — see ConfigManifestSupport.DEPENDENCY_ORDER_COMPARATOR.
            toApply.sort(ConfigManifestSupport.DEPENDENCY_ORDER_COMPARATOR);
            List<ConfigApplyService.EntityResult> appliedEntriesResults = applyService.applyEntries(toApply, scratch);
            for (ConfigApplyService.EntityResult result : appliedEntriesResults) {
                results.add(toMigrateResult(result));
            }
        }
        return new ConfigFileMigrateResponse(results);
    }

    private static ConfigFileMigrateResult toMigrateResult(ConfigApplyService.EntityResult result) {
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
        boolean shortNameKeyed = MergedConfigStore.isShortNameKeyed(spec.resourceType());
        for (Map.Entry<String, ?> entry : spec.entities().apply(fileConfig).entrySet()) {
            String shortName = entry.getKey();
            String canonicalId = MergedConfigStore.canonicalId(spec.resourceType(), ResourceDescriptor.PLATFORM_BUCKET, shortName);
            // For the 5 short-name-keyed types (see MergedConfigStore.isShortNameKeyed), a file entry
            // and a platform-blob entry share the same bare map key in the merged Config, so a merged
            // map lookup can't tell "file-only" from "already migrated" apart — check the blob directly.
            // Keys/routes keep the canonical id as their map key regardless of source, so a merged
            // lookup against the platform-bucket canonical id is already precise.
            boolean alreadyInBlob = shortNameKeyed
                    ? resourceService.hasResource(platformDescriptor(spec.resourceType(), shortName))
                    : liveEntities.containsKey(canonicalId);
            if (alreadyInBlob) {
                results.add(new ConfigFileMigrateResult(canonicalId, skippedStatus(dryRun), "already in blob"));
                continue;
            }
            JsonNode specNode = ProxyUtil.MAPPER.valueToTree(entry.getValue());
            collect(new AdminManifest(spec.kind(), canonicalId, specNode), scratch, dryRun, toApply, results);
        }
    }

    private void collectKeys(Config fileConfig, Config scratch, boolean dryRun,
                             List<AdminManifest> toApply, List<ConfigFileMigrateResult> results) {
        BlobKeySecrets blobKeySecrets = listBlobKeySecrets();
        Set<String> existingSecrets = blobKeySecrets.secrets();
        Map<String, String> secretByName = new HashMap<>(blobKeySecrets.secretByName());
        for (Map.Entry<String, Key> entry : fileConfig.getKeys().entrySet()) {
            String secret = entry.getKey();
            Key fileKey = entry.getValue();
            String hash = HashUtil.sha256Hex(secret).substring(0, KEY_HASH_LENGTH);
            String project = fileKey.getProject();
            String shortName = (project != null && ConfigResourceController.ENTITY_NAME_PATTERN.matcher(project).matches())
                    ? project.toLowerCase(Locale.ROOT) + "-" + hash
                    : hash;
            String canonicalId = MergedConfigStore.canonicalId(ResourceTypes.PROJECT_KEY, ResourceDescriptor.PLATFORM_BUCKET, shortName);
            // A matching secret may already live under an unrelated, admin-chosen blob name (e.g.
            // created directly via the API) — a canonicalId/name check alone would miss that and
            // create a duplicate blob for the same secret, so idempotency here is secret-based.
            if (existingSecrets.contains(secret)) {
                results.add(new ConfigFileMigrateResult(canonicalId, skippedStatus(dryRun), "already in blob"));
                continue;
            }
            // Reaching here means `secret` isn't in existingSecrets, so any occupant found under
            // shortName is necessarily a different secret — a genuine truncated-hash collision.
            String occupant = secretByName.get(shortName);
            if (occupant != null) {
                results.add(new ConfigFileMigrateResult(canonicalId, failedStatus(dryRun),
                        "Derived blob name collides with an existing key under a different secret"));
                continue;
            }
            secretByName.put(shortName, secret);
            // Key.key is @JsonProperty(WRITE_ONLY): MAPPER.valueToTree always drops it on
            // serialization regardless of the Java object's field state — inject it directly.
            ObjectNode specNode = ProxyUtil.MAPPER.valueToTree(fileKey);
            specNode.put("key", secret);
            collect(new AdminManifest("Key", canonicalId, specNode), scratch, dryRun, toApply, results);
        }
    }

    private BlobKeySecrets listBlobKeySecrets() {
        NamedValues values = listBlobValues(ResourceTypes.PROJECT_KEY, (body, name) -> {
            Key key = ConfigEntityCodec.treeToEntity(BLOB_MAPPER.readTree(body), Key.class);
            ResourceDescriptor descriptor = platformDescriptor(ResourceTypes.PROJECT_KEY, name);
            mergedConfigStore.getSecretFieldProcessor().decryptFields(key, descriptor);
            return StringUtils.isNotBlank(key.getKey()) ? key.getKey() : null;
        });
        return new BlobKeySecrets(values.values(), values.valuesByName());
    }

    private void collectSchemas(SchemaTypeSpec spec, Config fileConfig, Config scratch, boolean dryRun,
                                List<AdminManifest> toApply, List<ConfigFileMigrateResult> results) {
        Map<String, String> fileSchemas = spec.schemas().apply(fileConfig);
        // The merged Config's schema map folds file- and blob-sourced entries under the same $id key,
        // so "already migrated" can only be answered by listing the actual platform-bucket blobs.
        BlobSchemas blobSchemas = listBlobSchemas(spec.resourceType());
        Map<String, String> notYetMigrated = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : fileSchemas.entrySet()) {
            if (blobSchemas.ids().contains(entry.getKey())) {
                results.add(new ConfigFileMigrateResult(entry.getKey(), skippedStatus(dryRun), "already in blob"));
            } else {
                notYetMigrated.put(entry.getKey(), entry.getValue());
            }
        }

        Map<String, SchemaMigrationNameResolver.Resolution> resolutions =
                SchemaMigrationNameResolver.resolveNames(notYetMigrated, blobSchemas.idsByName());
        Map<String, String> scratchSchemas = spec.schemas().apply(scratch);
        for (Map.Entry<String, String> entry : notYetMigrated.entrySet()) {
            String id = entry.getKey();
            SchemaMigrationNameResolver.Resolution resolution = resolutions.get(id);
            if (!resolution.isValid()) {
                results.add(new ConfigFileMigrateResult(id, failedStatus(dryRun), resolution.error()));
                continue;
            }
            JsonNode body;
            try {
                body = ProxyUtil.MAPPER.readTree(entry.getValue());
            } catch (JsonProcessingException e) {
                results.add(new ConfigFileMigrateResult(id, failedStatus(dryRun), "Failed to parse schema body"));
                continue;
            }
            // MergedConfigStore.validateSchemaId (invoked by AdminApplyController while applying this
            // entry) rejects a non-update write whose $id is already present in scratch's schema map,
            // as a duplicate-$id guard. Since scratch starts as a copy of the merged/live config, the
            // file-sourced schema being migrated already occupies this exact $id there — remove the
            // shadow so validation sees a genuinely new blob entry, not a collision with itself.
            scratchSchemas.remove(id);
            String canonicalId = MergedConfigStore.canonicalId(spec.resourceType(), ResourceDescriptor.PLATFORM_BUCKET,
                    resolution.blobName());
            collect(new AdminManifest(spec.kind(), canonicalId, body), scratch, dryRun, toApply, results);
        }
    }

    private BlobSchemas listBlobSchemas(ResourceTypes type) {
        NamedValues values = listBlobValues(type,
                (body, name) -> MergedConfigStore.extractSchemaId(BLOB_MAPPER.readTree(body)));
        return new BlobSchemas(values.values(), values.valuesByName());
    }

    private NamedValues listBlobValues(ResourceTypes type, BlobValueExtractor extractor) {
        ResourceDescriptor folder = platformDescriptor(type, "");
        Set<String> values = new HashSet<>();
        Map<String, String> valuesByName = new HashMap<>();
        for (Pair<ResourceItemMetadata, String> item : resourceService.listResources(folder, ignored -> { })) {
            String body = item.getValue();
            if (body == null) {
                continue;
            }
            String name = item.getKey().getName();
            try {
                String value = extractor.extract(body, name);
                if (value != null) {
                    values.add(value);
                    valuesByName.put(name, value);
                }
            } catch (Exception e) {
                // Unparseable/undecryptable blob — ignore for idempotency purposes.
            }
        }
        return new NamedValues(values, valuesByName);
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
     * dry-run: validates immediately (no write) via {@link ConfigValidationService#validateOnly} and
     * reports the outcome right away. Real run: defers to {@code toApply}, applied and flushed once
     * for the whole batch by {@link ConfigApplyService#applyEntries} at the end of {@link
     * #runMigration}.
     */
    private void collect(AdminManifest manifest, Config scratch, boolean dryRun,
                         List<AdminManifest> toApply, List<ConfigFileMigrateResult> results) {
        if (!dryRun) {
            toApply.add(manifest);
            return;
        }
        ValidationResult validation = validationService.validateOnly(manifest, scratch);
        if (ValidationStatus.VALID.equals(validation.status())) {
            results.add(new ConfigFileMigrateResult(manifest.name(), ConfigFileMigrateStatus.WOULD_MIGRATE, null));
            ConfigManifestSupport.mutateScratch(scratch, manifest);
        } else {
            results.add(new ConfigFileMigrateResult(manifest.name(), ConfigFileMigrateStatus.WOULD_FAIL, validation.error()));
        }
    }

    private static String locationOf(JsonProcessingException e) {
        return e.getLocation() == null
                ? "unknown location"
                : "line " + e.getLocation().getLineNr() + ", column " + e.getLocation().getColumnNr();
    }

    private static ResourceDescriptor platformDescriptor(ResourceTypes type, String name) {
        return ResourceDescriptorFactory.fromDecoded(type, ResourceDescriptor.PLATFORM_BUCKET,
                ResourceDescriptor.PLATFORM_LOCATION, name);
    }

    /**
     * One accessor per managed, name-addressed type, applied to {@code fileConfig} for the entities to
     * migrate and to {@code live} for the canonical-id-keyed idempotency check — see
     * {@link #collectManaged}. The short-name-keyed types instead check blob existence directly, since
     * the merged map can't distinguish a file entry from an already-migrated blob entry sharing the
     * same short name.
     */
    private record ManagedTypeSpec(String typeKey, String kind, ResourceTypes resourceType,
                                   Function<Config, Map<String, ?>> entities) {}

    /**
     * One spec per schema type. The schema map is keyed by {@code $id} in {@code fileConfig}, but
     * idempotency can't be checked against the merged/live map — see {@link #collectSchemas}.
     */
    private record SchemaTypeSpec(String typeKey, String kind, ResourceTypes resourceType,
                                  Function<Config, Map<String, String>> schemas) {}

    /**
     * Every {@code $id} found across the platform-bucket blobs of a schema type, indexed two ways for
     * {@link #collectSchemas}: {@code ids} for the already-migrated check, {@code idsByName} since the
     * blob name a migrated schema ends up under is derived from its {@code $id} rather than being a
     * single-descriptor lookup.
     */
    private record BlobSchemas(Set<String> ids, Map<String, String> idsByName) {}

    /**
     * Every existing {@code PROJECT_KEY} blob's decrypted secret, indexed two ways for {@link
     * #collectKeys}: {@code secrets} for the secret-based idempotency check, {@code secretByName}
     * for the truncated-hash collision guard (which secret currently occupies a given blob name).
     */
    private record BlobKeySecrets(Set<String> secrets, Map<String, String> secretByName) {}

    /**
     * Generic result of {@link #listBlobValues}, before it's wrapped in the domain-specific {@link
     * BlobSchemas}/{@link BlobKeySecrets} at each call site for readability.
     */
    private record NamedValues(Set<String> values, Map<String, String> valuesByName) {}

    @FunctionalInterface
    private interface BlobValueExtractor {
        String extract(String body, String name) throws Exception;
    }
}
