package com.epam.aidial.core.server.config;

import com.epam.aidial.core.config.Alias;
import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.DeploymentInterface;
import com.epam.aidial.core.config.ExternalService;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.InterfaceMode;
import com.epam.aidial.core.config.InterfacePathMapping;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Limit;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Plugin;
import com.epam.aidial.core.config.Pricing;
import com.epam.aidial.core.config.PricingRate;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.Role;
import com.epam.aidial.core.config.RoleBasedEntity;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.config.Router;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.config.Translator;
import com.epam.aidial.core.config.TranslatorRef;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.config.UpstreamAuthType;
import com.epam.aidial.core.config.UpstreamInterface;
import com.epam.aidial.core.config.policy.Policy;
import com.epam.aidial.core.config.policy.PolicyAssignment;
import com.epam.aidial.core.config.policy.PolicyAssignmentValidator;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsChangeMode;
import com.epam.aidial.core.credentials.validation.AuthSettingsValidator;
import com.epam.aidial.core.credentials.validation.AuthSettingsValidatorFactory;
import com.epam.aidial.core.plugin.PluginProvider;
import com.epam.aidial.core.server.alias.AliasTargets;
import com.epam.aidial.core.server.plugin.PluginRegistry;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.service.OrgUnitRoles;
import com.epam.aidial.core.server.util.DeploymentEndpointUtil;
import com.epam.aidial.core.server.util.PathTemplateUtil;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 *       fail per-entity. Only applied to file-sourced maps — {@link MergedConfigStore}
 *       skips this pass for the merged config: blob-sourced models/applications/
 *       interceptors/roles/toolsets key by short name (never contains {@code /}), schemas
 *       key by their {@code $id} field value (which contains {@code /} legitimately), and keys/routes
 *       key by canonical id legitimately.</li>
 *   <li><b>Semantic</b> — name back-fill, deployment-id uniqueness, ToolSet
 *       resource-key validation, route ordering, {@link ApiKeyStore} hookup.
 *       Each per-entity violation either throws (default {@code abort} mode,
 *       {@code onSkip == null}) or is routed via {@code onSkip} after removing
 *       the entry from the map ({@code skip} mode).</li>
 * </ul>
 *
 * <p>Entry point {@link #process(Config, ApiKeyStore, PluginRegistry)} is retained for
 * {@link FileConfigStore}'s today-behavior — whole-config-atomic with abort on
 * any violation.
 */
@Slf4j
public final class ConfigPostProcessor {

    private static final String NO_PLUGIN_FOR_INTERCEPTOR = "A plugin is not supported for an interceptor";
    private static final String NAMELESS_PLUGIN_URL = "A plugin name cannot be blank";
    private static final String UNSERVED_PLUGIN_URL = "A plugin url is only accepted in a base url";

    // Allowed characters for a client-chosen entity short name, applied to the URL-decoded segment.
    // Shared by every write surface that accepts one — the single-entity PUT/DELETE endpoints
    // (ConfigResourceController), the batch apply/validate endpoints (ConfigManifestSupport), and
    // the file-config migration tool (ConfigFileMigrateController, SchemaMigrationNameResolver) —
    // so none of them can be used to bypass what the others enforce.
    public static final Pattern ENTITY_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9._%:@\\[\\]()-]+$");
    private static final Pattern RESOURCE_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9-_]+$");
    private static final AuthSettingsValidatorFactory AUTH_SETTINGS_VALIDATOR_FACTORY = new AuthSettingsValidatorFactory();

    private ConfigPostProcessor() {
    }

    public static void process(Config config, @Nullable ApiKeyStore apiKeyStore, PluginRegistry pluginRegistry) {
        processStructural(config);
        processPlugins(config, pluginRegistry, null);
        processSemantic(config, apiKeyStore, config.getKeys(), Map.of(), null);
    }

    /**
     * Drops file-defined entries with slash-keyed names across models, applications,
     * interceptors, translators, plugins, roles, routes, and toolsets. Warn + drop, not warn + skip-record:
     * the entries never reach {@link Config} and are not surfaced through the
     * invalid-entity sibling store.
     */
    public static void processStructural(Config config) {
        rejectSlashKeyedNames(config.getModels(), "models");
        rejectSlashKeyedNames(config.getApplications(), "applications");
        rejectSlashKeyedNames(config.getInterceptors(), "interceptors");
        rejectSlashKeyedNames(config.getTranslators(), "translators");
        rejectSlashKeyedNames(config.getPlugins(), "plugins");
        rejectSlashKeyedNames(config.getRoles(), "roles");
        rejectSlashKeyedNames(config.getRoutes(), "routes");
        rejectSlashKeyedNames(config.getToolsets(), "toolsets");
        rejectSlashKeyedNames(config.getRouters(), "routers");
        rejectSlashKeyedNames(config.getAliases(), "aliases");
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
        processRoutes(config, onSkip);
        processTranslators(config, onSkip);
        processModels(config, deploymentIds, onSkip);
        processApplications(config, deploymentIds, onSkip);
        processRoles(config);
        processInterceptors(config, deploymentIds, onSkip);
        processToolSets(config, deploymentIds, onSkip);
        processRouters(config, deploymentIds, onSkip);
        processAliases(config, deploymentIds, onSkip);
        OrgUnitRoles.validate(config.getOrgUnitRoles());

        if (apiKeyStore != null) {
            apiKeyStore.addProjectKeys(fileKeysBySecret, apiKeysByCanonicalId);
        }
    }

    /**
     * Checks every {@code plugins} entry with {@link #validatePlugin}. Runs before {@link #processSemantic}, so the
     * entities that reference a plugin see only the valid ones. An unknown type is checked only when {@code onSkip} is
     * set, as a model's interceptors are in {@link #processModels}: a jar not yet on a pod during a rollout must not
     * fail that pod's rebuild.
     */
    public static void processPlugins(Config config, PluginRegistry pluginRegistry,
                                      @Nullable BiConsumer<ResourceTypes, InvalidEntityException> onSkip) {
        for (String name : List.copyOf(config.getPlugins().keySet())) {
            validateSinglePlugin(config, pluginRegistry, name, onSkip);
        }
    }

    /**
     * {@link #processPlugins} for one entry; a {@link MergedConfigStore} partial update calls it for the entry it wrote.
     */
    static void validateSinglePlugin(Config config, PluginRegistry pluginRegistry, String mapKey,
                                     @Nullable BiConsumer<ResourceTypes, InvalidEntityException> onSkip) {
        Plugin plugin = config.getPlugins().get(mapKey);
        if (plugin == null) {
            return;
        }
        List<ValidationWarning> warnings = new ArrayList<>();
        validatePlugin(mapKey, plugin, pluginRegistry, onSkip != null, warnings);
        if (warnings.isEmpty()) {
            return;
        }
        if (onSkip == null) {
            throw new InvalidEntityException(ResourceTypes.PLUGIN, mapKey, warnings);
        }
        config.getPlugins().remove(mapKey);
        onSkip.accept(ResourceTypes.PLUGIN, new InvalidEntityException(ResourceTypes.PLUGIN, mapKey, warnings));
    }

    /**
     * Checks a {@code plugins} entry against the provider of its type. Settings the provider rejects are a defect of
     * the entry itself, reported under {@code settings.<path>} whatever {@code checkType}. A type no loaded provider
     * declares is a dangling reference, reported under {@code type} only when {@code checkType}; without a provider the
     * settings cannot be checked. A provider that fails while checking rejects the settings, so one provider's defect
     * drops or fails only its own entries. Shared by every write surface (full load, partial update, Configuration API,
     * admin apply and validate).
     */
    public static void validatePlugin(@Nullable String name, Plugin plugin, PluginRegistry pluginRegistry,
                                      boolean checkType, List<ValidationWarning> warnings) {
        PluginProvider provider = pluginRegistry.get(plugin.getType());
        if (provider == null) {
            if (checkType) {
                warnings.add(new ValidationWarning("type", messageWithEntityPrefix("Plugin", name,
                        "No plugin provider of type '" + plugin.getType() + "' is loaded")));
            }
            return;
        }
        List<PluginProvider.Problem> problems;
        try {
            // a copy: a provider that changed the settings would change the config being validated
            problems = List.copyOf(provider.validate(plugin.getSettings() == null ? null : plugin.getSettings().deepCopy()));
        } catch (RuntimeException | LinkageError e) {
            // plugin code: a jar missing a class throws NoClassDefFoundError on first use
            log.warn("Plugin provider of type '{}' failed to check plugin '{}'", plugin.getType(), name, e);
            warnings.add(new ValidationWarning("settings", messageWithEntityPrefix("Plugin", name,
                    "The plugin provider of type '" + plugin.getType() + "' failed to check the settings")));
            return;
        }
        for (PluginProvider.Problem problem : problems) {
            String path = problem.path();
            String field = path == null || path.isEmpty() ? "settings" : "settings." + path;
            warnings.add(new ValidationWarning(field, messageWithEntityPrefix("Plugin", name, problem.message())));
        }
    }

    /**
     * Prefixes a validation message with the entity it's about, so a message read out of context
     * (a log line, an aggregated skip reason) still says which model/deployment/translator it's
     * about. Null-safe: {@code name} is unset at a few call sites whose warnings are already tagged
     * with the entity's id one level up, so those get the message unprefixed, exactly as before.
     */
    private static String messageWithEntityPrefix(String typeLabel, @Nullable String name, String message) {
        return (name == null || name.isBlank()) ? message : typeLabel + " '" + name + "': " + message;
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
    static void validateSingleModel(Config config, String mapKey,
                                    @Nullable BiConsumer<ResourceTypes, InvalidEntityException> onSkip) {
        Model model = config.getModels().get(mapKey);
        if (model == null) {
            return;
        }
        model.setName(mapKey);
        List<ValidationWarning> warnings = new ArrayList<>();
        validateModelInvariants(model, config.getTranslators(), warnings);
        if (onSkip != null) {
            validateCrossReferences(model, config, warnings);
        }
        if (warnings.isEmpty()) {
            return;
        }
        if (onSkip == null) {
            throw new InvalidEntityException(ResourceTypes.MODEL, mapKey, warnings);
        }
        config.getModels().remove(mapKey);
        onSkip.accept(ResourceTypes.MODEL, new InvalidEntityException(ResourceTypes.MODEL, mapKey, warnings));
    }

    /**
     * Targeted per-type helper for {@link MergedConfigStore}'s partial-update path — validates the
     * single written entry via {@link #validateTranslator}. {@link Translator} has no name field to
     * back-fill, unlike Interceptor/Role/Application.
     */
    static void validateSingleTranslator(Config config, String mapKey,
                                         @Nullable BiConsumer<ResourceTypes, InvalidEntityException> onSkip) {
        Translator translator = config.getTranslators().get(mapKey);
        if (translator == null) {
            return;
        }
        List<ValidationWarning> warnings = new ArrayList<>();
        validateTranslator(mapKey, translator, warnings);
        if (warnings.isEmpty()) {
            return;
        }
        if (onSkip == null) {
            throw new InvalidEntityException(ResourceTypes.TRANSLATOR, mapKey, warnings);
        }
        config.getTranslators().remove(mapKey);
        onSkip.accept(ResourceTypes.TRANSLATOR, new InvalidEntityException(ResourceTypes.TRANSLATOR, mapKey, warnings));
    }

    /**
     * Validates every policy and assignment of the rebuilt {@link Config}: policies first, so that an
     * assignment of a policy dropped here is dropped as dangling.
     */
    static void processPolicies(Config config, @Nullable BiConsumer<ResourceTypes, InvalidEntityException> onSkip) {
        for (String path : List.copyOf(config.getPolicies().keySet())) {
            validateSinglePolicy(config, path, onSkip);
        }
        for (String path : List.copyOf(config.getPolicyAssignments().keySet())) {
            validateSingleAssignment(config, path, onSkip);
        }
    }

    static void validateSinglePolicy(Config config, String mapKey,
                                     @Nullable BiConsumer<ResourceTypes, InvalidEntityException> onSkip) {
        Policy policy = config.getPolicies().get(mapKey);
        if (policy == null) {
            return;
        }
        String error = PolicyAssignmentValidator.validatePolicy(policy);
        if (error == null && policy.isSelfApplying() && mapKey.indexOf('/') < 0) {
            error = "A policy that pins both subject and costObject applies by itself and must live in the folder of its cost object";
        }
        if (error != null) {
            rejectPolicyRecord(ResourceTypes.POLICY, config.getPolicies(), mapKey, error, onSkip);
        }
    }

    /**
     * Besides the record's own checks, an assignment whose policy is missing is invalid.
     */
    static void validateSingleAssignment(Config config, String mapKey,
                                         @Nullable BiConsumer<ResourceTypes, InvalidEntityException> onSkip) {
        PolicyAssignment assignment = config.getPolicyAssignments().get(mapKey);
        if (assignment == null) {
            return;
        }
        String error = PolicyAssignmentValidator.validateAssignment(assignment);
        if (error == null && !config.getPolicies().containsKey(assignment.getPolicy())) {
            error = "Policy '" + assignment.getPolicy() + "' does not exist";
        }
        if (error != null) {
            rejectPolicyRecord(ResourceTypes.POLICY_ASSIGNMENT, config.getPolicyAssignments(), mapKey, error, onSkip);
        }
    }

    private static void rejectPolicyRecord(ResourceTypes type, Map<String, ?> records, String mapKey, String error,
                                           @Nullable BiConsumer<ResourceTypes, InvalidEntityException> onSkip) {
        InvalidEntityException exception = new InvalidEntityException(type, mapKey, List.of(new ValidationWarning("body", error)));
        if (onSkip == null) {
            throw exception;
        }
        records.remove(mapKey);
        onSkip.accept(type, exception);
    }

    /**
     * Targeted per-type helpers for {@link MergedConfigStore}'s partial-update path. Set the name from
     * the map key and validate {@code overridePaths} — the one semantic check these types carry — so a
     * write cannot accept an entity the next full rebuild would reject.
     */
    static void validateSingleApplication(Config config, String mapKey,
                                          @Nullable BiConsumer<ResourceTypes, InvalidEntityException> onSkip) {
        Application application = config.getApplications().get(mapKey);
        if (application == null) {
            return;
        }
        application.setName(mapKey);
        List<ValidationWarning> warnings = new ArrayList<>();
        validateInterfaces(application, warnings);
        if (warnings.isEmpty()) {
            return;
        }
        if (onSkip == null) {
            throw new InvalidEntityException(ResourceTypes.APPLICATION, mapKey, warnings);
        }
        config.getApplications().remove(mapKey);
        onSkip.accept(ResourceTypes.APPLICATION, new InvalidEntityException(ResourceTypes.APPLICATION, mapKey, warnings));
    }

    static void validateSingleInterceptor(Config config, String mapKey,
                                          @Nullable BiConsumer<ResourceTypes, InvalidEntityException> onSkip) {
        Interceptor interceptor = config.getInterceptors().get(mapKey);
        if (interceptor == null) {
            return;
        }
        interceptor.setName(mapKey);
        List<ValidationWarning> warnings = new ArrayList<>();
        validateInterfaces(interceptor, warnings);
        if (warnings.isEmpty()) {
            return;
        }
        if (onSkip == null) {
            throw new InvalidEntityException(ResourceTypes.INTERCEPTOR, mapKey, warnings);
        }
        config.getInterceptors().remove(mapKey);
        onSkip.accept(ResourceTypes.INTERCEPTOR, new InvalidEntityException(ResourceTypes.INTERCEPTOR, mapKey, warnings));
    }

    /**
     * Targeted per-type helper for {@link MergedConfigStore}'s partial-update path — validates the
     * single written route via {@link #validateRouteInvariants}, after the route sort that
     * already runs for every ROUTE write ({@link #sortRoutesInPlace}).
     */
    static void validateSingleRoute(Config config, String mapKey,
                                    @Nullable BiConsumer<ResourceTypes, InvalidEntityException> onSkip) {
        Route route = config.getRoutes().get(mapKey);
        if (route == null) {
            return;
        }
        List<ValidationWarning> warnings = new ArrayList<>();
        validateRouteInvariants(route, warnings);
        if (warnings.isEmpty()) {
            return;
        }
        if (onSkip == null) {
            throw new InvalidEntityException(ResourceTypes.ROUTE, mapKey, warnings);
        }
        config.getRoutes().remove(mapKey);
        onSkip.accept(ResourceTypes.ROUTE, new InvalidEntityException(ResourceTypes.ROUTE, mapKey, warnings));
    }

    static <T extends RoleBasedEntity> void setNameAsMapKey(Map<String, T> entities, String mapKey) {
        T entity = entities.get(mapKey);
        if (entity != null) {
            entity.setName(mapKey);
        }
    }

    /**
     * Targeted per-type helper. Sets {@code role.name} from the map key. {@code Role.limits}
     * keys are loose-refs (warning-only today) so no cross-ref validation runs.
     */
    static void setRoleNameAsMapKey(Map<String, Role> roles, String mapKey) {
        Role role = roles.get(mapKey);
        if (role != null) {
            role.setName(mapKey);
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
                String mapKey = entry.getKey();
                iterator.remove();
                onSkip.accept(ResourceTypes.MODEL,
                        new InvalidEntityException(ResourceTypes.MODEL, mapKey, warnings));
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

    /**
     * Validates every top-level route's upstreams. Routes have no deployment-id namespace to
     * dedupe (the config map key is already unique), so unlike {@link #processModels} this only
     * runs {@link #validateRouteInvariants}.
     */
    private static void processRoutes(Config config, @Nullable BiConsumer<ResourceTypes, InvalidEntityException> onSkip) {
        Iterator<Map.Entry<String, Route>> iterator = config.getRoutes().entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Route> entry = iterator.next();
            String name = entry.getKey();
            Route route = entry.getValue();
            List<ValidationWarning> warnings = new ArrayList<>();
            validateRouteInvariants(route, warnings);
            if (warnings.isEmpty()) {
                continue;
            }
            if (onSkip == null) {
                throw new InvalidEntityException(ResourceTypes.ROUTE, name, warnings);
            }
            iterator.remove();
            onSkip.accept(ResourceTypes.ROUTE, new InvalidEntityException(ResourceTypes.ROUTE, name, warnings));
        }
    }

    /**
     * Route checks the rebuild enforces unconditionally; pre-write paths call this so nothing is stored that
     * the next rebuild would reject.
     */
    public static void validateRouteInvariants(Route route, List<ValidationWarning> warnings) {
        validateRouteUpstreams(route, warnings);
    }

    /**
     * Validates an upstream declaring an {@code authType} other than {@code API_KEY} ({@code BEARER} or
     * {@code CUSTOM_HEADER}): it must also carry a {@code key}, or the route-forwarding path silently falls
     * back to sending the caller's own per-request DIAL key under {@code API-KEY} instead, ignoring
     * {@code authType} entirely.
     */
    public static void validateRouteUpstreams(Route route, List<ValidationWarning> warnings) {
        List<Upstream> upstreams = route.getUpstreams();
        if (upstreams == null) {
            return;
        }
        for (int i = 0; i < upstreams.size(); i++) {
            Upstream upstream = upstreams.get(i);
            UpstreamAuthType authType = upstream.getAuthType().type();
            if (authType != UpstreamAuthType.API_KEY && upstream.getKey() == null) {
                warnings.add(new ValidationWarning("upstreams[" + i + "].authType",
                        messageWithEntityPrefix("Route", route.getName(), "authType '" + authType + "' requires a key")));
            }
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
            List<ValidationWarning> warnings = new ArrayList<>();
            validateModelInvariants(model, config.getTranslators(), warnings);
            // Cross-ref check is skip-mode-only — file-loaded abort-mode path (onSkip == null)
            // preserves design 02 §4.2's allowance for pre-existing file-side inconsistency.
            // Strict-mode 422 is enforced at the write controller, not here. Pricing validation
            // has no such excuse (it's self-contained to this model) so it runs unconditionally.
            if (onSkip != null) {
                validateCrossReferences(model, config, warnings);
            }
            if (!warnings.isEmpty()) {
                if (onSkip == null) {
                    throw new InvalidEntityException(ResourceTypes.MODEL, name, warnings);
                }
                iterator.remove();
                onSkip.accept(ResourceTypes.MODEL, new InvalidEntityException(ResourceTypes.MODEL, name, warnings));
            }
        }
    }

    /**
     * Validates that every interceptor reference on the supplied model resolves
     * within the merged {@code config.interceptors} map — file- and blob-sourced
     * interceptors alike key by short name, so a plain {@code containsKey} against
     * that one map key shape is enough. Returns {@code true} when every reference
     * resolves (no warnings appended).
     */
    public static boolean validateCrossReferences(Model model, Config config, List<ValidationWarning> warnings) {
        List<String> refs = model.getInterceptors();
        if (refs == null || refs.isEmpty()) {
            return true;
        }
        Map<String, Interceptor> interceptors = config.getInterceptors();
        boolean resolved = true;
        for (int i = 0; i < refs.size(); i++) {
            String ref = refs.get(i);
            if (ref == null || !interceptors.containsKey(ref)) {
                warnings.add(new ValidationWarning("interceptors[" + i + "]",
                        messageWithEntityPrefix("Model", model.getName(), "Interceptor '" + ref + "' not found in config")));
                resolved = false;
            }
        }
        return resolved;
    }

    /**
     * Model checks the rebuild enforces unconditionally; pre-write paths call this so nothing is
     * stored that the next rebuild would reject. Excludes cross-references and already covers
     * override paths. Translator names missing from {@code translators} are not reported.
     */
    public static void validateModelInvariants(Model model, Map<String, Translator> translators,
                                               List<ValidationWarning> warnings) {
        validatePricing(model, warnings);
        validateUpstreamInterfaces(model, warnings);
        validateDeploymentInterfaces(model, translators, warnings);
    }

    /**
     * Validates that {@code pricing.cacheRead}/{@code cacheWrite} (in any shape) and a decision-tree
     * rate on {@code pricing.prompt}/{@code completion} are only used when
     * {@code pricing.unit == "token"}. Cache rates have no meaning outside token-based pricing; a
     * decision tree's {@code test} evaluates token-based usage fields, which are likewise meaningless
     * for the {@code char_without_whitespace} unit that prices on character counts rather than
     * {@link com.epam.aidial.core.server.token.TokenUsage}'s reported token counts. A flat
     * {@code prompt}/{@code completion} rate has no such restriction, since it's just a per-character
     * price under that unit.
     */
    private static void validatePricing(Model model, List<ValidationWarning> warnings) {
        Pricing pricing = model.getPricing();
        if (pricing == null || "token".equals(pricing.getUnit())) {
            return;
        }
        boolean hasCacheRate = pricing.getCacheRead() != null || pricing.getCacheWrite() != null;
        boolean hasDecisionTreeRate = isDecisionTree(pricing.getPrompt()) || isDecisionTree(pricing.getCompletion());
        if (hasCacheRate || hasDecisionTreeRate) {
            warnings.add(new ValidationWarning("pricing",
                    messageWithEntityPrefix("Model", model.getName(),
                            "Decision-tree cacheRead/cacheWrite and prompt/completion pricing require pricing.unit = \"token\"")));
        }
    }

    private static boolean isDecisionTree(PricingRate pricingRate) {
        return pricingRate != null && !pricingRate.isLeaf();
    }

    /**
     * Validates an upstream declaring {@code interfaces}. Every entry must resolve to a provider url —
     * an entry with no {@code endpoint} of its own needs the upstream's {@code baseUrl} to complete it,
     * or it declares an interface the upstream cannot serve and silently sends no
     * {@code X-UPSTREAM-ENDPOINT}. The upstream must also carry an {@code id}: {@code endpoint} is what
     * identifies an upstream to {@code X-UPSTREAM-ID} routing and to prompt-cache pinning, and this
     * shape does not have one.
     */
    private static void validateUpstreamInterfaces(Model model, List<ValidationWarning> warnings) {
        List<Upstream> upstreams = model.getUpstreams();
        for (int i = 0; i < upstreams.size(); i++) {
            Upstream upstream = upstreams.get(i);
            Map<String, UpstreamInterface> interfaces = upstream.getInterfaces();
            if (interfaces == null || interfaces.isEmpty()) {
                continue;
            }
            if (upstream.getId() == null || upstream.getId().isBlank()) {
                warnings.add(new ValidationWarning("upstreams[" + i + "].id",
                        messageWithEntityPrefix("Model", model.getName(), "An upstream declaring interfaces requires an id")));
            }
            if (upstream.getBaseUrl() != null) {
                continue;
            }
            for (Map.Entry<String, UpstreamInterface> entry : interfaces.entrySet()) {
                if (entry.getValue().getEndpoint() == null) {
                    warnings.add(new ValidationWarning("upstreams[" + i + "].interfaces." + entry.getKey(),
                            messageWithEntityPrefix("Model", model.getName(), "Interface '" + entry.getKey() + "' declares no endpoint and the upstream "
                                    + "declares no baseUrl"))
                    );
                }
            }
        }
    }

    private static void processTranslators(Config config,
                                           @Nullable BiConsumer<ResourceTypes, InvalidEntityException> onSkip) {
        Iterator<Map.Entry<String, Translator>> iterator = config.getTranslators().entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Translator> entry = iterator.next();
            String name = entry.getKey();
            List<ValidationWarning> warnings = new ArrayList<>();
            validateTranslator(name, entry.getValue(), warnings);
            if (warnings.isEmpty()) {
                continue;
            }
            if (onSkip == null) {
                throw new InvalidEntityException(ResourceTypes.TRANSLATOR, name, warnings);
            }
            iterator.remove();
            onSkip.accept(ResourceTypes.TRANSLATOR, new InvalidEntityException(ResourceTypes.TRANSLATOR, name, warnings));
        }
    }

    /**
     * A {@code translators} entry has to say what it converts from: unlike a definition written inline it is
     * declared under no interface, so {@code in} is the only thing tying it to one. {@code out} and
     * {@code baseUrl} need no check here — {@link Translator} rejects an entry missing either as it is read.
     * Shared by every write surface (full load, partial-update, admin apply/validate) so a translator is
     * held to the same rule everywhere.
     */
    public static void validateTranslator(@Nullable String name, @Nullable Translator translator, List<ValidationWarning> warnings) {
        if (translator == null) {
            warnings.add(new ValidationWarning("translator", messageWithEntityPrefix("Translator", name, "Translator is empty")));
            return;
        }
        if (translator.getIn() == null) {
            warnings.add(new ValidationWarning("in", messageWithEntityPrefix("Translator", name, "Translator declares no in")));
        }
        if (namesNoPlugin(translator.getBaseUrl())) {
            warnings.add(new ValidationWarning("baseUrl", messageWithEntityPrefix("Translator", name, NAMELESS_PLUGIN_URL)));
        }
    }

    /**
     * Validates a model's {@code interfaces}. An entry is served by a base url, an HTTP origin's or a plugin's, or by a
     * translator, never by both and never by none, and {@code mode} is what says which — routing and limits both
     * read it, so a config where it disagrees with the fields around it is rejected rather than resolved.
     * Named translator references are resolved against {@code translators} — the registry of the config the
     * model is entering — exactly as the request path resolves them.
     */
    private static void validateDeploymentInterfaces(Deployment deployment, Map<String, Translator> translators,
                                                    List<ValidationWarning> warnings) {
        String entityLabel = deployment.getClass().getSimpleName();
        validateDeploymentPluginUrls(deployment, warnings);
        Map<String, DeploymentInterface> interfaces = deployment.getInterfaces();
        if (interfaces == null) {
            return;
        }
        for (Map.Entry<String, DeploymentInterface> entry : interfaces.entrySet()) {
            DeploymentInterface declared = entry.getValue();
            // an interface mapped to null declares itself unserved and carries nothing to validate
            if (declared == null) {
                continue;
            }
            String field = "interfaces." + entry.getKey();
            if (declared.getMode() == InterfaceMode.TRANSLATOR) {
                validateTranslatedInterface(deployment, entry.getKey(), declared, translators, field, warnings);
            } else if (declared.getTranslator() != null) {
                warnings.add(new ValidationWarning(field, messageWithEntityPrefix(entityLabel, deployment.getName(), "A translator requires mode 'translator'")));
            } else if (declared.getBaseUrl() == null && deployment.getBaseUrl() == null) {
                warnings.add(new ValidationWarning(field, messageWithEntityPrefix(entityLabel, deployment.getName(), "Interface '" + entry.getKey()
                        + "' declares no base_url and the " + entityLabel.toLowerCase(Locale.ROOT) + " declares no baseUrl")));
            }
            validatePluginReference(deployment, declared, field, warnings);
            validateOverridePaths(deployment, entry.getKey(), declared, field, warnings);
        }
    }

    /**
     * Validates the deployment's plugin urls and every interface entry's {@code overridePaths} on a deployment whose interfaces get
     * no other semantic validation — applications, interceptors and toolsets. Models and routers run the same per-entry
     * checks inside {@link #validateDeploymentInterfaces}. Shared by every write surface, each of which turns
     * the warnings into its own layer's failure: a result object for the admin services, a response
     * status for the controllers.
     */
    public static void validateInterfaces(Deployment deployment, List<ValidationWarning> warnings) {
        validateDeploymentPluginUrls(deployment, warnings);
        Map<String, DeploymentInterface> interfaces = deployment.getInterfaces();
        if (interfaces == null) {
            return;
        }
        for (Map.Entry<String, DeploymentInterface> entry : interfaces.entrySet()) {
            DeploymentInterface declared = entry.getValue();
            if (declared == null) {
                continue;
            }
            String field = "interfaces." + entry.getKey();
            validatePluginReference(deployment, declared, field, warnings);
            validateOverridePaths(deployment, entry.getKey(), declared, field, warnings);
        }
    }

    /**
     * Validates an interface entry's {@code overridePaths}. A key this Core does not know is tolerated —
     * exactly as an unknown interface type is — but a known key under an interface that does not own it,
     * a template that does not parse, and a variable the operation cannot render are config contradicting
     * itself. A translated interface routes to its translator, and one served by a plugin url to its plugin, so overrides
     * on either are dead config.
     */
    private static void validateOverridePaths(Deployment deployment, String type, DeploymentInterface declared, String field,
                                              List<ValidationWarning> warnings) {
        Map<String, String> overridePaths = declared.getOverridePaths();
        if (overridePaths == null || overridePaths.isEmpty()) {
            return;
        }
        String entityLabel = deployment.getClass().getSimpleName();
        if (declared.getMode() == InterfaceMode.TRANSLATOR) {
            warnings.add(new ValidationWarning(field,
                    messageWithEntityPrefix(entityLabel, deployment.getName(), "A translated interface is served by its translator: overridePaths has no effect")));
            return;
        }
        if (DeploymentEndpointUtil.isPluginUrl(declared.getBaseUrl() != null ? declared.getBaseUrl() : deployment.getBaseUrl())) {
            warnings.add(new ValidationWarning(field,
                    messageWithEntityPrefix(entityLabel, deployment.getName(), "An interface with a plugin is served by its plugin: overridePaths has no effect")));
            return;
        }
        for (Map.Entry<String, String> entry : overridePaths.entrySet()) {
            InterfacePathMapping pathMapping = InterfacePathMapping.find(entry.getKey());
            if (pathMapping == null) {
                continue;
            }
            String keyField = field + ".overridePaths." + entry.getKey();
            if (pathMapping.getInterfaceType() != InterfaceType.find(type)) {
                warnings.add(new ValidationWarning(keyField, messageWithEntityPrefix(entityLabel, deployment.getName(), "Override path key '" + entry.getKey()
                        + "' belongs to interface '" + pathMapping.getInterfaceType().getValue() + "'")));
                continue;
            }
            validateOverridePathTemplate(deployment, pathMapping, entry.getValue(), keyField, warnings);
        }
    }

    private static void validateOverridePathTemplate(Deployment deployment, InterfacePathMapping pathMapping, @Nullable String template,
                                                     String keyField, List<ValidationWarning> warnings) {
        String entityLabel = deployment.getClass().getSimpleName();
        if (template == null || template.isBlank()) {
            warnings.add(new ValidationWarning(keyField, messageWithEntityPrefix(entityLabel, deployment.getName(), "Override path is empty")));
            return;
        }
        if (!pathMapping.isIdApplicable() && PathTemplateUtil.referencesId(template)) {
            warnings.add(new ValidationWarning(keyField, messageWithEntityPrefix(entityLabel, deployment.getName(), "The operation carries no id: {id} cannot render")));
        }
    }

    /**
     * Validates the plugin urls of an interface entry, its own {@code base_url} and its inline translator's. Interceptor
     * hops are always sent over HTTP, so an interceptor cannot name a plugin. A name no {@code plugins} entry has leaves
     * the interface unserved, not the deployment invalid, as for a translator; an empty name is a defect.
     */
    private static void validatePluginReference(Deployment deployment, DeploymentInterface declared, String field,
                                                List<ValidationWarning> warnings) {
        String entityLabel = deployment.getClass().getSimpleName();
        String translatorUrl = inlineTranslatorBaseUrl(declared);
        if (deployment instanceof Interceptor && (DeploymentEndpointUtil.isPluginUrl(declared.getBaseUrl()) || DeploymentEndpointUtil.isPluginUrl(translatorUrl))) {
            warnings.add(new ValidationWarning(field, messageWithEntityPrefix(entityLabel, deployment.getName(), NO_PLUGIN_FOR_INTERCEPTOR)));
            return;
        }
        if (namesNoPlugin(declared.getBaseUrl()) || namesNoPlugin(translatorUrl)) {
            warnings.add(new ValidationWarning(field, messageWithEntityPrefix(entityLabel, deployment.getName(), NAMELESS_PLUGIN_URL)));
        }
    }

    /**
     * Validates the plugin urls a deployment declares outside its interface entries. Its {@code baseUrl} may name a
     * plugin, which then serves the entries declaring no {@code base_url}, except on an interceptor. {@code endpoint}
     * and {@code responsesEndpoint} name an origin no plugin serves: a plugin url there would reach the HTTP client and
     * fail at request time. A router rejects both fields whatever they hold.
     */
    private static void validateDeploymentPluginUrls(Deployment deployment, List<ValidationWarning> warnings) {
        String entityLabel = deployment.getClass().getSimpleName();
        if (deployment instanceof Interceptor && DeploymentEndpointUtil.isPluginUrl(deployment.getBaseUrl())) {
            warnings.add(new ValidationWarning("baseUrl", messageWithEntityPrefix(entityLabel, deployment.getName(), NO_PLUGIN_FOR_INTERCEPTOR)));
        } else if (namesNoPlugin(deployment.getBaseUrl())) {
            warnings.add(new ValidationWarning("baseUrl", messageWithEntityPrefix(entityLabel, deployment.getName(), NAMELESS_PLUGIN_URL)));
        }
        if (deployment instanceof Router) {
            return;
        }
        if (DeploymentEndpointUtil.isPluginUrl(deployment.getEndpoint())) {
            warnings.add(new ValidationWarning("endpoint", messageWithEntityPrefix(entityLabel, deployment.getName(), UNSERVED_PLUGIN_URL)));
        }
        if (DeploymentEndpointUtil.isPluginUrl(deployment.getResponsesEndpoint())) {
            warnings.add(new ValidationWarning("responsesEndpoint", messageWithEntityPrefix(entityLabel, deployment.getName(), UNSERVED_PLUGIN_URL)));
        }
    }

    /**
     * Whether the entry's translator is written inline and its {@code baseUrl} is a plugin url. A named translator is not
     * resolved here: its entry can change after the deployment is written, so the request path checks it.
     */
    public static boolean hasInlineTranslatorPlugin(DeploymentInterface declared) {
        return DeploymentEndpointUtil.isPluginUrl(inlineTranslatorBaseUrl(declared));
    }

    @Nullable
    private static String inlineTranslatorBaseUrl(DeploymentInterface declared) {
        TranslatorRef translator = declared.getTranslator();
        return translator == null || translator.getInline() == null ? null : translator.getInline().getBaseUrl();
    }

    private static boolean namesNoPlugin(@Nullable String url) {
        String name = DeploymentEndpointUtil.pluginName(url);
        return name != null && name.isBlank();
    }

    private static void validateTranslatedInterface(Deployment deployment, String type, DeploymentInterface declared,
                                                    Map<String, Translator> translators,
                                                    String field, List<ValidationWarning> warnings) {
        String entityLabel = deployment.getClass().getSimpleName();
        if (declared.getBaseUrl() != null) {
            warnings.add(new ValidationWarning(field,
                    messageWithEntityPrefix(entityLabel, deployment.getName(), "An interface is served either by a translator or by a base_url, not by both")));
        }
        TranslatorRef translator = declared.getTranslator();
        if (translator == null) {
            warnings.add(new ValidationWarning(field, messageWithEntityPrefix(entityLabel, deployment.getName(), "Mode 'translator' requires a translator")));
            return;
        }
        // a name with no entry to resolve to leaves the interface unserved rather than the model invalid:
        // the request path answers 503 for it, exactly as it does for an application or interceptor, and
        // registering the entry serves it with no edit here. A registry entry that exists is always
        // complete — Translator sees to that — so what is rejected here is only config contradicting
        // itself, which no reload will fix.
        Translator definition = translator.resolve(translators);
        if (definition == null) {
            return;
        }
        if (definition.getIn() != null && definition.getIn() != InterfaceType.find(type)) {
            warnings.add(new ValidationWarning(field,
                    messageWithEntityPrefix(entityLabel, deployment.getName(), "Translator converts from '" + definition.getIn().getValue() + "', not from '" + type + "'")));
        }
        // a definition written inline names no in: the interface it sits under is what it converts from
        String in = definition.getIn() != null ? definition.getIn().getValue() : type;
        if (in.equals(definition.getOut().getValue())) {
            warnings.add(new ValidationWarning(field,
                    messageWithEntityPrefix(entityLabel, deployment.getName(), "A translator cannot convert '" + in
                            + "' to itself: its output would arrive back on the interface it came from")));
            return;
        }
        validateTranslatorOutput(deployment, definition, translators, field, warnings);
    }

    /**
     * The interface a translator converts to has to be one the model serves itself, and serves pass-through:
     * the translator calls Core back on it to have the completion served.
     *
     * <p>This is what bounds a chain of translators to the single hop a request already makes. An
     * {@code out} landing on a second translated interface is rejected here, so no such chain can be
     * configured, and with none there is no cycle to detect: the interface converting back to the first one
     * would have to be the second hop of a chain this rule already refuses. Which leaves the one-interface
     * cycle, a translator converting an interface to itself, rejected by the caller.
     */
    private static void validateTranslatorOutput(Deployment deployment, Translator definition, Map<String, Translator> translators,
                                                 String field, List<ValidationWarning> warnings) {
        String entityLabel = deployment.getClass().getSimpleName();
        String kind = entityLabel.toLowerCase(Locale.ROOT);
        InterfaceType out = definition.getOut();
        if (DeploymentEndpointUtil.resolveMode(deployment, out) == InterfaceMode.TRANSLATOR) {
            warnings.add(new ValidationWarning(field,
                    messageWithEntityPrefix(entityLabel, deployment.getName(), "The " + kind + " serves '" + out.getValue()
                            + "' through a translator of its own, which the translator here converts to: "
                            + "the callback would arrive on a translated interface and be handed to a translator again")));
            return;
        }
        if (DeploymentEndpointUtil.resolveServingEndpoint(deployment, out, translators) == null) {
            warnings.add(new ValidationWarning(field,
                    messageWithEntityPrefix(entityLabel, deployment.getName(), "The " + kind + " does not serve '" + out.getValue() + "', which the translator converts to")));
        }
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
            List<ValidationWarning> warnings = new ArrayList<>();
            validateInterfaces(application, warnings);
            if (!warnings.isEmpty()) {
                if (onSkip == null) {
                    throw new InvalidEntityException(ResourceTypes.APPLICATION, name, warnings);
                }
                iterator.remove();
                onSkip.accept(ResourceTypes.APPLICATION, new InvalidEntityException(ResourceTypes.APPLICATION, name, warnings));
                continue;
            }
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
            Map<String, Limit> roleLimits = role.getLimits();
            if (roleLimits != null && !roleLimits.isEmpty()) {
                for (Map.Entry<String, Limit> limitEntry : roleLimits.entrySet()) {
                    log.debug("Loading {} for deployment `{}`", limitEntry.getValue(), limitEntry.getKey());
                }
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
            List<ValidationWarning> warnings = new ArrayList<>();
            validateInterfaces(interceptor, warnings);
            if (!warnings.isEmpty()) {
                if (onSkip == null) {
                    throw new InvalidEntityException(ResourceTypes.INTERCEPTOR, name, warnings);
                }
                iterator.remove();
                onSkip.accept(ResourceTypes.INTERCEPTOR, new InvalidEntityException(ResourceTypes.INTERCEPTOR, name, warnings));
                continue;
            }
            log.debug("Loading {}", interceptor);
        }
    }

    /**
     * The rules a router must satisfy wherever it enters the config: the file load, a Configuration API write, a
     * validate or apply manifest and a replica event. Names the router (the messages carry its name, and a request
     * body or manifest spec has none) and turns an explicit {@code "interceptors": null} into an empty list, which
     * the request path iterates. Duplicate ids are checked by the callers against the namespace they see.
     */
    public static void validateRouterInvariants(String name, Router router, Map<String, Translator> translators,
                                                List<ValidationWarning> warnings) {
        router.setName(name);
        if (router.getInterceptors() == null) {
            router.setInterceptors(List.of());
        } else if (!router.getInterceptors().isEmpty()) {
            warnings.add(new ValidationWarning("interceptors", messageWithEntityPrefix("Router", name, "A router declares no interceptors")));
        }
        if (router.getEndpoint() != null || router.getResponsesEndpoint() != null) {
            warnings.add(new ValidationWarning("endpoint", messageWithEntityPrefix("Router", name, "A router declares interfaces, not endpoint")));
        }
        if ((router.getApplicationTypeSchemaId() == null) != (router.getApplicationProperties() == null)) {
            String field = router.getApplicationTypeSchemaId() == null ? "applicationProperties" : "applicationTypeSchemaId";
            warnings.add(new ValidationWarning(field, messageWithEntityPrefix("Router", name,
                    "A router declares applicationTypeSchemaId and applicationProperties together")));
        }
        validateDecision(name, router, warnings);
        validateDeploymentInterfaces(router, translators, warnings);
    }

    /**
     * A router in decision mode never forwards the client's request, so no interface of it is served by a translator.
     * An interface that names a plugin is served: the plugin answers the decide request in process. The decide request settings
     * are read in decision mode only: on a router in proxy mode they would do nothing.
     */
    private static void validateDecision(String name, Router router, List<ValidationWarning> warnings) {
        Router.Decision decision = router.getDecision();
        if (router.getMode() != Router.Mode.DECISION) {
            if (decision != null) {
                warnings.add(new ValidationWarning("decision", messageWithEntityPrefix("Router", name, "decision requires mode 'decision'")));
            }
            return;
        }
        if (router.getInterfaces() != null) {
            router.getInterfaces().forEach((type, declared) -> {
                if (declared != null && (declared.getMode() == InterfaceMode.TRANSLATOR || declared.getTranslator() != null)) {
                    warnings.add(new ValidationWarning("interfaces." + type, messageWithEntityPrefix("Router", name,
                            "A router in mode 'decision' serves no interface through a translator")));
                }
            });
        }
        if (decision == null) {
            return;
        }
        if (decision.getTimeoutMs() != null && decision.getTimeoutMs() <= 0) {
            warnings.add(new ValidationWarning("decision.timeoutMs", messageWithEntityPrefix("Router", name, "decision.timeoutMs must be positive")));
        }
        if (decision.getMaxDecisions() != null && (decision.getMaxDecisions() < 1 || decision.getMaxDecisions() > 10)) {
            warnings.add(new ValidationWarning("decision.maxDecisions",
                    messageWithEntityPrefix("Router", name, "decision.maxDecisions must be between 1 and 10")));
        }
    }

    /**
     * Targeted per-type helper for {@link MergedConfigStore}'s partial-update path: validates the single written
     * router with {@link #validateRouterInvariants}.
     */
    static void validateSingleRouter(Config config, String mapKey,
                                     @Nullable BiConsumer<ResourceTypes, InvalidEntityException> onSkip) {
        Router router = config.getRouters().get(mapKey);
        if (router == null) {
            return;
        }
        List<ValidationWarning> warnings = new ArrayList<>();
        validateRouterInvariants(mapKey, router, config.getTranslators(), warnings);
        if (warnings.isEmpty()) {
            return;
        }
        if (onSkip == null) {
            throw new InvalidEntityException(ResourceTypes.ROUTER, mapKey, warnings);
        }
        config.getRouters().remove(mapKey);
        onSkip.accept(ResourceTypes.ROUTER, new InvalidEntityException(ResourceTypes.ROUTER, mapKey, warnings));
    }

    private static void processRouters(Config config, Set<String> deploymentIds,
                                       @Nullable BiConsumer<ResourceTypes, InvalidEntityException> onSkip) {
        Iterator<Map.Entry<String, Router>> iterator = config.getRouters().entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Router> entry = iterator.next();
            String name = entry.getKey();
            if (skipOnDuplicate(name, ResourceTypes.ROUTER, deploymentIds, onSkip, iterator)) {
                continue;
            }
            Router router = entry.getValue();
            List<ValidationWarning> warnings = new ArrayList<>();
            validateRouterInvariants(name, router, config.getTranslators(), warnings);
            if (!warnings.isEmpty()) {
                if (onSkip == null) {
                    throw new InvalidEntityException(ResourceTypes.ROUTER, name, warnings);
                }
                iterator.remove();
                onSkip.accept(ResourceTypes.ROUTER, new InvalidEntityException(ResourceTypes.ROUTER, name, warnings));
                continue;
            }
            log.debug("Loading {}", router);
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
     * Targeted per-type helper for {@link MergedConfigStore}'s partial-update path — validates the
     * single written entry. Sets the alias's name from {@code mapKey} and validates its target against the
     * current config. A missing target is only a warning: the alias is kept but its calls answer 503.
     */
    static void validateSingleAlias(Config config, String mapKey,
                                    @Nullable BiConsumer<ResourceTypes, InvalidEntityException> onSkip) {
        Alias alias = config.getAliases().get(mapKey);
        if (alias == null) {
            return;
        }
        applyAliasEntry(config, mapKey, alias, onSkip, null);
    }

    /**
     * Runs after every other deployment type, so the name check sees all of them and a target is looked up in maps
     * already cleared of invalid entries. A target the config does not have is only a warning: one broken alias must
     * not stop the config from loading, and calls of it answer 503.
     */
    private static void processAliases(Config config, Set<String> deploymentIds,
                                       @Nullable BiConsumer<ResourceTypes, InvalidEntityException> onSkip) {
        Iterator<Map.Entry<String, Alias>> iterator = config.getAliases().entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Alias> entry = iterator.next();
            String name = entry.getKey();
            if (skipOnDuplicate(name, ResourceTypes.ALIAS, deploymentIds, onSkip, iterator)) {
                continue;
            }
            if (applyAliasEntry(config, name, entry.getValue(), onSkip, iterator)) {
                log.debug("Loading {}", entry.getValue());
            }
        }
    }

    /**
     * Sets name, validates target, logs warnings for a single alias entry. Removes the entry and routes through
     * {@code onSkip} when validation fails. Returns {@code true} when the entry is kept, {@code false} when removed.
     * Uses {@code removeFrom} to remove via the iterator (full-scan path); falls back to map remove (single-entry path).
     */
    private static boolean applyAliasEntry(Config config, String name, Alias alias,
                                            @Nullable BiConsumer<ResourceTypes, InvalidEntityException> onSkip,
                                            @Nullable Iterator<?> removeFrom) {
        alias.setName(name);
        List<ValidationWarning> warnings = AliasTargets.validate(alias, config);
        if (!warnings.isEmpty()) {
            if (onSkip == null) {
                throw new InvalidEntityException(ResourceTypes.ALIAS, name, warnings);
            }
            if (removeFrom != null) {
                removeFrom.remove();
            } else {
                config.getAliases().remove(name);
            }
            onSkip.accept(ResourceTypes.ALIAS, new InvalidEntityException(ResourceTypes.ALIAS, name, warnings));
            return false;
        }
        if (AliasTargets.isTargetMissing(alias, config)) {
            log.warn("Alias {} points to a missing deployment {}", name, alias.getTarget().getPath());
        }
        warnOnLimitsUnderAliasName(config, name);
        return true;
    }

    /**
     * A call through an alias is limited under its target's name, so a role limit keyed by the alias's name has no
     * effect; it is most likely a mistake.
     */
    private static void warnOnLimitsUnderAliasName(Config config, String aliasName) {
        config.getRoles().forEach((roleName, role) -> {
            if (role != null && role.getLimits() != null && role.getLimits().containsKey(aliasName)) {
                log.warn("Role {} defines a limit under alias {}, which has no effect: define it under the alias's target",
                        roleName, aliasName);
            }
        });
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

    /**
     * Returns {@code true} if {@code shortName} is already claimed by a MODEL, APPLICATION,
     * TOOL_SET, INTERCEPTOR, ROUTER or ALIAS other than {@code type} — those six entity types share one
     * flat deployment-id namespace.
     */
    public static boolean isDeploymentIdTakenByAnotherDeploymentType(Config config,
                                                                     ResourceTypes type,
                                                                     String shortName) {
        Map<ResourceTypes, Map<String, ?>> deploymentIdSpaces = Map.of(
                ResourceTypes.MODEL, config.getModels(),
                ResourceTypes.APPLICATION, config.getApplications(),
                ResourceTypes.TOOL_SET, config.getToolsets(),
                ResourceTypes.INTERCEPTOR, config.getInterceptors(),
                ResourceTypes.ROUTER, config.getRouters(),
                ResourceTypes.ALIAS, config.getAliases());
        if (!deploymentIdSpaces.containsKey(type)) {
            throw new IllegalArgumentException("Not a deployment type: " + type);
        }
        return deploymentIdSpaces.entrySet().stream()
                .anyMatch(entry -> entry.getKey() != type && entry.getValue().containsKey(shortName));
    }

    private static boolean isValidResourceKey(String resourceKey) {
        return RESOURCE_KEY_PATTERN.matcher(resourceKey).matches();
    }

    /** Human-readable form of {@link #RESOURCE_KEY_PATTERN} for error messages on write surfaces. */
    public static String resourceKeyPattern() {
        return RESOURCE_KEY_PATTERN.pattern();
    }

    // ToolSet map keys are always short names now, file- and blob-sourced alike — same check as
    // isValidResourceKey. Kept as its own named entry point since ToolSet call sites reason about
    // it as "the toolset key check" rather than the generic one.
    public static boolean isValidToolSetKey(String resourceKey) {
        return isValidResourceKey(resourceKey);
    }
}
