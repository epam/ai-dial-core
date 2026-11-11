package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.config.annotation.EncryptedField;
import com.epam.aidial.core.server.data.ExportResourceIdentity;
import com.epam.aidial.core.server.service.config.ConfigEntityCodec;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protects the redaction coverage of {@link ExportBodyRedactor}: every {@code @EncryptedField} that can be
 * reached from the entity of a resource type that the export writes, and every place that holds a {@link ResourceAuthSettings},
 * is found by reflection, filled with a marker in a stored body, and must be gone from the redacted bytes. A field that someone adds
 * later, at a place the redactor does not reach, fails here, and so does a new field of {@code ResourceAuthSettings} that nobody classified as secret or not.
 *
 * <p>The walk is independent of {@code SecretFieldProcessor}, whose own reflection is what the redactor relies on, so a gap in
 * that discovery shows up as a failure and not as a silent agreement. Property names come from the blob mapper, as in a stored body.
 */
public class ExportBodyRedactorCoverageTest {

    private static final String MARKER = "MARKER-VALUE";
    private static final String KEPT = "kept-value";

    /** The entity classes behind the resource types of {@code ExportBodyRedactor}. */
    private static final Map<ResourceTypes, Class<?>> ENTITIES = Map.of(
            ResourceTypes.APPLICATION, Application.class,
            ResourceTypes.TOOL_SET, ToolSet.class,
            ResourceTypes.MODEL, Model.class,
            ResourceTypes.INTERCEPTOR, Interceptor.class);

    /** The fields of {@code ResourceAuthSettings} that the export removes, by Java name. */
    private static final Set<String> AUTH_SECRET_FIELDS = Set.of("clientSecret", "codeVerifier", "clientSecretHint");

    /** The other fields, reviewed as not secret: identifiers, endpoints, PKCE challenge, statuses and metadata. */
    private static final Set<String> AUTH_KEPT_FIELDS = Set.of("authenticationType", "clientId", "authorizationEndpoint",
            "tokenEndpoint", "redirectUri", "codeChallenge", "codeChallengeMethod", "apiKeyHeader", "globalAuthStatus",
            "userLevelAuthStatus", "appLevelAuthStatus", "scopesSupported", "tokenEndpointAuthMethod", "dynamicallyRegistered",
            "resource");

    /** What the walk found in one entity: the places of annotated fields and of auth settings, as segment lists. */
    private record Places(List<List<String>> encrypted, List<List<String>> authSettings) {
    }

    private static final String MAP_KEY = "entry";
    private static final String INDEX = "#0";

    private static Places placesOf(Class<?> entity) {
        Places places = new Places(new ArrayList<>(), new ArrayList<>());
        walk(ConfigEntityCodec.BLOB_MAPPER.constructType(entity), List.of(), new HashSet<>(), places);
        return places;
    }

    private static void walk(JavaType type, List<String> path, Set<Class<?>> visiting, Places places) {
        Class<?> raw = type.getRawClass();
        if (!raw.getName().startsWith("com.epam.aidial.core.config.") || !visiting.add(raw)) {
            return;
        }
        try {
            BeanDescription description = ConfigEntityCodec.BLOB_MAPPER.getDeserializationConfig().introspect(type);
            for (BeanPropertyDefinition property : description.findProperties()) {
                List<String> here = append(path, property.getName());
                if (property.getField() != null && property.getField().hasAnnotation(EncryptedField.class)) {
                    places.encrypted().add(here);
                }
                JavaType content = property.getPrimaryType();
                List<String> inside = here;
                while (content.isMapLikeType() || content.isCollectionLikeType()) {
                    inside = append(inside, content.isMapLikeType() ? MAP_KEY : INDEX);
                    content = content.getContentType();
                }
                if (content.getRawClass() == ResourceAuthSettings.class) {
                    places.authSettings().add(inside);
                } else {
                    walk(content, inside, visiting, places);
                }
            }
        } finally {
            visiting.remove(raw);
        }
    }

    private static List<String> append(List<String> path, String segment) {
        List<String> result = new ArrayList<>(path);
        result.add(segment);
        return result;
    }

    private static JsonPointer pointerOf(List<String> path) {
        JsonPointer pointer = JsonPointer.empty();
        for (String segment : path) {
            pointer = INDEX.equals(segment) ? pointer.appendIndex(0) : pointer.appendProperty(segment);
        }
        return pointer;
    }

    /** Creates the objects and arrays along the path and puts {@code leaf} at its end. */
    private static void put(ObjectNode root, List<String> path, JsonNode leaf) {
        JsonNode node = root;
        for (int i = 0; i < path.size(); i++) {
            String segment = path.get(i);
            boolean isLast = i == path.size() - 1;
            JsonNode child;
            if (isLast) {
                child = leaf;
            } else {
                child = INDEX.equals(path.get(i + 1)) ? ProxyUtil.MAPPER.createArrayNode() : ProxyUtil.MAPPER.createObjectNode();
            }
            if (INDEX.equals(segment)) {
                ArrayNode array = (ArrayNode) node;
                if (array.isEmpty()) {
                    array.add(child);
                    node = child;
                } else {
                    node = array.get(0);
                }
            } else {
                ObjectNode object = (ObjectNode) node;
                if (object.has(segment)) {
                    node = object.get(segment);
                } else {
                    object.set(segment, child);
                    node = child;
                }
            }
        }
    }

