package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.GlobalSettings;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Role;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.openapi.annotations.ApiExtension;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.config.ConfigPostProcessor;
import com.epam.aidial.core.server.config.EntityChange;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.config.SecretFieldProcessor;
import com.epam.aidial.core.server.config.ValidationWarning;
import com.epam.aidial.core.server.data.AdminApplyRequest;
import com.epam.aidial.core.server.data.AdminApplyResponse;
import com.epam.aidial.core.server.data.AdminApplyResult;
import com.epam.aidial.core.server.data.AdminApplyStatus;
import com.epam.aidial.core.server.data.AdminManifest;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.ValidationResult;
import com.epam.aidial.core.server.data.ValidationStatus;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.security.ConfigAuthorizationService;
import com.epam.aidial.core.server.service.AdminManagedFieldsWriteMode;
import com.epam.aidial.core.server.service.ApplicationService;
import com.epam.aidial.core.server.service.ToolSetService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.util.UpstreamExtraDataMerger;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class AdminApplyController {

    private static final String SETTINGS_SINGLETON_NAME = "global";

    static final Map<String, Integer> DEPENDENCY_ORDER = Map.of(
            "Settings", 0,
            "Schema", 1,
            "Interceptor", 2,
            "Role", 3,
            "Key", 4,
            "Route", 5,
            "Model", 6,
            "ToolSet", 7,
            "Application", 8);

    static final Map<String, String> KIND_URL_SEGMENT = Map.of(
            "Settings", "settings",
            "Schema", "schemas",
            "Interceptor", "interceptors",
            "Role", "roles",
            "Key", "keys",
            "Route", "routes",
            "Model", "models",
            "ToolSet", "toolsets",
            "Application", "applications");

    private final ProxyContext context;
    private final ConfigAuthorizationService authorizationService;
    private final MergedConfigStore mergedConfigStore;
    private final ResourceService resourceService;
    private final AsyncTaskExecutor taskExecutor;
    private final SecretFieldProcessor secretFieldProcessor;
    private final boolean softValidation;
    private final ApiKeyStore apiKeyStore;
    private final ApplicationService applicationService;
    private final ToolSetService toolSetService;
    private final LockService lockService;

    public AdminApplyController(ProxyContext context,
                                ConfigAuthorizationService authorizationService,
                                MergedConfigStore mergedConfigStore,
                                ResourceService resourceService,
                                AsyncTaskExecutor taskExecutor,
                                SecretFieldProcessor secretFieldProcessor,
                                boolean softValidation,
                                ApiKeyStore apiKeyStore,
                                ApplicationService applicationService,
                                ToolSetService toolSetService,
                                LockService lockService) {
        this.context = context;
        this.authorizationService = authorizationService;
        this.mergedConfigStore = mergedConfigStore;
        this.resourceService = resourceService;
        this.taskExecutor = taskExecutor;
        this.secretFieldProcessor = secretFieldProcessor;
        this.softValidation = softValidation;
        this.apiKeyStore = apiKeyStore;
        this.applicationService = applicationService;
        this.toolSetService = toolSetService;
        this.lockService = lockService;
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/admin/apply",
            operationId = "applyConfigManifests",
            tags = {"Admin"},
            requestBody = @ApiSchema(implementation = AdminApplyRequest.class),
            responses = {
                    @ApiResponse(code = 200, description = "Application successful", body = @ApiSchema(implementation = AdminApplyResponse.class)),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 422, description = "Precheck failed", body = @ApiSchema(implementation = AdminApplyResponse.class)),
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
        AdminApplyRequest request;
        try {
            String text = body.toString(StandardCharsets.UTF_8);
            request = ProxyUtil.MAPPER.readValue(text.isEmpty() ? "{}" : text, AdminApplyRequest.class);
        } catch (JsonProcessingException e) {
            // getOriginalMessage() echoes the offending token verbatim, which can leak submitted
            // secrets back into responses and logs — surface only the parse location.
            context.respond(HttpStatus.BAD_REQUEST, "Invalid JSON at " + locationOf(e));
            return;
        }

        if (request.manifests() == null) {
            context.respond(HttpStatus.BAD_REQUEST, "Missing or invalid 'manifests' array");
            return;
        }

        // Treat missing precheck value as true
        boolean precheck = request.precheck() == null || request.precheck();

        List<AdminManifest> entries = request.manifests();
        for (int i = 0; i < entries.size(); i++) {
            AdminManifest entry = entries.get(i);
            if (entry.kind() == null) {
                context.respond(HttpStatus.BAD_REQUEST, "manifests[" + i + "].kind must be a string");
                return;
            }
            if ("Bundle".equals(entry.kind())) {
                context.respond(HttpStatus.BAD_REQUEST, "Bundle kind is not allowed in /v1/admin/apply");
                return;
            }
        }

        taskExecutor.submit(() -> lockService.underBucketLocks(MergedConfigStore.ADMIN_BUCKET_LOCATIONS,
                () -> applyBatch(precheck, entries)))
                .onSuccess(result -> context.respond(result.status(), result.body()))
                .onFailure(error -> {
                    if (error instanceof HttpException ex) {
                        context.respond(ex);
                    } else {
                        context.respond(HttpStatus.INTERNAL_SERVER_ERROR, error.getMessage());
                    }
                });
    }

    private ApplyResponse applyBatch(boolean precheck, List<AdminManifest> rawEntries) {
        List<AdminManifest> entries = new ArrayList<>(rawEntries);
        entries.sort(Comparator.comparingInt(e -> DEPENDENCY_ORDER.getOrDefault(e.kind(), 99)));

        Config scratch = newScratch(mergedConfigStore);
        List<EntityResult> results = new ArrayList<>();

        if (precheck) {
            boolean anyFailure = false;
            for (AdminManifest entry : entries) {
                ValidationResult result = validateOnly(entry, scratch, softValidation);
                if (!ValidationStatus.VALID.equals(result.status())) {
                    anyFailure = true;
                    // Mirror /v1/admin/validate: the offending entry stays FAILED (carrying its
                    // error); only the valid siblings collapse to "skipped" below.
                    results.add(new EntityResult(result.entityId(), AdminApplyStatus.FAILED, result.error()));
                } else {
                    // Mutate scratch so subsequent precheck entries see prior ones — even though we
                    // aren't writing yet, reference resolution depends on the cumulative scratch.
                    mutateScratch(scratch, entry);
                    results.add(new EntityResult(result.entityId(), AdminApplyStatus.SKIPPED, null));
                }
            }
            if (anyFailure) {
                return buildResponse(HttpStatus.UNPROCESSABLE_ENTITY, results);
            }
            // Precheck passed — wipe and re-run as real writes.
            scratch = newScratch(mergedConfigStore);
            results.clear();
        }

        boolean anyApplied = false;
        // Slice 4S.4: collect partial-update changes per applied entity; flush as one applyBatch
        // after the apply loop so the merged Config swap happens once, after all blobs are written.
        List<EntityChange> pendingChanges = new ArrayList<>();
        GlobalSettings pendingSettings = null;
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
                mutateScratch(scratch, entry);
                if ("Settings".equals(entry.kind())) {
                    pendingSettings = ConfigResourceController.treeToEntity(entry.spec(), GlobalSettings.class);
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
        return buildResponse(HttpStatus.OK, results);
    }

    static Config newScratch(MergedConfigStore mergedConfigStore) {
        Config live = mergedConfigStore.get();
        Config scratch = new Config();
        if (live != null) {
            scratch.setModels(new HashMap<>(live.getModels()));
            scratch.setInterceptors(new HashMap<>(live.getInterceptors()));
            scratch.setApplicationTypeSchemas(new HashMap<>(live.getApplicationTypeSchemas()));
            scratch.setApplications(new HashMap<>(live.getApplications()));
            scratch.setToolsets(new HashMap<>(live.getToolsets()));
            scratch.setRoles(new HashMap<>(live.getRoles()));
            scratch.setKeys(new HashMap<>(live.getKeys()));
            scratch.getRoutes().putAll(live.getRoutes());
            scratch.setGlobalInterceptors(live.getGlobalInterceptors());
            scratch.setRetriableErrorCodes(live.getRetriableErrorCodes());
        }
        return scratch;
    }

    static ValidationResult validateOnly(AdminManifest entry, Config scratch, boolean softValidation) {
        String id = entry.name();
        ParsedName parsed;
        try {
            parsed = parseName(entry);
        } catch (IllegalArgumentException ex) {
            return new ValidationResult(id, ValidationStatus.FAILED, ex.getMessage());
        }
        try {
            switch (entry.kind()) {
                case "Settings" -> {
                    if (!SETTINGS_SINGLETON_NAME.equals(parsed.name())) {
                        return new ValidationResult(id, ValidationStatus.FAILED, "Settings name must be 'global'");
                    }
                    ConfigResourceController.treeToEntity(entry.spec(), GlobalSettings.class);
                }
                case "Model" -> {
                    Model model = ConfigResourceController.treeToEntity(entry.spec(), Model.class);
                    List<ValidationWarning> warnings = new ArrayList<>();
                    ConfigPostProcessor.validateCrossReferences(model, scratch, warnings);
                    UpstreamExtraDataMerger.validateNoOverlap(model);
                    if (!warnings.isEmpty() && !softValidation) {
                        return new ValidationResult(id, ValidationStatus.FAILED, joinWarnings(warnings));
                    }
                }
                case "Interceptor" -> ConfigResourceController.treeToEntity(entry.spec(), Interceptor.class);
                case "Role" -> ConfigResourceController.treeToEntity(entry.spec(), Role.class);
                case "Route" -> ConfigResourceController.treeToEntity(entry.spec(), Route.class);
                case "Key" -> {
                    Key key = ConfigResourceController.treeToEntity(entry.spec(), Key.class);
                    if (StringUtils.isBlank(key.getKey())) {
                        return new ValidationResult(id, ValidationStatus.FAILED, "Key.key must be provided explicitly");
                    }
                    if (StringUtils.isBlank(key.getProject())) {
                        return new ValidationResult(id, ValidationStatus.FAILED, "Project key is undefined");
                    }
                    if (StringUtils.isBlank(key.getRole()) && (key.getRoles() == null || key.getRoles().isEmpty())) {
                        return new ValidationResult(id, ValidationStatus.FAILED,
                                "Invalid key: at least one role must be assigned to the key " + key.getProject());
                    }
                }
                case "Application" -> ConfigResourceController.treeToEntity(entry.spec(), Application.class);
                case "ToolSet" -> ConfigResourceController.treeToEntity(entry.spec(), ToolSet.class);
                case "Schema" -> {
                    if (!entry.spec().isObject()) {
                        return new ValidationResult(id, ValidationStatus.FAILED, "Schema spec must be a JSON object");
                    }
                }
                default -> {
                    return new ValidationResult(id, ValidationStatus.FAILED, "Unknown kind: " + entry.kind());
                }
            }
        } catch (HttpException ex) {
            return new ValidationResult(id, ValidationStatus.FAILED, ex.getMessage());
        }
        return new ValidationResult(id, ValidationStatus.VALID, null);
    }

    private EntityResult applySingle(AdminManifest entry, Config scratch, List<EntityChange> pending) {
        String id = entry.name();
        ParsedName parsed;
        try {
            parsed = parseName(entry);
        } catch (IllegalArgumentException ex) {
            return new EntityResult(id, AdminApplyStatus.FAILED, ex.getMessage());
        }
        return switch (entry.kind()) {
            case "Settings" -> applySettings(entry, id, parsed);
            case "Schema" -> applySchema(entry, id, parsed, pending);
            case "Interceptor" -> applyManagedEntity(entry, id, parsed, ResourceTypes.INTERCEPTOR, Interceptor.class, pending);
            case "Role" -> applyManagedEntity(entry, id, parsed, ResourceTypes.ROLE, Role.class, pending);
            case "Route" -> applyManagedEntity(entry, id, parsed, ResourceTypes.ROUTE, Route.class, pending);
            case "Key" -> applyKey(entry, id, parsed, pending);
            case "Model" -> applyModel(entry, id, parsed, scratch, pending);
            case "ToolSet" -> applyToolSet(entry, id, parsed, pending);
            case "Application" -> applyApplication(entry, id, parsed, pending);
            default -> new EntityResult(id, AdminApplyStatus.FAILED, "Unknown kind: " + entry.kind());
        };
    }

    private EntityResult applySettings(AdminManifest entry, String id, ParsedName parsed) {
        if (!SETTINGS_SINGLETON_NAME.equals(parsed.name())) {
            return new EntityResult(id, AdminApplyStatus.FAILED, "Settings name must be 'global'");
        }
        GlobalSettings settings = ConfigResourceController.treeToEntity(entry.spec(), GlobalSettings.class);
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.GLOBAL_SETTINGS, parsed.bucket(), parsed.location(), parsed.name());
        String blobBody = ConfigResourceController.serializeForBlob(settings);
        resourceService.putResource(descriptor, blobBody, EtagHeader.ANY);
        return new EntityResult(id, AdminApplyStatus.APPLIED, null);
    }

    private EntityResult applySchema(AdminManifest entry, String id, ParsedName parsed, List<EntityChange> pending) {
        if (!entry.spec().isObject()) {
            return new EntityResult(id, AdminApplyStatus.FAILED, "Schema spec must be a JSON object");
        }
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.APP_TYPE_SCHEMA, parsed.bucket(), parsed.location(), parsed.name());
        String blobBody;
        try {
            blobBody = ProxyUtil.BLOB_MAPPER.writeValueAsString(entry.spec());
        } catch (JsonProcessingException e) {
            // Drop e.getOriginalMessage() — it can echo verbatim schema content (potentially
            // submitted secrets). Surface a generic failure tied to the entity id.
            return new EntityResult(id, AdminApplyStatus.FAILED, "Failed to serialize schema for " + id);
        }
        resourceService.putResource(descriptor, blobBody, EtagHeader.ANY);
        pending.add(new EntityChange(ResourceTypes.APP_TYPE_SCHEMA, MergedConfigStore.canonicalId(descriptor), entry.spec()));
        return new EntityResult(id, AdminApplyStatus.APPLIED, null);
    }

    private <T> EntityResult applyManagedEntity(AdminManifest entry, String id, ParsedName parsed,
                                                ResourceTypes type, Class<T> entityClass, List<EntityChange> pending) {
        T entity = ConfigResourceController.treeToEntity(entry.spec(), entityClass);
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromDecoded(
                type, parsed.bucket(), parsed.location(), parsed.name());
        String blobBody = ConfigResourceController.serializeForBlob(entity);
        resourceService.putResource(descriptor, blobBody, EtagHeader.ANY);
        pending.add(new EntityChange(type, MergedConfigStore.canonicalId(descriptor), entity));
        return new EntityResult(id, AdminApplyStatus.APPLIED, null);
    }

    private EntityResult applyKey(AdminManifest entry, String id, ParsedName parsed, List<EntityChange> pending) {
        Key key = ConfigResourceController.treeToEntity(entry.spec(), Key.class);
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
                Key prior = ConfigResourceController.treeToEntity(
                        ProxyUtil.BLOB_MAPPER.readTree(existingBody), Key.class);
                secretFieldProcessor.decryptFields(prior, descriptor);
                oldSecret = prior.getKey();
            } catch (Exception e) {
                log.warn("Could not recover prior key secret for rotation at {}; "
                        + "proceeding with new secret as authoritative", descriptor.getUrl());
            }
        }
        secretFieldProcessor.encryptFields(key, descriptor);
        String blobBody = ConfigResourceController.serializeForBlob(key);
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
        Model model = ConfigResourceController.treeToEntity(entry.spec(), Model.class);
        List<ValidationWarning> warnings = new ArrayList<>();
        ConfigPostProcessor.validateCrossReferences(model, scratch, warnings);
        UpstreamExtraDataMerger.validateNoOverlap(model);
        boolean invalid = !warnings.isEmpty();
        if (invalid && !softValidation) {
            return new EntityResult(id, AdminApplyStatus.FAILED, joinWarnings(warnings));
        }
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.MODEL, parsed.bucket(), parsed.location(), parsed.name());
        secretFieldProcessor.encryptFields(model, descriptor);
        String blobBody = ConfigResourceController.serializeForBlob(model);
        resourceService.putResource(descriptor, blobBody, EtagHeader.ANY);
        // Slice 4S.4: decrypt-in-place so partial-update receives plaintext upstream secrets.
        secretFieldProcessor.decryptFields(model, descriptor);
        pending.add(new EntityChange(ResourceTypes.MODEL, MergedConfigStore.canonicalId(descriptor), model));
        return new EntityResult(id, invalid ? AdminApplyStatus.APPLIED_INVALID : AdminApplyStatus.APPLIED, null);
    }

    private EntityResult applyApplication(AdminManifest entry, String id, ParsedName parsed, List<EntityChange> pending) {
        Application application = ConfigResourceController.treeToEntity(entry.spec(), Application.class);
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.APPLICATION, parsed.bucket(), parsed.location(), parsed.name());
        // Bulk admin apply is always admin context — preserve forwardAuthToken if the manifest set it.
        applicationService.putApplication(descriptor, EtagHeader.ANY, null, application, true,
                AdminManagedFieldsWriteMode.AUTHORITATIVE);
        Application decrypted = applicationService.getApplicationWithDecryptedSecrets(descriptor).getValue();
        pending.add(new EntityChange(ResourceTypes.APPLICATION, MergedConfigStore.canonicalId(descriptor), decrypted));
        return new EntityResult(id, AdminApplyStatus.APPLIED, null);
    }

    private EntityResult applyToolSet(AdminManifest entry, String id, ParsedName parsed, List<EntityChange> pending) {
        ToolSet toolSet = ConfigResourceController.treeToEntity(entry.spec(), ToolSet.class);
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.TOOL_SET, parsed.bucket(), parsed.location(), parsed.name());
        toolSetService.putToolSet(descriptor, EtagHeader.ANY, null, toolSet, true);
        ToolSet decrypted = toolSetService.getToolSetWithDecryptedAuthSettings(descriptor).getValue();
        pending.add(new EntityChange(ResourceTypes.TOOL_SET, MergedConfigStore.canonicalId(descriptor), decrypted));
        return new EntityResult(id, AdminApplyStatus.APPLIED, null);
    }

    static void mutateScratch(Config scratch, AdminManifest entry) {
        try {
            switch (entry.kind()) {
                case "Settings" -> {
                    GlobalSettings settings = ConfigResourceController.treeToEntity(entry.spec(), GlobalSettings.class);
                    scratch.setGlobalInterceptors(settings.getGlobalInterceptors());
                    scratch.setRetriableErrorCodes(settings.getRetriableErrorCodes());
                }
                case "Interceptor" -> {
                    Interceptor interceptor = ConfigResourceController.treeToEntity(entry.spec(), Interceptor.class);
                    scratch.getInterceptors().put(entry.name(), interceptor);
                }
                case "Role" -> {
                    Role role = ConfigResourceController.treeToEntity(entry.spec(), Role.class);
                    scratch.getRoles().put(entry.name(), role);
                }
                case "Route" -> {
                    Route route = ConfigResourceController.treeToEntity(entry.spec(), Route.class);
                    scratch.getRoutes().put(entry.name(), route);
                }
                case "Key" -> {
                    Key key = ConfigResourceController.treeToEntity(entry.spec(), Key.class);
                    scratch.getKeys().put(entry.name(), key);
                }
                case "Model" -> {
                    Model model = ConfigResourceController.treeToEntity(entry.spec(), Model.class);
                    scratch.getModels().put(entry.name(), model);
                }
                case "Application" -> {
                    Application application = ConfigResourceController.treeToEntity(entry.spec(), Application.class);
                    scratch.getApplications().put(entry.name(), application);
                }
                case "ToolSet" -> {
                    ToolSet toolSet = ConfigResourceController.treeToEntity(entry.spec(), ToolSet.class);
                    scratch.getToolsets().put(entry.name(), toolSet);
                }
                case "Schema" -> {
                    String json;
                    try {
                        json = ProxyUtil.BLOB_MAPPER.writeValueAsString(entry.spec());
                    } catch (JsonProcessingException e) {
                        return;
                    }
                    scratch.getApplicationTypeSchemas().put(entry.name(), json);
                }
                default -> { /* unknown kinds never reach this code path */ }
            }
        } catch (HttpException ignored) {
            // Already accounted for in apply path; scratch update is best-effort.
        }
    }

    /**
     * {@code name} is the canonical resource id ({@code <kind-segment>/<bucket>/<name>}), e.g.
     * {@code models/platform/gpt-4} or {@code applications/public/my-app} — the client picks the
     * bucket explicitly rather than it being implied by {@code kind} alone.
     */
    private record ParsedName(String bucket, String location, String name) {}

    private static ParsedName parseName(AdminManifest entry) {
        String segment = KIND_URL_SEGMENT.get(entry.kind());
        if (segment == null) {
            throw new IllegalArgumentException("Unknown kind: " + entry.kind());
        }
        if (StringUtils.isBlank(entry.name())) {
            throw new IllegalArgumentException("Missing or empty 'name'");
        }
        if (entry.spec() == null) {
            throw new IllegalArgumentException("Missing 'spec'");
        }
        String raw = entry.name();
        String prefix = segment + "/";
        if (!raw.startsWith(prefix)) {
            throw new IllegalArgumentException("'name' must start with '" + prefix + "' for kind " + entry.kind());
        }
        String afterSegment = raw.substring(prefix.length());
        int slash = afterSegment.indexOf('/');
        if (slash < 0) {
            throw new IllegalArgumentException(
                    "'name' must include a bucket segment, e.g. '" + prefix + "platform/<name>'");
        }
        String bucket = afterSegment.substring(0, slash);
        String rest = afterSegment.substring(slash + 1);
        if (StringUtils.isBlank(rest)) {
            throw new IllegalArgumentException("'name' is missing the entity name after the bucket segment");
        }

        if (ResourceDescriptor.PLATFORM_BUCKET.equals(bucket)) {
            if (rest.contains("/")) {
                throw new IllegalArgumentException(
                        "'name' in the platform bucket must not contain nested path segments: " + raw);
            }
            return new ParsedName(ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION, rest);
        }

        boolean allowPublicBucket = "Application".equals(entry.kind()) || "ToolSet".equals(entry.kind());
        if (allowPublicBucket && ResourceDescriptor.PUBLIC_BUCKET.equals(bucket)) {
            return new ParsedName(ResourceDescriptor.PUBLIC_BUCKET, ResourceDescriptor.PUBLIC_LOCATION, rest);
        }

        throw new IllegalArgumentException(
                "'name' bucket segment must be 'platform'" + (allowPublicBucket ? " or 'public'" : "")
                        + ", got '" + bucket + "'");
    }

    private static String locationOf(JsonProcessingException e) {
        return e.getLocation() == null
                ? "unknown location"
                : "line " + e.getLocation().getLineNr() + ", column " + e.getLocation().getColumnNr();
    }

    private static String joinWarnings(List<ValidationWarning> warnings) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < warnings.size(); i++) {
            if (i > 0) {
                sb.append("; ");
            }
            ValidationWarning w = warnings.get(i);
            sb.append(w.getField()).append(": ").append(w.getMessage());
        }
        return sb.toString();
    }

    private ApplyResponse buildResponse(HttpStatus status, List<EntityResult> results) {
        int applied = 0;
        int failed = 0;

        List<AdminApplyResult> responseResults = new ArrayList<>();

        for (EntityResult r : results) {
            if (r.status() == AdminApplyStatus.APPLIED || r.status() == AdminApplyStatus.APPLIED_INVALID) {
                applied++;
            } else if (r.status() == AdminApplyStatus.FAILED) {
                failed++;
            }
            responseResults.add(
                    new AdminApplyResult(
                            r.entityId(),
                            r.status(),
                            r.error()
                    )
            );
        }
        return new ApplyResponse(status, new AdminApplyResponse(applied, failed, responseResults));
    }

    private record EntityResult(String entityId, AdminApplyStatus status, String error) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ApplyResponse(HttpStatus status, AdminApplyResponse body) {}
}