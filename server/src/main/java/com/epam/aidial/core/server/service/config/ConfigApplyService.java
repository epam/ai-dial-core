package com.epam.aidial.core.server.service.config;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.GlobalSettings;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Role;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.server.config.ConfigPostProcessor;
import com.epam.aidial.core.server.config.EntityChange;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.config.SecretFieldProcessor;
import com.epam.aidial.core.server.config.ValidationWarning;
import com.epam.aidial.core.server.data.AdminApplyStatus;
import com.epam.aidial.core.server.data.AdminManifest;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.service.AdminManagedFieldsWriteMode;
import com.epam.aidial.core.server.service.ApplicationService;
import com.epam.aidial.core.server.service.ToolSetService;
import com.epam.aidial.core.server.service.config.ConfigManifestSupport.ParsedName;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.util.UpstreamExtraDataMerger;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.epam.aidial.core.server.service.config.ConfigEntityCodec.BLOB_MAPPER;

/**
 * Real-apply engine behind the write phase of {@code /v1/admin/apply} and
 * {@code /v1/admin/config/file/migrate} — writes each config entity to blob storage directly or
 * via {@link ApplicationService}/{@link ToolSetService}. Shares its precheck validation with
 * {@link ConfigValidationService} and manifest-shape helpers (parsing, scratch, dependency
 * ordering) with {@link ConfigManifestSupport}.
 */
@Slf4j
public class ConfigApplyService {

    private final MergedConfigStore mergedConfigStore;
    private final ResourceService resourceService;
    private final SecretFieldProcessor secretFieldProcessor;
    private final boolean softValidation;
    private final ApiKeyStore apiKeyStore;
    private final ApplicationService applicationService;
    private final ToolSetService toolSetService;

    public ConfigApplyService(MergedConfigStore mergedConfigStore,
                              ResourceService resourceService,
                              SecretFieldProcessor secretFieldProcessor,
                              boolean softValidation,
                              ApiKeyStore apiKeyStore,
                              ApplicationService applicationService,
                              ToolSetService toolSetService) {
        this.mergedConfigStore = mergedConfigStore;
        this.resourceService = resourceService;
        this.secretFieldProcessor = secretFieldProcessor;
        this.softValidation = softValidation;
        this.apiKeyStore = apiKeyStore;
        this.applicationService = applicationService;
        this.toolSetService = toolSetService;
    }

    /**
     * Applies each entry via {@link #applySingle}, mutating {@code scratch} after every success so
     * later entries in the same list see earlier ones, then flushes every in-memory change as a
     * single partial-update swap. Shared by the real-apply phase of {@code /v1/admin/apply} and by
     * {@code /v1/admin/config/file/migrate}, which builds its own entry list and scratch (with any
     * shadowed file entries already removed) before calling this.
     */
    public List<EntityResult> applyEntries(List<AdminManifest> entries, Config scratch) {
        List<EntityResult> results = new ArrayList<>();
        // Collect partial-update changes per applied entity; flush as one applyBatch
        // after the apply loop so the merged Config swap happens once, after all blobs are written.
        List<EntityChange> pendingChanges = new ArrayList<>();
        GlobalSettings pendingSettings = null;
        boolean anyApplied = false;
        for (AdminManifest entry : entries) {
            EntityResult result;
            try {
                result = applySingle(entry, scratch, pendingChanges);
            } catch (Exception ex) {
                result = new EntityResult(entry.name(), AdminApplyStatus.FAILED, ex.getMessage());
            }
            results.add(result);
            if (AdminApplyStatus.APPLIED.equals(result.status()) || AdminApplyStatus.APPLIED_INVALID.equals(result.status())) {
                anyApplied = true;
                ConfigManifestSupport.mutateScratch(scratch, entry);
                if ("Settings".equals(entry.kind())) {
                    pendingSettings = ConfigEntityCodec.treeToEntity(entry.spec(), GlobalSettings.class);
                }
            }
        }
        if (anyApplied) {
            if (!pendingChanges.isEmpty()) {
                Map<String, String> failures = mergedConfigStore.applyBatch(pendingChanges);
                // Blobs are already written; if the in-memory swap of any entity failed (e.g. an
                // unforeseen type coercion error not caught by precheck) the merged Config diverges
                // from blob until the next full rebuild. Surface via log so operators see it.
                failures.forEach((id, reason) ->
                        log.warn("Partial-update swap failed for {} after blob put: {}", id, reason));
            }
            if (pendingSettings != null) {
                mergedConfigStore.applySettingsWrite(pendingSettings);
            }
        }
        return results;
    }

