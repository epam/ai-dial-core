package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.metaschemas.MetaSchemaHolder;
import com.epam.aidial.core.server.config.SecretFieldProcessor;
import com.epam.aidial.core.server.data.ExportResourceIdentity;
import com.epam.aidial.core.server.service.ExportArchive.BodyRedactor;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Turns the stored JSON of an exported resource into the bytes of its archive entry, under a fail-closed policy. It works on the
 * stored JSON and never on a decrypted or in-memory object, and it fails by throwing: a body that cannot be redacted is not written,
 * and nothing falls back to the stored body.
 *
 * <p>The policy is a list of named fields. It does not read the values inside them, and it does not read any other value.
 * {@code application_properties}, {@code function.env} and {@code default_headers} have no rule, so they are exported as stored.
 *
 * <p><b>Removed, with everything inside them:</b>
 * <ul>
 *   <li>every {@link com.epam.aidial.core.config.annotation.EncryptedField} value, found by the existing
 *   {@link SecretFieldProcessor#stripEncryptedFields};</li>
 *   <li>the {@code client_secret}, {@code code_verifier} and {@code client_secret_hint} of the {@code auth_settings} of a ToolSet and of
 *   every external service (the three fields that {@link ResourceAuthSettings#removeSecretFields} removes, as does the config API);</li>
 *   <li>{@code dial:key}, {@code key}, {@code dial:secretExtraData} and {@code secretExtraData} at
 *   {@code dial:applicationTypeRoutes[*].dial:upstreams[*]} of an application type schema and in the interfaces of such an upstream: the
 *   spellings of the two {@code @EncryptedField} properties of {@code Upstream} ({@code key}, {@code secretExtraData}), which a schema
 *   document holds without an entity class.</li>
 * </ul>
 * A removed value is not replaced by a marker, and neither the technical manifest nor the registry says that anything was removed.
 *
 * <p><b>Exported exactly as stored:</b> a Prompt. Its bytes are the stored bytes, and no rule below applies to it.
 *
 * <p><b>Refused:</b> a body that is not valid JSON or not a JSON object, and a type that has no policy. A refusal fails the export of the
 * whole package before the first byte, and the exception holds no value: only the resource and the rule. {@link ExportClosureWalker}
 * logs it once, where it reads the resource, not here.
 *
 * <p><b>Not inspected for secrets, and not claimed to be safe:</b> every other value. A URL is not inspected for credentials and is not changed by
 * redaction (URL-form resource references are parsed elsewhere, only to resolve resources), so a credential in its user
 * information, path, query or fragment is exported; free text is exported; so are {@code defaults}, {@code responses_defaults},
 * {@code catalog_properties} and {@code override_paths}, and a string that merely starts with {@code ENC[}. There is no second check for an
 * annotated field that the removal missed: the completeness of {@link SecretFieldProcessor#stripEncryptedFields} and its tests is what
 * protects them.
 */
public final class ExportBodyRedactor implements BodyRedactor {

    /** TEMPORARY, FOR TESTING ONLY: when true the policy is skipped and every body is exported as stored, with its secrets. */
    private static final boolean REDACTION_DISABLED = true;

    /**
     * The place of a schema document that holds a secret: {@code dial:applicationTypeRoutes[*].dial:upstreams[*]}. The route is read
     * into {@code Route} and {@code Upstream}, which accept the spellings below for the same properties, so a schema stored in any of
     * them is treated alike.
     */
    private static final List<String> SCHEMA_UPSTREAMS = List.of("dial:upstreams", "upstreams");
    private static final List<String> SCHEMA_UPSTREAM_INTERFACES = List.of("dial:interfaces", "interfaces");
    private static final List<String> SCHEMA_UPSTREAM_SECRETS = List.of("dial:key", "key", "dial:secretExtraData", "secretExtraData");
    private static final String APPLICATION_TYPE_SCHEMA = "application-type-schema";
    private static final String EXTERNAL_SERVICES = "external_services";
    private static final String AUTH_SETTINGS = "auth_settings";

    /** The entity class whose annotations say which fields are encrypted; the schemas are plain JSON documents. */
    private static final Map<String, Class<?>> ENTITY_CLASSES = Map.of(
            "application", Application.class,
            "toolset", ToolSet.class,
            "model", Model.class,
            "interceptor", Interceptor.class);
    private static final Set<String> SCHEMA_TYPES = Set.of(APPLICATION_TYPE_SCHEMA, "catalog-schema");
    /** The types that are exported exactly as stored: a Prompt. */
    private static final Set<String> AS_STORED_TYPES = Set.of("prompts");

    @Override
    public byte[] redact(ExportResourceIdentity identity, String storedBody) {
        return redact(identity, storedBody, parse(identity, storedBody));
    }

    /**
     * A type that is exported as stored gets its stored bytes back, after the body has been checked to be a JSON object; any other type
     * gets the policy on a copy of the tree.
     */
    @Override
    public byte[] redact(ExportResourceIdentity identity, String storedBody, JsonNode parsedBody) {
        if (AS_STORED_TYPES.contains(identity.type())) {
            requireObject(identity, parsedBody);
            return storedBody.getBytes(StandardCharsets.UTF_8);
        }
        return redact(identity, parsedBody);
    }

    /**
     * The policy itself, on a body that is already parsed. The text entry is the parse followed by this method, so there is one
     * policy and no second copy of a rule. The tree is not changed: the policy works on a copy, and the caller may keep using the
     * tree, for example to read the schema id.
     */
    @Override
    public byte[] redact(ExportResourceIdentity identity, JsonNode storedTree) {
        ObjectNode stored = requireObject(identity, storedTree);
        ObjectNode redacted = AS_STORED_TYPES.contains(identity.type()) ? stored.deepCopy() : applyPolicy(identity, stored);
        try {
            return ProxyUtil.MAPPER.writeValueAsBytes(redacted);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The redacted body cannot be serialized");
        }
    }

    private static ObjectNode applyPolicy(ExportResourceIdentity identity, ObjectNode stored) {
        // TEMPORARY, FOR TESTING ONLY: redaction is switched off, so the export holds secrets. Remove this line to restore the policy.
        if (REDACTION_DISABLED) {
            return stored.deepCopy();
        }
        ObjectNode redacted = stripEncryptedFields(identity, stored);
        removeAuthSecrets(identity, redacted);
        removeSchemaSecrets(identity, redacted);
        return redacted;
    }

    private static JsonNode parse(ExportResourceIdentity identity, String storedBody) {
        try {
            return ProxyUtil.MAPPER.readTree(storedBody);
        } catch (JsonProcessingException e) {
            // no cause: the message of the parser can hold a piece of the body
            throw refusal(identity, "A stored body is not valid JSON");
        }
    }

    private static ObjectNode requireObject(ExportResourceIdentity identity, JsonNode body) {
        if (!(body instanceof ObjectNode object)) {
            throw refusal(identity, "A stored body is not a JSON object");
        }
        return object;
    }

    /**
     * A refusal carries its own diagnostic: the resource and the rule, never a value. The redactor does not log it;
     * {@link ExportClosureWalker} logs it once, where it reads the resource, so one refused resource is one log record.
     */
    private static IllegalStateException refusal(ExportResourceIdentity identity, String rule) {
        return new IllegalStateException(rule + " (resource=" + identity.key() + ")");
    }

    private static ObjectNode stripEncryptedFields(ExportResourceIdentity identity, ObjectNode stored) {
        Class<?> entityClass = ENTITY_CLASSES.get(identity.type());
        if (entityClass == null) {
            if (SCHEMA_TYPES.contains(identity.type())) {
                return stored.deepCopy();
            }
            throw refusal(identity, "No redaction exists for a resource of this type");
        }
        return SecretFieldProcessor.stripEncryptedFields(stored, entityClass);
    }

    /** The toolset's own {@code auth_settings} and those of every external service of a toolset or an Application. */
    private static void removeAuthSecrets(ExportResourceIdentity identity, ObjectNode body) {
        boolean isToolSet = "toolset".equals(identity.type());
        if (isToolSet) {
            ResourceAuthSettings.removeSecretFields(body.get(AUTH_SETTINGS));
        }
        if (isToolSet || "application".equals(identity.type())) {
            JsonNode services = body.get(EXTERNAL_SERVICES);
            if (services instanceof ObjectNode serviceObject) {
                serviceObject.fieldNames().forEachRemaining(name -> ResourceAuthSettings.removeSecretFields(serviceObject.get(name).get(AUTH_SETTINGS)));
            }
        }
    }

    /** The upstream keys of an application type schema, which is a plain JSON document without an entity class. */
    private static void removeSchemaSecrets(ExportResourceIdentity identity, ObjectNode body) {
        if (APPLICATION_TYPE_SCHEMA.equals(identity.type())) {
            removeSchemaRouteKeys(body);
        }
    }

    /** {@code dial:applicationTypeRoutes[*].dial:upstreams[*]}: the keys and extra data of an upstream, and those of its interfaces. */
    private static void removeSchemaRouteKeys(ObjectNode schema) {
        forEachChild(schema.get(MetaSchemaHolder.APPLICATION_TYPE_ROUTES), route -> {
            for (String upstreams : SCHEMA_UPSTREAMS) {
                forEachChild(route.get(upstreams), upstream -> {
                    removeUpstreamSecrets(upstream);
                    for (String interfaces : SCHEMA_UPSTREAM_INTERFACES) {
                        forEachChild(upstream.get(interfaces), ExportBodyRedactor::removeUpstreamSecrets);
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
