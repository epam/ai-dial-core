package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.config.UpstreamInterface;
import com.epam.aidial.core.config.annotation.EncryptedField;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards {@link ConfigEntityRedactor}: the entities that it projects are walked as the default mapper serializes them (so a {@code WRITE_ONLY}
 * property is not in the walk), and a secret that the projection would let out fails here, with its path.
 *
 * <p>A failure means a field that the projection does not cover: mark it {@code @EncryptedField} and
 * {@code @JsonProperty(access = WRITE_ONLY)}, or extend {@link ConfigEntityRedactor}, and then update the lists below.
 */
public class ConfigEntityRedactorCoverageTest {

    private static final String CONFIG_PACKAGE = "com.epam.aidial.core.config.";

    private static final List<Class<?>> ENTITIES = List.of(Application.class, Model.class, Interceptor.class, ToolSet.class);

    /**
     * The only places that hold a {@link ResourceAuthSettings}, which the redact methods of {@link ConfigEntityRedactor} clean; {@code *} is a map
     * value or a list element.
     */
    private static final Set<String> REDACTED_AUTH_SETTINGS = Set.of("ToolSet/auth_settings", "Application/external_services/*/auth_settings");

    private static final Set<String> REMOVED_FROM_AUTH_SETTINGS = Set.of(ResourceAuthSettings.CLIENT_SECRET_FIELD,
            ResourceAuthSettings.CODE_VERIFIER_FIELD, ResourceAuthSettings.CLIENT_SECRET_HINT_FIELD);

    private static final List<String> SECRET_WORDS = List.of("secret", "password", "credential", "apikey", "api_key", "token");

    /** Text properties whose name looks secret and which hold none, by {@code Owner.javaField}, with the reason. */
    private static final Map<String, String> NOT_SECRETS = Map.of(
            "Features.tokenizeEndpoint", "a path of the tokenize operation of the deployment, not a credential",
            "Mapping.tokenize", "a path of the tokenize operation of the application function, not a credential",
            "Model.tokenizerModel", "the name of the model that counts the tokens",
            "ResourceAuthSettings.tokenEndpoint", "the URL of the OAuth token endpoint",
            "ResourceAuthSettings.tokenEndpointAuthMethod", "the name of the client authentication method",
            "ResourceAuthSettings.apiKeyHeader", "the name of a header, not the key that it carries");

    private record Reached(String path, Class<?> owner, BeanPropertyDefinition property, Class<?> valueType) {
    }

    private static List<Reached> reachedFrom(Class<?> entity) {
        List<Reached> reached = new ArrayList<>();
        walk(ProxyUtil.MAPPER.constructType(entity), entity.getSimpleName(), new HashSet<>(), reached);
        return reached;
    }

    private static void walk(JavaType type, String path, Set<Class<?>> visiting, List<Reached> reached) {
        Class<?> raw = type.getRawClass();
        if (!raw.getName().startsWith(CONFIG_PACKAGE) || !visiting.add(raw)) {
            return;
        }
        try {
            for (BeanPropertyDefinition property : ProxyUtil.MAPPER.getSerializationConfig().introspect(type).findProperties()) {
                if (!property.couldSerialize()) {
                    // a WRITE_ONLY property stays in the list without a getter and a field, and is never written
                    continue;
                }
                String here = path + "/" + property.getName();
                JavaType content = property.getPrimaryType();
                while (content.isMapLikeType() || content.isCollectionLikeType() || content.isArrayType()) {
                    content = content.getContentType();
                    here += "/*";
                }
                reached.add(new Reached(here, raw, property, content.getRawClass()));
                walk(content, here, visiting, reached);
            }
        } finally {
            visiting.remove(raw);
        }
    }

    private static List<Reached> reachedFromAllEntities() {
        List<Reached> reached = new ArrayList<>();
        ENTITIES.forEach(entity -> reached.addAll(reachedFrom(entity)));
        return reached;
    }

    private static boolean isText(Class<?> type) {
        return type == String.class || type == Object.class || JsonNode.class.isAssignableFrom(type);
    }

    private static boolean looksSecret(BeanPropertyDefinition property) {
        String names = (property.getName() + " " + property.getInternalName()).toLowerCase(Locale.ROOT);
        return SECRET_WORDS.stream().anyMatch(names::contains);
    }

    @Test
    void testNoEncryptedFieldIsEmittedByTheDefaultMapper() {
        List<String> failures = new ArrayList<>();
        for (Reached found : reachedFromAllEntities()) {
            if (found.property().getField() != null && found.property().getField().hasAnnotation(EncryptedField.class)) {
                failures.add(found.path() + " is @EncryptedField but the default mapper writes it: add"
                        + " @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) to the field");
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    void testResourceAuthSettingsIsReachableOnlyWhereTheProjectionRedactsIt() {
        List<String> failures = new ArrayList<>();
        Set<String> found = new HashSet<>();
        for (Reached reached : reachedFromAllEntities()) {
            if (reached.valueType() == ResourceAuthSettings.class) {
                found.add(reached.path());
                if (!REDACTED_AUTH_SETTINGS.contains(reached.path())) {
                    failures.add(reached.path() + " holds ResourceAuthSettings, whose client_secret and code_verifier the projection does not"
                            + " remove there: extend ConfigEntityRedactor to redact it, then add the path to REDACTED_AUTH_SETTINGS");
                }
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
        assertEquals(REDACTED_AUTH_SETTINGS, found, "the redacted places are not all reached, update REDACTED_AUTH_SETTINGS");
    }

    @Test
    void testEveryTextPropertyThatLooksSecretIsKnownNotToLeak() {
        List<String> failures = new ArrayList<>();
        for (Reached reached : reachedFromAllEntities()) {
            BeanPropertyDefinition property = reached.property();
            if (!isText(reached.valueType()) || !looksSecret(property)) {
                continue;
            }
            boolean isRemoved = reached.owner() == ResourceAuthSettings.class && REMOVED_FROM_AUTH_SETTINGS.contains(property.getName());
            boolean isKnown = NOT_SECRETS.containsKey(reached.owner().getSimpleName() + "." + property.getInternalName());
            if (!isRemoved && !isKnown) {
                failures.add(reached.path() + " looks like a secret and the default mapper writes it: mark the field @EncryptedField and"
                        + " @JsonProperty(access = WRITE_ONLY), or extend ConfigEntityRedactor to remove it, or, if it holds no secret, add"
                        + " " + reached.owner().getSimpleName() + "." + property.getInternalName() + " to NOT_SECRETS with the reason");
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    void testTheWalkReachesTheKnownSecrets() {
        Set<Class<?>> types = new HashSet<>();
        reachedFromAllEntities().forEach(reached -> types.add(reached.valueType()));
        assertTrue(types.containsAll(List.of(Upstream.class, UpstreamInterface.class, ResourceAuthSettings.class)), types.toString());
        BeanPropertyDefinition key = ProxyUtil.MAPPER.getSerializationConfig().introspect(ProxyUtil.MAPPER.constructType(Upstream.class))
                .findProperties().stream().filter(property -> "key".equals(property.getName())).findFirst().orElseThrow();
        assertFalse(key.couldSerialize(), "the key of an upstream is written by the default mapper");
    }
}