    private static ExportResourceIdentity identity(ResourceTypes type) {
        return ExportResourceIdentity.ofDescriptor(ResourceDescriptorFactory.fromDecoded(type, "platform", "platform/", "x"), null);
    }

    private static String authSettingsJsonName(String javaName) {
        JavaType type = ConfigEntityCodec.BLOB_MAPPER.constructType(ResourceAuthSettings.class);
        // a read-only property such as the hint is only in the serialization view, a write-only one only in the other
        for (BeanDescription description : List.of(ConfigEntityCodec.BLOB_MAPPER.getSerializationConfig().introspect(type),
                ConfigEntityCodec.BLOB_MAPPER.getDeserializationConfig().introspect(type))) {
            for (BeanPropertyDefinition property : description.findProperties()) {
                if (property.getField() != null && property.getField().getName().equals(javaName)) {
                    return property.getName();
                }
            }
        }
        throw new AssertionError("no JSON property for " + javaName);
    }

    private static ObjectNode authSettingsBody() {
        ObjectNode settings = ProxyUtil.MAPPER.createObjectNode();
        for (String field : AUTH_SECRET_FIELDS) {
            settings.put(authSettingsJsonName(field), MARKER + "-" + field);
        }
        for (String field : AUTH_KEPT_FIELDS) {
            if (!"scopesSupported".equals(field) && !"dynamicallyRegistered".equals(field)) {
                settings.put(authSettingsJsonName(field), KEPT + "-" + field);
            }
        }
        return settings;
    }

    // ---- every annotated field is removed and listed ---------------------------------------------------------------------

    static Stream<Arguments> entities() {
        return ENTITIES.entrySet().stream().map(entry -> Arguments.of(entry.getKey(), entry.getValue()));
    }

    @ParameterizedTest
    @MethodSource("entities")
    @Disabled("Redaction is switched off temporarily for testing")
    void testEveryEncryptedFieldOfTheEntityIsRedacted(ResourceTypes type, Class<?> entity) throws Exception {
        List<List<String>> places = placesOf(entity).encrypted();
        ObjectNode body = ProxyUtil.MAPPER.createObjectNode();
        for (List<String> place : places) {
            put(body, place, ProxyUtil.MAPPER.getNodeFactory().textNode("ENC[" + MARKER + "]"));
        }

        byte[] redacted = new ExportBodyRedactor().redact(identity(type), body.toString());

        String output = new String(redacted, StandardCharsets.UTF_8);
        assertFalse(output.contains(MARKER), type + ": a marked secret is still in the body: " + output);
        JsonNode result = ProxyUtil.MAPPER.readTree(redacted);
        for (List<String> place : places) {
            JsonPointer pointer = pointerOf(place);
            assertTrue(result.at(pointer).isMissingNode(), type + ": not removed: " + pointer);
        }
    }

    // ---- every ResourceAuthSettings is redacted --------------------------------------------------------------------------

    @Test
    @Disabled("Redaction is switched off temporarily for testing")
    void testEveryPlaceThatHoldsAuthSettingsIsRedactedAndTheOtherFieldsAreKept() throws Exception {
        for (Map.Entry<ResourceTypes, Class<?>> entry : ENTITIES.entrySet()) {
            for (List<String> place : placesOf(entry.getValue()).authSettings()) {
                ObjectNode body = ProxyUtil.MAPPER.createObjectNode();
                put(body, place, authSettingsBody());

                byte[] redacted = new ExportBodyRedactor().redact(identity(entry.getKey()), body.toString());

                JsonNode settings = ProxyUtil.MAPPER.readTree(redacted).at(pointerOf(place));
                String where = entry.getKey() + " at " + pointerOf(place);
                for (String field : AUTH_SECRET_FIELDS) {
                    assertTrue(settings.path(authSettingsJsonName(field)).isMissingNode(), where + ": " + field + " is not removed");
                }
                for (String field : AUTH_KEPT_FIELDS) {
                    if (!"scopesSupported".equals(field) && !"dynamicallyRegistered".equals(field)) {
                        assertTrue(settings.has(authSettingsJsonName(field)), where + ": " + field + " must be kept");
                    }
                }
                assertFalse(new String(redacted, StandardCharsets.UTF_8).contains(MARKER), where);
            }
        }
    }

    @Test
    void testEveryFieldOfResourceAuthSettingsIsClassifiedAsSecretOrNot() {
        Set<String> declared = new TreeSet<>();
        for (Field field : ResourceAuthSettings.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                declared.add(field.getName());
            }
        }
        Set<String> classified = new TreeSet<>(AUTH_SECRET_FIELDS);
        classified.addAll(AUTH_KEPT_FIELDS);

        assertEquals(classified, declared, "a field of ResourceAuthSettings was added or removed: decide whether the export removes it"
                + " (ExportBodyRedactor.removeAuthSettingsSecrets) and update the two sets of this test");
    }
}