    private EntityResult applySingle(AdminManifest entry, Config scratch, List<EntityChange> pending) {
        String id = entry.name();
        ParsedName parsed;
        try {
            parsed = ConfigManifestSupport.parseName(entry);
        } catch (IllegalArgumentException ex) {
            return new EntityResult(id, AdminApplyStatus.FAILED, ex.getMessage());
        }
        return switch (entry.kind()) {
            case "Settings" -> applySettings(entry, id, parsed);
            case "Schema" -> applySchema(entry, id, parsed, pending, ResourceTypes.APP_TYPE_SCHEMA, scratch);
            case "CatalogSchema" -> applySchema(entry, id, parsed, pending, ResourceTypes.CATALOG_SCHEMA, scratch);
            case "Interceptor" -> applyManagedEntity(entry, id, parsed, ResourceTypes.INTERCEPTOR, Interceptor.class, scratch, pending);
            case "Role" -> applyManagedEntity(entry, id, parsed, ResourceTypes.ROLE, Role.class, scratch, pending);
            case "Route" -> applyManagedEntity(entry, id, parsed, ResourceTypes.ROUTE, Route.class, scratch, pending);
            case "Key" -> applyKey(entry, id, parsed, pending);
            case "Model" -> applyModel(entry, id, parsed, scratch, pending);
            case "ToolSet" -> applyToolSet(entry, id, parsed, scratch, pending);
            case "Application" -> applyApplication(entry, id, parsed, scratch, pending);
            default -> new EntityResult(id, AdminApplyStatus.FAILED, "Unknown kind: " + entry.kind());
        };
    }

