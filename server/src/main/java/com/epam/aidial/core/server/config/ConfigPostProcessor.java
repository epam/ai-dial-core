package com.epam.aidial.core.server.config;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.DeploymentInterface;
import com.epam.aidial.core.config.ExternalService;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.InterfaceMode;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Limit;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Pricing;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.Role;
import com.epam.aidial.core.config.RoleBasedEntity;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.config.Translator;
import com.epam.aidial.core.config.TranslatorRef;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.config.UpstreamInterface;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsChangeMode;
import com.epam.aidial.core.credentials.validation.AuthSettingsValidator;
import com.epam.aidial.core.credentials.validation.AuthSettingsValidatorFactory;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.util.DeploymentEndpointUtil;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
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
        linkTranslators(config);
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
    static void validateSingleModel(Config config, String mapKey,
                                    @Nullable BiConsumer<ResourceTypes, InvalidEntityException> onSkip) {
        Model model = config.getModels().get(mapKey);
        if (model == null) {
            return;
        }
        model.setName(mapKey);
        List<ValidationWarning> warnings = new ArrayList<>();
        linkTranslators(model, config.getTranslators());
        validatePricing(model, warnings);
        validateUpstreamInterfaces(model, warnings);
        validateDeploymentInterfaces(model, warnings);
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
            validatePricing(model, warnings);
            validateUpstreamInterfaces(model, warnings);
            validateDeploymentInterfaces(model, warnings);
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
        for (int i = 0; i < refs.size(); i++) {
            String ref = refs.get(i);
            if (ref == null || !interceptors.containsKey(ref)) {
                warnings.add(new ValidationWarning("interceptors[" + i + "]",
                        "Interceptor '" + ref + "' not found in config"));
            }
        }
        return warnings.isEmpty();
    }

    /**
     * Validates that {@code pricing.cacheRead}/{@code cacheWrite} are only set when
     * {@code pricing.unit == "token"} — those rates are meaningless for the
     * {@code char_without_whitespace} unit, which prices on character counts rather than
     * {@link com.epam.aidial.core.server.token.TokenUsage}'s reported cache token counts.
     */
    public static void validatePricing(Model model, List<ValidationWarning> warnings) {
        Pricing pricing = model.getPricing();
        if (pricing == null) {
            return;
        }
        boolean hasCacheRate = pricing.getCacheRead() != null || pricing.getCacheWrite() != null;
        if (hasCacheRate && !"token".equals(pricing.getUnit())) {
            warnings.add(new ValidationWarning("pricing",
                    "cacheRead/cacheWrite pricing requires pricing.unit = \"token\""));
        }
    }

    /**
     * Validates an upstream declaring {@code interfaces}. Every entry must resolve to a provider url —
     * an entry with no {@code endpoint} of its own needs the upstream's {@code baseUrl} to complete it,
     * or it declares an interface the upstream cannot serve and silently sends no
     * {@code X-UPSTREAM-ENDPOINT}. The upstream must also carry an {@code id}: {@code endpoint} is what
     * identifies an upstream to {@code X-UPSTREAM-ID} routing and to prompt-cache pinning, and this
     * shape does not have one.
     */
    public static void validateUpstreamInterfaces(Model model, List<ValidationWarning> warnings) {
        List<Upstream> upstreams = model.getUpstreams();
        for (int i = 0; i < upstreams.size(); i++) {
            Upstream upstream = upstreams.get(i);
            Map<String, UpstreamInterface> interfaces = upstream.getInterfaces();
            if (interfaces == null || interfaces.isEmpty()) {
                continue;
            }
            if (upstream.getId() == null || upstream.getId().isBlank()) {
                warnings.add(new ValidationWarning("upstreams[" + i + "].id",
                        "An upstream declaring interfaces requires an id"));
            }
            if (upstream.getBaseUrl() != null) {
                continue;
            }
            for (Map.Entry<String, UpstreamInterface> entry : interfaces.entrySet()) {
                if (entry.getValue().getEndpoint() == null) {
                    warnings.add(new ValidationWarning("upstreams[" + i + "].interfaces." + entry.getKey(),
                            "Interface '" + entry.getKey() + "' declares no endpoint and the upstream "
                                    + "declares no baseUrl")
                    );
                }
            }
        }
    }

    /**
     * Points every named {@code interfaces.<type>.translator} at its {@link Config#getTranslators()} entry.
     * Runs on every load, so an edit to the registry reaches the deployments referencing it; a name with no
     * entry stays unlinked and its interface serves nothing, the same as one with no base url.
     */
    private static void linkTranslators(Config config) {
        Map<String, Translator> translators = config.getTranslators();
        // toolsets are left out because they serve MCP alone
        linkTranslators(config.getModels().values(), translators);
        linkTranslators(config.getApplications().values(), translators);
        linkTranslators(config.getInterceptors().values(), translators);
    }

    private static void linkTranslators(Collection<? extends Deployment> deployments, Map<String, Translator> translators) {
        for (Deployment deployment : deployments) {
            linkTranslators(deployment, translators);
        }
    }

    /**
     * Points the deployment's named {@code interfaces.<type>.translator} entries at their
     * {@link Config#getTranslators()} entries. Every path putting a deployment into a live config has to run
     * this: a reference left unlinked serves nothing on that interface, and says nothing about why.
     */
    static void linkTranslators(Deployment deployment, Map<String, Translator> translators) {
        Map<String, DeploymentInterface> interfaces = deployment.getInterfaces();
        if (interfaces == null) {
            return;
        }
        for (DeploymentInterface declared : interfaces.values()) {
            TranslatorRef translator = declared == null ? null : declared.getTranslator();
            if (translator != null && translator.getName() != null) {
                translator.setDefinition(translators.get(translator.getName()));
            }
        }
    }

    /**
     * Validates a model's {@code interfaces}. An entry is served either by a base url or by a translator,
     * never by both and never by neither, and {@code mode} is what says which — routing and limits both
     * read it, so a config where it disagrees with the fields around it is rejected rather than resolved.
     */
    public static void validateDeploymentInterfaces(Model model, List<ValidationWarning> warnings) {
        Map<String, DeploymentInterface> interfaces = model.getInterfaces();
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
                validateTranslatedInterface(model, entry.getKey(), declared, field, warnings);
            } else if (declared.getTranslator() != null) {
                warnings.add(new ValidationWarning(field, "A translator requires mode 'translator'"));
            } else if (declared.getBaseUrl() == null && model.getBaseUrl() == null) {
                warnings.add(new ValidationWarning(field,
                        "Interface '" + entry.getKey() + "' declares no base_url and the model declares no baseUrl"));
            }
        }
    }

    private static void validateTranslatedInterface(Model model, String type, DeploymentInterface declared,
                                                    String field, List<ValidationWarning> warnings) {
        if (declared.getBaseUrl() != null) {
            warnings.add(new ValidationWarning(field,
                    "An interface is served either by a translator or by a base_url, not by both"));
        }
        TranslatorRef translator = declared.getTranslator();
        if (translator == null) {
            warnings.add(new ValidationWarning(field, "Mode 'translator' requires a translator"));
            return;
        }
        // a reference resolving to no url — an unknown name, or an entry declaring no baseUrl — leaves the
        // interface unserved rather than the model invalid: the request path answers 503 for it, exactly as
        // it does for an application or interceptor, and a later reload can link it. Only config that
        // contradicts itself is rejected here, because no reload will fix that.
        Translator definition = translator.getDefinition();
        if (definition == null || definition.getBaseUrl() == null) {
            return;
        }
        if (definition.getIn() != null && !definition.getIn().equals(type)) {
            warnings.add(new ValidationWarning(field,
                    "Translator converts from '" + definition.getIn() + "', not from '" + type + "'"));
        }
        // a definition written inline names no in: the interface it sits under is what it converts from
        String in = definition.getIn() != null ? definition.getIn() : type;
        if (in.equals(definition.getOut())) {
            warnings.add(new ValidationWarning(field,
                    "A translator cannot convert '" + in + "' to itself: its output would arrive back on the interface it came from"));
            return;
        }
        validateTranslatorOutput(model, definition, field, warnings);
    }

    /**
     * The interface a translator converts to has to be one the model serves itself: the translator calls
     * Core back on it to have the completion served, and a call landing on another translator would loop.
     */
    private static void validateTranslatorOutput(Model model, Translator definition,
                                                 String field, List<ValidationWarning> warnings) {
        // no out, or one this Core does not know: nothing to check the model against
        InterfaceType out = definition.getOut() == null ? null : InterfaceType.find(definition.getOut());
        if (out == null) {
            return;
        }
        if (DeploymentEndpointUtil.resolveMode(model, out) == InterfaceMode.TRANSLATOR
                || DeploymentEndpointUtil.resolveServingEndpoint(model, out) == null) {
            warnings.add(new ValidationWarning(field,
                    "The model does not serve '" + definition.getOut() + "', which the translator converts to"));
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

    /**
     * Returns {@code true} if {@code shortName} is already claimed by a MODEL, APPLICATION,
     * TOOL_SET, or INTERCEPTOR other than {@code type} — those four entity types share one
     * flat deployment-id namespace.
     */
    public static boolean isDeploymentIdTakenByAnotherDeploymentType(Config config,
                                                                     ResourceTypes type,
                                                                     String shortName) {
        Map<ResourceTypes, Map<String, ?>> deploymentIdSpaces = Map.of(
                ResourceTypes.MODEL, config.getModels(),
                ResourceTypes.APPLICATION, config.getApplications(),
                ResourceTypes.TOOL_SET, config.getToolsets(),
                ResourceTypes.INTERCEPTOR, config.getInterceptors());
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
