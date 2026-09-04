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
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.config.ValidationWarning;
import com.epam.aidial.core.server.data.AdminManifest;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.epam.aidial.core.server.service.config.ConfigEntityCodec.BLOB_MAPPER;

/**
 * Manifest-shape helpers shared by {@link ConfigApplyService} (real-apply) and
 * {@link ConfigValidationService} (precheck) — parsing/naming rules, dependency ordering, scratch
 * construction/mutation, and cross-entity validation that neither side owns exclusively.
 */
@UtilityClass
public class ConfigManifestSupport {

    static final String SETTINGS_SINGLETON_NAME = "global";

    public static final Map<String, Integer> DEPENDENCY_ORDER = Map.of(
            "Settings", 0,
            "Schema", 1,
            "CatalogSchema", 1,
            "Interceptor", 2,
            "Role", 3,
            "Key", 4,
            "Route", 5,
            "Model", 6,
            "ToolSet", 7,
            "Application", 8);

    /**
     * Sorts a manifest list so a kind that other kinds can reference (e.g. {@code Interceptor})
     * is always applied before the kind referencing it (e.g. {@code Model}) — required whenever
     * more than one kind in the same batch/call can cross-reference another by name. Shared by
     * all three admin write/validate endpoints, so the one ordering definition governs each.
     */
    public static final Comparator<AdminManifest> DEPENDENCY_ORDER_COMPARATOR =
            Comparator.comparingInt(entry -> DEPENDENCY_ORDER.getOrDefault(entry.kind(), 99));

    public static final Map<String, String> KIND_URL_SEGMENT = Map.of(
            "Settings", "settings",
            "Schema", "schemas",
            "CatalogSchema", "catalog_schemas",
            "Interceptor", "interceptors",
            "Role", "roles",
            "Key", "keys",
            "Route", "routes",
            "Model", "models",
            "ToolSet", "toolsets",
            "Application", "applications");

    public static Config newScratch(MergedConfigStore mergedConfigStore) {
        Config live = mergedConfigStore.get();
        Config scratch = new Config();
        if (live != null) {
            scratch.setModels(new HashMap<>(live.getModels()));
            scratch.setInterceptors(new HashMap<>(live.getInterceptors()));
            scratch.setApplicationTypeSchemas(new HashMap<>(live.getApplicationTypeSchemas()));
            scratch.setCatalogSchemas(new HashMap<>(live.getCatalogSchemas()));
            scratch.setApplications(new HashMap<>(live.getApplications()));
            scratch.setToolsets(new HashMap<>(live.getToolsets()));
            scratch.setRoles(new HashMap<>(live.getRoles()));
            scratch.setKeys(new HashMap<>(live.getKeys()));
            scratch.getRoutes().putAll(live.getRoutes());
            scratch.setGlobalInterceptors(live.getGlobalInterceptors());
            scratch.setRetriableErrorCodes(live.getRetriableErrorCodes());
            scratch.setTranslators(live.getTranslators());
        }
        return scratch;
    }

    public static void mutateScratch(Config scratch, AdminManifest entry) {
        try {
            switch (entry.kind()) {
                case "Settings" -> {
                    GlobalSettings settings = ConfigEntityCodec.treeToEntity(entry.spec(), GlobalSettings.class);
                    scratch.setGlobalInterceptors(settings.getGlobalInterceptors());
                    scratch.setRetriableErrorCodes(settings.getRetriableErrorCodes());
                }
                case "Interceptor" -> {
                    Interceptor interceptor = ConfigEntityCodec.treeToEntity(entry.spec(), Interceptor.class);
                    scratch.getInterceptors().put(parseName(entry).name(), interceptor);
                }
                case "Role" -> {
                    Role role = ConfigEntityCodec.treeToEntity(entry.spec(), Role.class);
                    scratch.getRoles().put(parseName(entry).name(), role);
                }
                case "Route" -> {
                    Route route = ConfigEntityCodec.treeToEntity(entry.spec(), Route.class);
                    scratch.getRoutes().put(entry.name(), route);
                }
                case "Key" -> {
                    Key key = ConfigEntityCodec.treeToEntity(entry.spec(), Key.class);
                    scratch.getKeys().put(entry.name(), key);
                }
                case "Model" -> {
                    Model model = ConfigEntityCodec.treeToEntity(entry.spec(), Model.class);
                    scratch.getModels().put(parseName(entry).name(), model);
                }
                case "Application" -> {
                    ParsedName parsed = parseName(entry);
                    if (ResourceDescriptor.PLATFORM_BUCKET.equals(parsed.bucket())) {
                        Application application = ConfigEntityCodec.treeToEntity(entry.spec(), Application.class);
                        scratch.getApplications().put(parsed.name(), application);
                    }
                }
                case "ToolSet" -> {
                    ParsedName parsed = parseName(entry);
                    if (ResourceDescriptor.PLATFORM_BUCKET.equals(parsed.bucket())) {
                        ToolSet toolSet = ConfigEntityCodec.treeToEntity(entry.spec(), ToolSet.class);
                        scratch.getToolsets().put(parsed.name(), toolSet);
                    }
                }
                case "Schema" -> {
                    String schemaId = MergedConfigStore.extractSchemaId(entry.spec());
                    if (schemaId != null && !schemaId.isBlank()) {
                        try {
                            scratch.getApplicationTypeSchemas().put(schemaId, BLOB_MAPPER.writeValueAsString(entry.spec()));
                        } catch (JsonProcessingException e) {
                            return;
                        }
                    }
                }
                case "CatalogSchema" -> {
                    String schemaId = MergedConfigStore.extractSchemaId(entry.spec());
                    if (schemaId != null && !schemaId.isBlank()) {
                        try {
                            scratch.getCatalogSchemas().put(schemaId, BLOB_MAPPER.writeValueAsString(entry.spec()));
                        } catch (JsonProcessingException e) {
                            return;
                        }
                    }
                }
                default -> { /* unknown kinds never reach this code path */ }
            }
        } catch (IllegalArgumentException ignored) {
            // Already accounted for in apply path; scratch update is best-effort.
        }
    }

