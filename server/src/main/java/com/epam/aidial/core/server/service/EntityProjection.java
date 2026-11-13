package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.metaschemas.MetaSchemaHolder;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.function.Consumer;

/**
 * The one projection of a config entity into the JSON that leaves the system, shared by the config API and the export.
 *
 * <p>{@link #project} drops every {@code @EncryptedField} value ({@code WRITE_ONLY}). The {@code auth_settings} secrets are not annotated,
 * because the services persist through the plain mapper and {@code WRITE_ONLY} would drop them from the blob as well; they are removed
 * from the projected tree, a fresh copy, instead.
 */
public final class EntityProjection {

    /**
     * The place of a schema document that holds a secret: {@code dial:applicationTypeRoutes[*].dial:upstreams[*]}. {@code Route} and
     * {@code Upstream} accept both spellings below, so a schema stored in either is treated alike.
     */
    private static final List<String> SCHEMA_UPSTREAMS = List.of("dial:upstreams", "upstreams");
    private static final List<String> SCHEMA_UPSTREAM_INTERFACES = List.of("dial:interfaces", "interfaces");
    private static final List<String> SCHEMA_UPSTREAM_SECRETS = List.of("dial:key", "key", "dial:secretExtraData", "secretExtraData");
    private static final String AUTH_SETTINGS = "auth_settings";
    private static final String EXTERNAL_SERVICES = "external_services";

    private EntityProjection() {
    }

    /**
     * @return a fresh tree of the entity, without any {@code @EncryptedField} value
     */
    public static ObjectNode project(Object entity) {
        return ProxyUtil.MAPPER.valueToTree(entity);
    }

    /**
     * Removes the {@code auth_settings} secrets of a ToolSet's projected tree.
     *
     * @param revealHint replaces the removed secret with its {@code client_secret_hint}; admins only
     */
    public static ObjectNode redactAuthSettingsSecrets(ObjectNode node, boolean revealHint) {
        redactSecretFields(node.get(AUTH_SETTINGS), revealHint);
        return node;
    }

    /**
     * Removes the {@code auth_settings} secrets of every external service of an Application's projected tree; {@code revealHint} as in
     * {@link #redactAuthSettingsSecrets}.
     */
    public static ObjectNode redactExternalServiceSecrets(ObjectNode node, boolean revealHint) {
        JsonNode externalServices = node.get(EXTERNAL_SERVICES);
        if (externalServices != null && externalServices.isObject()) {
            externalServices.forEach(service -> redactSecretFields(service.get(AUTH_SETTINGS), revealHint));
        }
        return node;
    }

    private static void redactSecretFields(JsonNode authSettings, boolean revealHint) {
        JsonNode clientSecret = ResourceAuthSettings.removeSecretFields(authSettings);
        // The hint is this method's to emit, never the projected node's (removeSecretFields already dropped it).
        if (revealHint && clientSecret != null && clientSecret.isTextual()) {
            String hint = ResourceAuthSettings.hintFor(clientSecret.textValue());
            if (hint != null) {
                ((ObjectNode) authSettings).put(ResourceAuthSettings.CLIENT_SECRET_HINT_FIELD, hint);
            }
        }
    }

    /**
     * Removes the keys and extra data of the upstreams of an application type schema, a plain JSON document without an entity class.
     * The tree is changed.
     */
    public static void removeSchemaUpstreamKeys(ObjectNode schema) {
        forEachChild(schema.get(MetaSchemaHolder.APPLICATION_TYPE_ROUTES), route -> {
            for (String upstreams : SCHEMA_UPSTREAMS) {
                forEachChild(route.get(upstreams), upstream -> {
                    removeUpstreamSecrets(upstream);
                    for (String interfaces : SCHEMA_UPSTREAM_INTERFACES) {
                        forEachChild(upstream.get(interfaces), EntityProjection::removeUpstreamSecrets);
                    }
                });
            }
        });
    }

    private static void removeUpstreamSecrets(JsonNode owner) {
        if (owner instanceof ObjectNode object) {
            object.remove(SCHEMA_UPSTREAM_SECRETS);
        }
    }

    /** {@code [*]}: every value of an object (the routes are keyed by name) and every element of an array. */
    private static void forEachChild(JsonNode container, Consumer<JsonNode> action) {
        if (container instanceof ObjectNode object) {
            object.fieldNames().forEachRemaining(name -> action.accept(object.get(name)));
        } else if (container instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                action.accept(array.get(i));
            }
        }
    }
}
