package com.epam.aidial.core.server.config;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.DeploymentInterface;
import com.epam.aidial.core.config.InterfaceType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared helper that migrates legacy {@code endpoint} / {@code responsesEndpoint} fields into the
 * typed {@code interfaces} map. Two layers reuse the same {@link #authority(String)} derivation:
 *
 * <ul>
 *   <li><b>Layer A</b> — {@link #migrateDeployment(Deployment)} mutates the in-memory POJO. Runs on
 *       every config load/reload (file, blob, Configuration API) so routing always sees interfaces.</li>
 *   <li><b>Layer B</b> — {@link #migrateRawTree(JsonNode)} rewrites the raw JSON tree, preserving every
 *       other field/key order/unknown property. Used by {@link FileConfigStore} for best-effort
 *       config-file write-back, opt-in via the {@code ENDPOINT_MIGRATION_TO_INTERFACES} env var. When
 *       it is disabled the file is left untouched and {@link #hasLegacyEndpoints(JsonNode)} drives an
 *       obsolescence warning instead.</li>
 * </ul>
 *
 * <p>Both layers are idempotent and native-interfaces-preserving (a non-empty {@code interfaces} wins).
 *
 * <p>Models, applications and interceptors are all migrated the same way: only the legacy endpoint's
 * authority ({@link #authority(String)}) is kept as the {@code base_url} and the ingress path is appended
 * at request time. An interceptor exposes whatever interface Core received the request on (chat
 * completions today), so it is routed exactly like a model/application.
 */
@Slf4j
public final class InterfaceMigration {

    static final String ENDPOINT_FIELD = "endpoint";
    static final String RESPONSES_ENDPOINT_FIELD = "responsesEndpoint";
    static final String INTERFACES_FIELD = "interfaces";
    static final String BASE_URL_FIELD = "base_url";
    static final String MODELS_FIELD = "models";
    static final String APPLICATIONS_FIELD = "applications";
    static final String INTERCEPTORS_FIELD = "interceptors";

    private InterfaceMigration() {
    }

    /**
     * Derives the {@code base_url} for an interface from a legacy endpoint URL: authority only
     * ({@code scheme://host[:port]}, including userinfo if present), the whole path is dropped.
     * On parse failure or a missing scheme/authority, logs a warning and falls back to the raw
     * string; never throws (a bad endpoint must not abort the load).
     */
    public static String authority(String url) {
        if (url == null) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            String authority = uri.getAuthority();
            if (scheme != null && authority != null) {
                return scheme + "://" + authority;
            }
        } catch (RuntimeException e) {
            log.warn("Failed to parse legacy endpoint '{}' for interface migration; using raw value", url);
            return url;
        }
        log.warn("Legacy endpoint '{}' has no scheme/authority; using raw value for interface migration", url);
        return url;
    }

    /**
     * Layer A. Populates {@code deployment.interfaces} from legacy {@code endpoint} /
     * {@code responsesEndpoint} when no interfaces are declared yet. Returns {@code true} when the
     * deployment was changed.
     */
    public static boolean migrateDeployment(Deployment deployment) {
        if (deployment == null) {
            return false;
        }
        Map<String, DeploymentInterface> existing = deployment.getInterfaces();
        if (existing != null && !existing.isEmpty()) {
            return false; // native interfaces always win
        }
        Map<String, DeploymentInterface> migrated = new LinkedHashMap<>();
        if (deployment.getEndpoint() != null) {
            migrated.put(
                    InterfaceType.OPENAI_CHAT_COMPLETIONS.getValue(),
                    new DeploymentInterface(authority(deployment.getEndpoint()))
            );
        }
        if (deployment.getResponsesEndpoint() != null) {
            migrated.put(
                    InterfaceType.OPENAI_RESPONSES.getValue(),
                    new DeploymentInterface(authority(deployment.getResponsesEndpoint()))
            );
        }
        if (migrated.isEmpty()) {
            return false;
        }
        deployment.setInterfaces(migrated);
        return true;
    }

    /**
     * Layer B. Rewrites the {@code models} and {@code applications} sections of a raw config tree,
     * migrating each legacy deployment node into the interfaces shape. Returns {@code true} when any
     * node was changed.
     */
    public static boolean migrateRawTree(JsonNode root) {
        if (root == null || !root.isObject()) {
            return false;
        }
        boolean changed = false;
        changed |= migrateRawSection(root.get(MODELS_FIELD));
        changed |= migrateRawSection(root.get(APPLICATIONS_FIELD));
        changed |= migrateRawSection(root.get(INTERCEPTORS_FIELD));
        return changed;
    }

    /**
     * Non-mutating detector mirroring {@link #migrateRawTree}: returns {@code true} when any model,
     * application or interceptor node still carries a legacy {@code endpoint}/{@code responsesEndpoint}
     * field that write-back would strip — including nodes that already declare native {@code interfaces}
     * (the redundant legacy fields are still removed). Used to decide whether to warn when config-file
     * write-back is disabled.
     */
    public static boolean hasLegacyEndpoints(JsonNode root) {
        if (root == null || !root.isObject()) {
            return false;
        }
        return sectionHasLegacy(root.get(MODELS_FIELD))
                || sectionHasLegacy(root.get(APPLICATIONS_FIELD))
                || sectionHasLegacy(root.get(INTERCEPTORS_FIELD));
    }

    private static boolean sectionHasLegacy(JsonNode section) {
        if (section == null || !section.isObject()) {
            return false;
        }
        for (JsonNode entry : section) {
            if (entry instanceof ObjectNode node && nodeHasLegacy(node)) {
                return true;
            }
        }
        return false;
    }

    private static boolean migrateRawSection(JsonNode section) {
        if (section == null || !section.isObject()) {
            return false;
        }
        boolean changed = false;
        for (JsonNode entry : section) {
            if (entry instanceof ObjectNode node) {
                changed |= migrateRawDeploymentNode(node);
            }
        }
        return changed;
    }

    /**
     * Raw-tree migration for a single deployment node. Always removes the obsolete {@code endpoint} /
     * {@code responsesEndpoint} fields; derives {@code interfaces} from them only when the node does not
     * already declare native interfaces (a non-empty {@code interfaces} wins and is left untouched).
     * Preserves all other fields and key order. Returns {@code true} when the node was changed.
     */
    static boolean migrateRawDeploymentNode(ObjectNode node) {
        if (node == null || !nodeHasLegacy(node)) {
            return false;
        }
        if (!hasNativeInterfaces(node)) {
            JsonNode endpoint = node.get(ENDPOINT_FIELD);
            JsonNode responsesEndpoint = node.get(RESPONSES_ENDPOINT_FIELD);
            ObjectNode interfaces = node.objectNode();
            if (isTextual(endpoint)) {
                interfaces.set(InterfaceType.OPENAI_CHAT_COMPLETIONS.getValue(),
                        interfaceNode(node, authority(endpoint.asText())));
            }
            if (isTextual(responsesEndpoint)) {
                interfaces.set(InterfaceType.OPENAI_RESPONSES.getValue(),
                        interfaceNode(node, authority(responsesEndpoint.asText())));
            }
            node.set(INTERFACES_FIELD, interfaces);
        }
        node.remove(ENDPOINT_FIELD);
        node.remove(RESPONSES_ENDPOINT_FIELD);
        return true;
    }

    /**
     * Literal presence of a legacy {@code endpoint} / {@code responsesEndpoint} field, independent of
     * whether native {@code interfaces} are declared. These obsolete fields are always stripped by
     * {@link #migrateRawDeploymentNode} and reported by {@link #hasLegacyEndpoints}.
     */
    private static boolean nodeHasLegacy(ObjectNode node) {
        return isTextual(node.get(ENDPOINT_FIELD)) || isTextual(node.get(RESPONSES_ENDPOINT_FIELD));
    }

    private static boolean hasNativeInterfaces(ObjectNode node) {
        JsonNode interfaces = node.get(INTERFACES_FIELD);
        return interfaces != null && interfaces.isObject() && !interfaces.isEmpty();
    }

    private static ObjectNode interfaceNode(ObjectNode parent, String baseUrl) {
        ObjectNode iface = parent.objectNode();
        iface.put(BASE_URL_FIELD, baseUrl);
        return iface;
    }

    private static boolean isTextual(JsonNode node) {
        return node != null && node.isTextual() && !node.asText().isBlank();
    }
}
