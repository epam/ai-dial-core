package com.epam.aidial.core.server.config;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.ExternalService;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Limit;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.Role;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsChangeMode;
import com.epam.aidial.core.credentials.validation.AuthSettingsValidator;
import com.epam.aidial.core.credentials.validation.AuthSettingsValidatorFactory;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * Post-processes a freshly-loaded {@link Config} in two passes (slice 2S.9):
 *
 * <ul>
 *   <li><b>Structural</b> — drops file-defined entries whose map key contains
 *       {@code /} (cross-entity reserved path separator). Always run; cannot
 *       fail per-entity. Only applied to file-sourced maps —
 *       {@link MergedConfigStore} skips this pass for the merged config because
 *       blob entries legitimately key by canonical ID.</li>
 *   <li><b>Semantic</b> — name back-fill, deployment-id uniqueness, ToolSet
 *       resource-key validation, route ordering, {@link ApiKeyStore} hookup.
 *       Each per-entity violation either throws (default {@code abort} mode,
 *       {@code onSkip == null}) or is routed via {@code onSkip} after removing
 *       the entry from the map ({@code skip} mode).</li>
 * </ul>
 *
 * <p>Entry point {@link #process(Config, ApiKeyStore)} is retained for
 * {@link FileConfigStore}'s today-behavior — whole-config-atomic with abort on
 * any violation.
 */
@Slf4j
public final class ConfigPostProcessor {

    private static final Pattern RESOURCE_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9-_]+$");
    private static final AuthSettingsValidatorFactory AUTH_SETTINGS_VALIDATOR_FACTORY = new AuthSettingsValidatorFactory();

    private ConfigPostProcessor() {
    }

    public static void process(Config config, @Nullable ApiKeyStore apiKeyStore) {
        processStructural(config);
        processSemantic(config, apiKeyStore, config.getKeys(), Map.of(), null);
    }

    /**
     * Drops file-defined entries with slash-keyed names across models, applications,
     * interceptors, roles, routes, and toolsets. Warn + drop, not warn + skip-record:
     * the entries never reach {@link Config} and are not surfaced through the
     * invalid-entity sibling store.
     */
    public static void processStructural(Config config) {
        rejectSlashKeyedNames(config.getModels(), "models");
        rejectSlashKeyedNames(config.getApplications(), "applications");
        rejectSlashKeyedNames(config.getInterceptors(), "interceptors");
        rejectSlashKeyedNames(config.getRoles(), "roles");
        rejectSlashKeyedNames(config.getRoutes(), "routes");
        rejectSlashKeyedNames(config.getToolsets(), "toolsets");
    }

    /**
     * Runs name back-fill, deployment-id uniqueness, toolset key validation,
     * route ordering, and {@link ApiKeyStore} hookup. Per-entity violations
     * route through {@code onSkip} when non-null; otherwise they throw.
     */
    public static void processSemantic(Config config, @Nullable ApiKeyStore apiKeyStore,
                                       Map<String, Key> fileKeysBySecret, Map<String, Key> apiKeysByCanonicalId,
                                       @Nullable BiConsumer<ResourceTypes, InvalidEntityException> onSkip) {
        Set<String> deploymentIds = new HashSet<>();
        sortRoutes(config);
        processModels(config, deploymentIds, onSkip);
        processApplications(config, deploymentIds, onSkip);
        processRoles(config);
        processInterceptors(config, deploymentIds, onSkip);
        processToolSets(config, deploymentIds, onSkip);

        if (apiKeyStore != null) {
            apiKeyStore.addProjectKeys(fileKeysBySecret, apiKeysByCanonicalId);
        }
    }

    private static <T> void rejectSlashKeyedNames(Map<String, T> map, String typeLabel) {
        Iterator<Map.Entry<String, T>> iter = map.entrySet().iterator();
        while (iter.hasNext()) {
            String key = iter.next().getKey();
            if (key.contains("/")) {
                log.warn("Dropping {} entry with slash-keyed name: {}", typeLabel, key);
                iter.remove();
            }
        }
    }

    /**
     * Targeted per-type helper for {@link MergedConfigStore} partial-update path (slice 4S.4).
     * Sets {@code model.name} from the map key, runs cross-reference check against the
     * supplied {@link Config}'s current interceptor map. On warning, removes the model from
     * {@code config.getModels()} and routes the violation through {@code onSkip}. Skip-mode
     * only — {@code onSkip == null} means cross-refs are not validated (matches file-loaded
     * abort path in {@link #processModels}).
     */
    static void validateSingleModel(Config config, String canonicalId,
                                    @Nullable BiConsumer<ResourceTypes, InvalidEntityException> onSkip) {
        Model model = config.getModels().get(canonicalId);
        if (model == null) {
            return;
        }
        model.setName(canonicalId);
        if (onSkip == null) {
            return;
        }
        List<ValidationWarning> warnings = new ArrayList<>();
        validateCrossReferences(model, config, warnings);
        if (!warnings.isEmpty()) {
            config.getModels().remove(canonicalId);
            onSkip.accept(ResourceTypes.MODEL, new InvalidEntityException(ResourceTypes.MODEL, canonicalId, warnings));
        }
    }

    /**
     * Targeted per-type helper. Sets {@code interceptor.name} from the map key. No cross-ref
     * validation — interceptors have no outbound refs.
     */
    static void validateSingleInterceptor(Config config, String canonicalId) {
        Interceptor interceptor = config.getInterceptors().get(canonicalId);
        if (interceptor != null) {
            interceptor.setName(canonicalId);
        }
    }

    /**
     * Targeted per-type helper. Sets {@code role.name} from the map key. {@code Role.limits}
     * keys are loose-refs (warning-only today) so no cross-ref validation runs.
     */
    static void validateSingleRole(Config config, String canonicalId) {
        Role role = config.getRoles().get(canonicalId);
        if (role != null) {
            role.setName(canonicalId);
        }
    }

    /**
     * Targeted per-type helper for {@link MergedConfigStore} partial-update path (slice 4S.4).
     * After an {@code INTERCEPTOR} delete that may have orphaned model chains, walks the
     * model map and routes any model with a now-missing interceptor reference through
     * {@code onSkip} after removing it from the map. Skip-mode only — {@code onSkip == null}
     * is a no-op (matches {@link #processModels}'s abort-mode behavior).
     */
    static void cascadeInterceptorDelete(Config config,
                                         @Nullable BiConsumer<ResourceTypes, InvalidEntityException> onSkip) {
        if (onSkip == null) {
            return;
        }
        Iterator<Map.Entry<String, Model>> iterator = config.getModels().entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Model> entry = iterator.next();
            Model model = entry.getValue();
            List<ValidationWarning> warnings = new ArrayList<>();
            validateCrossReferences(model, config, warnings);
            if (!warnings.isEmpty()) {
                String canonicalId = entry.getKey();
                iterator.remove();
                onSkip.accept(ResourceTypes.MODEL,
                        new InvalidEntityException(ResourceTypes.MODEL, canonicalId, warnings));
            }
        }
    }

    /**
     * Package-visible wrapper around route sort for {@link MergedConfigStore} partial-update path.
     */
    static void sortRoutesInPlace(Config config) {
        sortRoutes(config);
    }

    private static void sortRoutes(Config config) {
        List<Route> sortedRoutes = new ArrayList<>();
        for (Map.Entry<String, Route> entry : config.getRoutes().entrySet()) {
            String name = entry.getKey();
            Route route = entry.getValue();
            route.setName(name);
            log.debug("Loading {}", route);
            sortedRoutes.add(route);
        }
        sortedRoutes.sort(Comparator.comparingInt(Route::getOrder));
        LinkedHashMap<String, Route> routes = config.getRoutes();
        routes.clear();
        for (Route route : sortedRoutes) {
            routes.put(route.getName(), route);
        }
    }

    private static void processModels(Config config, Set<String> deploymentIds,
                                      @Nullable BiConsumer<ResourceTypes, InvalidEntityException> onSkip) {
        Iterator<Map.Entry<String, Model>> iterator = config.getModels().entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Model> entry = iterator.next();
            String name = entry.getKey();
            if (skipOnDuplicate(name, ResourceTypes.MODEL, deploymentIds, onSkip, iterator)) {
                continue;
            }
            Model model = entry.getValue();
            model.setName(name);
            log.debug("Loading {}", model);
            // Cross-ref check is skip-mode-only — file-loaded abort-mode path (onSkip == null)
            // preserves design 02 §4.2's allowance for pre-existing file-side inconsistency.
            // Strict-mode 422 is enforced at the write controller, not here.
            if (onSkip != null) {
                List<ValidationWarning> crossRefWarnings = new ArrayList<>();
                validateCrossReferences(model, config, crossRefWarnings);
                if (!crossRefWarnings.isEmpty()) {
                    iterator.remove();
                    onSkip.accept(ResourceTypes.MODEL, new InvalidEntityException(ResourceTypes.MODEL, name, crossRefWarnings));
                }
            }
        }
    }

    /**
     * Validates that every interceptor reference on the supplied model resolves
     * within the merged {@code config.interceptors} map. {@link MergedConfigStore}
     * keys file entries by simple name and API entries by canonical ID; either
     * shape is accepted via {@code containsKey}. Returns {@code true} when every
     * reference resolves (no warnings appended).
     */
    public static boolean validateCrossReferences(Model model, Config config, List<ValidationWarning> warnings) {
        List<String> refs = model.getInterceptors();
        if (refs == null || refs.isEmpty()) {
            return true;
        }
        Map<String, Interceptor> interceptors = config.getInterceptors();
        for (int i = 0; i < refs.size(); i++) {
            String ref = refs.get(i);
            if (ref == null || !interceptors.containsKey(ref)) {
                warnings.add(new ValidationWarning("interceptors[" + i + "]",
                        "Interceptor '" + ref + "' not found in config"));
            }
        }
        return warnings.isEmpty();
    }

    private static void processApplications(Config config, Set<String> deploymentIds,
                                            @Nullable BiConsumer<ResourceTypes, InvalidEntityException> onSkip) {
        Iterator<Map.Entry<String, Application>> iterator = config.getApplications().entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Application> entry = iterator.next();
            String name = entry.getKey();
            if (skipOnDuplicate(name, ResourceTypes.APPLICATION, deploymentIds, onSkip, iterator)) {
                continue;
            }
            Application application = entry.getValue();
            application.setName(name);
            validateExternalServices(application);
            log.debug("Loading {}", application);
        }
    }

    /**
     * Drops external-service definitions with missing/invalid {@code auth_settings} from a config-loaded
     * application (config is admin-edited, so we drop-and-log rather than fail the whole load).
     */
    private static void validateExternalServices(Application application) {
        Map<String, ExternalService> services = application.getExternalServices();
        if (services == null || services.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<String, ExternalService>> iterator = services.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, ExternalService> entry = iterator.next();
            String serviceId = entry.getKey();
            ExternalService service = entry.getValue();
            if (!isValidResourceKey(serviceId)) {
                log.error("Dropping external service '{}' on application '{}': id must contain only letters, digits, '-' or '_'",
                        serviceId, application.getName());
                iterator.remove();
                continue;
            }
            ResourceAuthSettings authSettings = service == null ? null : service.getAuthSettings();
            if (authSettings == null || authSettings.getAuthenticationType() == null) {
                log.error("Dropping external service '{}' on application '{}': auth_settings or authentication_type missing",
                        serviceId, application.getName());
                iterator.remove();
                continue;
            }
            AuthSettingsValidator validator = AUTH_SETTINGS_VALIDATOR_FACTORY.getValidator(authSettings.getAuthenticationType());
            if (validator == null) {
                log.error("Dropping external service '{}' on application '{}': unknown authentication_type {}",
                        serviceId, application.getName(), authSettings.getAuthenticationType());
                iterator.remove();
                continue;
            }
            try {
                validator.validate(authSettings, ResourceAuthSettingsChangeMode.NO_CLIENT_CHANGES);
            } catch (RuntimeException e) {
                log.error("Dropping external service '{}' on application '{}': invalid auth_settings: {}",
                        serviceId, application.getName(), e.getMessage());
                iterator.remove();
            }
        }
    }

    private static void processRoles(Config config) {
        for (Map.Entry<String, Role> entry : config.getRoles().entrySet()) {
            String name = entry.getKey();
            Role role = entry.getValue();
            role.setName(name);
            log.debug("Start loading role `{}`", role.getName());
            for (Map.Entry<String, Limit> limitEntry : role.getLimits().entrySet()) {
                log.debug("Loading {} for deployment `{}`", limitEntry.getValue(), limitEntry.getKey());
            }
            log.debug("End loading role `{}`", role.getName());
        }
    }

    private static void processInterceptors(Config config, Set<String> deploymentIds,
                                            @Nullable BiConsumer<ResourceTypes, InvalidEntityException> onSkip) {
        Iterator<Map.Entry<String, Interceptor>> iterator = config.getInterceptors().entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Interceptor> entry = iterator.next();
            String name = entry.getKey();
            if (skipOnDuplicate(name, ResourceTypes.INTERCEPTOR, deploymentIds, onSkip, iterator)) {
                continue;
            }
            Interceptor interceptor = entry.getValue();
            interceptor.setName(name);
            log.debug("Loading {}", interceptor);
        }
    }

    private static void processToolSets(Config config, Set<String> deploymentIds,
                                        @Nullable BiConsumer<ResourceTypes, InvalidEntityException> onSkip) {
        Iterator<Map.Entry<String, ToolSet>> iterator = config.getToolsets().entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, ToolSet> entry = iterator.next();
            String name = entry.getKey();
            if (skipOnDuplicate(name, ResourceTypes.TOOL_SET, deploymentIds, onSkip, iterator)) {
                continue;
            }
            if (isValidToolSetKey(name)) {
                ToolSet toolSet = entry.getValue();
                toolSet.setName(name);
                log.debug("Loading {}", entry.getValue());
            } else {
                log.warn("Invalid ToolSet name: {}", name);
                iterator.remove();
            }
        }
    }

    /**
     * Returns true and removes the offending entry when the name was already seen.
     * Abort mode ({@code onSkip == null}) preserves {@link FileConfigStore}'s today-behavior:
     * throw {@link IllegalStateException} and roll back the load.
     */
    private static boolean skipOnDuplicate(String name, ResourceTypes type, Set<String> deploymentIds,
                                           @Nullable BiConsumer<ResourceTypes, InvalidEntityException> onSkip,
                                           Iterator<?> iterator) {
        if (deploymentIds.add(name)) {
            return false;
        }
        if (onSkip == null) {
            throw new IllegalStateException("Deployment uniqueness is violated: duplicate is found " + name);
        }
        log.warn("Skipping {} '{}' due to duplicate deployment ID", type, name);
        onSkip.accept(type, new InvalidEntityException(type, name,
                List.of(new ValidationWarning("name", "Duplicate deployment ID: " + name))));
        iterator.remove();
        return true;
    }

    private static boolean isValidResourceKey(String resourceKey) {
        return RESOURCE_KEY_PATTERN.matcher(resourceKey).matches();
    }

    // Bare file-sourced toolset names have no '/'; API-managed toolsets are keyed by their canonical
    // id ("toolsets/platform/name") — validate only the trailing short-name segment in that case, same
    // as the plain-name case would validate the whole (slash-free) string. Only ToolSet map keys are
    // ever canonical-id-shaped this way — other RESOURCE_KEY_PATTERN callers (e.g. external-service
    // ids) must keep going through the strict isValidResourceKey above.
    private static boolean isValidToolSetKey(String resourceKey) {
        int slash = resourceKey.lastIndexOf('/');
        String candidate = slash < 0 ? resourceKey : resourceKey.substring(slash + 1);
        return RESOURCE_KEY_PATTERN.matcher(candidate).matches();
    }
}