    private EntityResult applySettings(AdminManifest entry, String id, ParsedName parsed) {
        if (!ConfigManifestSupport.SETTINGS_SINGLETON_NAME.equals(parsed.name())) {
            return new EntityResult(id, AdminApplyStatus.FAILED, "Settings name must be 'global'");
        }
        GlobalSettings settings = ConfigEntityCodec.treeToEntity(entry.spec(), GlobalSettings.class);
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.GLOBAL_SETTINGS, parsed.bucket(), parsed.location(), parsed.name());
        String blobBody = ConfigEntityCodec.serializeForBlob(settings);
        resourceService.putResource(descriptor, blobBody, EtagHeader.ANY);
        return new EntityResult(id, AdminApplyStatus.APPLIED, null);
    }

    private EntityResult applySchema(AdminManifest entry, String id, ParsedName parsed, List<EntityChange> pending,
                                     ResourceTypes type, Config scratch) {
        String validationError = ConfigManifestSupport.validateSchema(entry, parsed, scratch, type, resourceService);
        if (validationError != null) {
            return new EntityResult(id, AdminApplyStatus.FAILED, validationError);
        }
        JsonNode spec = entry.spec();
        String schemaId = MergedConfigStore.extractSchemaId(spec);
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromDecoded(
                type, parsed.bucket(), parsed.location(), parsed.name());
        String blobBody;
        try {
            blobBody = BLOB_MAPPER.writeValueAsString(spec);
        } catch (JsonProcessingException e) {
            // Drop e.getOriginalMessage() — it can echo verbatim schema content (potentially
            // submitted secrets). Surface a generic failure tied to the entity id.
            return new EntityResult(id, AdminApplyStatus.FAILED, "Failed to serialize schema for " + id);
        }
        Map<String, String> eventMetadata = Map.of("$id", schemaId);
        resourceService.putResource(descriptor, blobBody, EtagHeader.ANY, null, true, eventMetadata);
        pending.add(new EntityChange(type, schemaId, spec));
        return new EntityResult(id, AdminApplyStatus.APPLIED, null);
    }

    private <T> EntityResult applyManagedEntity(AdminManifest entry, String id, ParsedName parsed,
                                                ResourceTypes type, Class<T> entityClass, Config scratch,
                                                List<EntityChange> pending) {
        T entity = ConfigEntityCodec.treeToEntity(entry.spec(), entityClass);
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromDecoded(
                type, parsed.bucket(), parsed.location(), parsed.name());
        /// Deployment-id uniqueness only applies to INTERCEPTOR here — ROLE/ROUTE aren't deployments
        // resolved through Config.selectDeployment, so they don't share the short-name namespace.
        if (type == ResourceTypes.INTERCEPTOR) {
            String dupError = ConfigManifestSupport.validateDeploymentIdUniqueness(scratch, type, parsed);
            if (dupError != null) {
                return new EntityResult(id, AdminApplyStatus.FAILED, dupError);
            }
        }
        String blobBody = ConfigEntityCodec.serializeForBlob(entity);
        resourceService.putResource(descriptor, blobBody, EtagHeader.ANY);
        pending.add(new EntityChange(type, MergedConfigStore.resolveMapKeyFor(descriptor), entity));
        return new EntityResult(id, AdminApplyStatus.APPLIED, null);
    }

    private EntityResult applyKey(AdminManifest entry, String id, ParsedName parsed, List<EntityChange> pending) {
        Key key = ConfigEntityCodec.treeToEntity(entry.spec(), Key.class);
        if (StringUtils.isBlank(key.getKey())) {
            return new EntityResult(id, AdminApplyStatus.FAILED, "Key.key must be provided explicitly");
        }
        if (StringUtils.isBlank(key.getProject())) {
            return new EntityResult(id, AdminApplyStatus.FAILED, "Project key is undefined");
        }
        if (StringUtils.isBlank(key.getRole()) && (key.getRoles() == null || key.getRoles().isEmpty())) {
            return new EntityResult(id, AdminApplyStatus.FAILED,
                    "Invalid key: at least one role must be assigned to the key " + key.getProject());
        }
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.PROJECT_KEY, parsed.bucket(), parsed.location(), parsed.name());
        String secret = key.getKey();
        // Recover the prior plaintext secret so a rotation can revoke the old auth bearer
        // (FINDING #2). Deliberately non-fatal: a corrupt prior blob must NOT abort the rotation —
        // the new secret is authoritative and any stale entry is cleaned at the next full rebuild.
        String oldSecret = null;
        String existingBody = resourceService.getResource(descriptor);
        if (existingBody != null) {
            try {
                Key prior = ConfigEntityCodec.treeToEntity(
                        BLOB_MAPPER.readTree(existingBody), Key.class);
                secretFieldProcessor.decryptFields(prior, descriptor);
                oldSecret = prior.getKey();
            } catch (Exception e) {
                log.warn("Could not recover prior key secret for rotation at {}; "
                        + "proceeding with new secret as authoritative", descriptor.getUrl());
            }
        }
        secretFieldProcessor.encryptFields(key, descriptor);
        String blobBody = ConfigEntityCodec.serializeForBlob(key);
        resourceService.putResource(descriptor, blobBody, EtagHeader.ANY);
        ApiKeyData data = new ApiKeyData();
        data.setOriginalKey(key);
        apiKeyStore.addOrUpdateKey(secret, data);
        if (oldSecret != null && !oldSecret.isBlank() && !oldSecret.equals(secret)) {
            apiKeyStore.removeKey(oldSecret);
        }
        // Slice 4S.4: decrypt-in-place after blob put so the partial-update path receives a
        // fully-plaintext Key. decryptValue is idempotent on plaintext fields.
        secretFieldProcessor.decryptFields(key, descriptor);
        pending.add(new EntityChange(ResourceTypes.PROJECT_KEY, MergedConfigStore.canonicalId(descriptor), key));
        return new EntityResult(id, AdminApplyStatus.APPLIED, null);
    }

    private EntityResult applyModel(AdminManifest entry, String id, ParsedName parsed, Config scratch, List<EntityChange> pending) {
        Model model = ConfigEntityCodec.treeToEntity(entry.spec(), Model.class);
        List<ValidationWarning> warnings = new ArrayList<>();
        ConfigPostProcessor.validatePricing(model, warnings);
        ConfigPostProcessor.validateUpstreamInterfaces(model, warnings);
        ConfigPostProcessor.validateCrossReferences(model, scratch, warnings);
        UpstreamExtraDataMerger.validateNoOverlap(model);
        boolean invalid = !warnings.isEmpty();
        if (invalid && !softValidation) {
            return new EntityResult(id, AdminApplyStatus.FAILED, ConfigManifestSupport.joinWarnings(warnings));
        }
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.MODEL, parsed.bucket(), parsed.location(), parsed.name());
        String dupError = ConfigManifestSupport.validateDeploymentIdUniqueness(scratch, ResourceTypes.MODEL, parsed);
        if (dupError != null) {
            return new EntityResult(id, AdminApplyStatus.FAILED, dupError);
        }
        secretFieldProcessor.encryptFields(model, descriptor);
        String blobBody = ConfigEntityCodec.serializeForBlob(model);
        resourceService.putResource(descriptor, blobBody, EtagHeader.ANY);
        // Slice 4S.4: decrypt-in-place so partial-update receives plaintext upstream secrets.
        secretFieldProcessor.decryptFields(model, descriptor);
        pending.add(new EntityChange(ResourceTypes.MODEL, MergedConfigStore.resolveMapKeyFor(descriptor), model));
        return new EntityResult(id, invalid ? AdminApplyStatus.APPLIED_INVALID : AdminApplyStatus.APPLIED, null);
    }

    private EntityResult applyApplication(AdminManifest entry, String id, ParsedName parsed, Config scratch, List<EntityChange> pending) {
        Application application = ConfigEntityCodec.treeToEntity(entry.spec(), Application.class);
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.APPLICATION, parsed.bucket(), parsed.location(), parsed.name());
        // Only the platform bucket is materialized into MergedConfigStore (see EntityLocationStrategy) —
        // public-bucket apps stay outside it and are served lazily by ApplicationService, so they're
        // exempt from deployment-id uniqueness and pushing them into `pending` below would spuriously
        // duplicate them in config.getApplications()-backed listings (e.g. ApplicationController/
        // DeploymentController) until the next full rebuild.
        boolean platform = ResourceDescriptor.PLATFORM_BUCKET.equals(parsed.bucket());
        if (platform) {
            String dupError = ConfigManifestSupport.validateDeploymentIdUniqueness(scratch, ResourceTypes.APPLICATION, parsed);
            if (dupError != null) {
                return new EntityResult(id, AdminApplyStatus.FAILED, dupError);
            }
        }
        // Bulk admin apply is always admin context — preserve forwardAuthToken if the manifest set it.
        applicationService.putApplication(descriptor, EtagHeader.ANY, null, application, true,
                AdminManagedFieldsWriteMode.AUTHORITATIVE);
        if (platform) {
            Application decrypted = applicationService.getApplicationWithDecryptedSecrets(descriptor).getValue();
            pending.add(new EntityChange(ResourceTypes.APPLICATION, MergedConfigStore.resolveMapKeyFor(descriptor), decrypted));
        }
        return new EntityResult(id, AdminApplyStatus.APPLIED, null);
    }

    private EntityResult applyToolSet(AdminManifest entry, String id, ParsedName parsed, Config scratch, List<EntityChange> pending) {
        ToolSet toolSet = ConfigEntityCodec.treeToEntity(entry.spec(), ToolSet.class);
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.TOOL_SET, parsed.bucket(), parsed.location(), parsed.name());
        // Same rationale as applyApplication above — only platform-bucket toolsets belong in
        // MergedConfigStore / are subject to deployment-id uniqueness.
        boolean platform = ResourceDescriptor.PLATFORM_BUCKET.equals(parsed.bucket());
        if (platform) {
            String dupError = ConfigManifestSupport.validateDeploymentIdUniqueness(scratch, ResourceTypes.TOOL_SET, parsed);
            if (dupError != null) {
                return new EntityResult(id, AdminApplyStatus.FAILED, dupError);
            }
        }
        toolSetService.putToolSet(descriptor, EtagHeader.ANY, null, toolSet, true);
        if (platform) {
            ToolSet decrypted = toolSetService.getToolSetWithDecryptedAuthSettings(descriptor).getValue();
            pending.add(new EntityChange(ResourceTypes.TOOL_SET, MergedConfigStore.resolveMapKeyFor(descriptor), decrypted));
        }
        return new EntityResult(id, AdminApplyStatus.APPLIED, null);
    }

    public record EntityResult(String entityId, AdminApplyStatus status, String error) {}
}