    /**
     * Shared by {@link ConfigValidationService#validateOnly} (precheck) and the real-apply
     * {@code applyX} methods on {@link ConfigApplyService}: non-null iff {@code parsed}'s short
     * name is already claimed by a different model/application/interceptor/toolset in
     * {@code scratch}. See {@link ConfigPostProcessor#isDeploymentIdTakenByAnotherDeploymentType}.
     */
    static String validateDeploymentIdUniqueness(Config scratch, ResourceTypes type, ParsedName parsed) {
        if (ConfigPostProcessor.isDeploymentIdTakenByAnotherDeploymentType(scratch, type, parsed.name())) {
            return "Deployment ID '" + parsed.name() + "' is already used by a different entity";
        }
        return null;
    }

    /**
     * Shared by {@link ConfigValidationService#validateOnly} (precheck) and
     * {@link ConfigApplyService#applySchema} (real-apply): validates a Schema/CatalogSchema spec
     * and, if it's well-formed, checks its {@code $id} for an in-place change or a collision
     * against a different blob via {@link MergedConfigStore#validateSchemaId}.
     */
    static String validateSchema(AdminManifest entry, ParsedName parsed, Config scratch,
                                 ResourceTypes type, ResourceService resourceService) {
        String kindLabel = switch (type) {
            case APP_TYPE_SCHEMA -> "Schema";
            case CATALOG_SCHEMA -> "CatalogSchema";
            default -> throw new IllegalArgumentException("Unexpected schema type: " + type);
        };
        if (!entry.spec().isObject()) {
            return kindLabel + " spec must be a JSON object";
        }
        String schemaId = MergedConfigStore.extractSchemaId(entry.spec());
        if (schemaId == null || schemaId.isBlank()) {
            return kindLabel + " spec must contain a non-blank $id field";
        }
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromDecoded(
                type, parsed.bucket(), parsed.location(), parsed.name());
        String oldSchemaId = readOldSchemaId(resourceService, descriptor);
        Map<String, String> schemaMap = MergedConfigStore.getSchemaMapOf(scratch, type);
        return MergedConfigStore.validateSchemaId(schemaMap, oldSchemaId != null, schemaId, oldSchemaId);
    }

    private static String readOldSchemaId(ResourceService resourceService, ResourceDescriptor descriptor) {
        String existingBody;
        try {
            existingBody = resourceService.getResource(descriptor);
        } catch (Exception e) {
            // A genuinely missing resource surfaces as null, not an exception — any exception here
            // is a real read failure, so fail closed rather than silently treating it as "no prior $id".
            throw new RuntimeException("Failed to read existing resource: " + descriptor.getUrl());
        }
        if (existingBody == null) {
            return null;
        }
        try {
            return MergedConfigStore.extractSchemaId(BLOB_MAPPER.readTree(existingBody));
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse existing resource: " + descriptor.getUrl());
        }
    }

    /**
     * {@code name} is the canonical resource id ({@code <kind-segment>/<bucket>/<name>}), e.g.
     * {@code models/platform/gpt-4} or {@code applications/public/my-app} — the client picks the
     * bucket explicitly rather than it being implied by {@code kind} alone.
     */
    record ParsedName(String bucket, String location, String name) {}

    static ParsedName parseName(AdminManifest entry) {
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

    static String joinWarnings(List<ValidationWarning> warnings) {
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
}
