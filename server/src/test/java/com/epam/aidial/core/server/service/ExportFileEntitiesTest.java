package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.server.data.ExportResourceIdentity;
import com.epam.aidial.core.server.service.config.ConfigEntityCodec;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The representation of an entity of the file configuration: what
 * {@link ExportFileEntities#readTree} gives for a Model, an Interceptor, a ToolSet, an Application and the two kinds of schema loses no
 * configured field, holds no value of a server-owned field, and is what the redactor then turns into
 * the bytes of the entry.
 */
public class ExportFileEntitiesTest {

    private static final String SCHEMA_ID = "https://dial.example/schemas/s";
    private static final String CATALOG_ID = "https://dial.example/catalog/c";
    private static final String MODEL_SECRET = "MODEL-SECRET-VALUE";
    private static final String TOOLSET_SECRET = "TOOLSET-SECRET-VALUE";

    private static final String CONFIG = """
            {
              "models": {
                "m-camel": {"type": "chat", "displayName": "M", "overrideName": "x", "endpoint": "http://m.invalid/v1", "description": null,
                  "upstreams": [{"endpoint": "http://u.invalid", "key": "%s", "weight": 2, "tier": 1}],
                  "limits": {"maxTotalTokens": 1000}, "pricing": {"unit": "token", "prompt": "0.1", "completion": "0.2"}},
                "m-snake": {"type": "chat", "display_name": "M", "override_name": "x", "endpoint": "http://m.invalid/v1",
                  "upstreams": [{"endpoint": "http://u.invalid", "key": "%s", "weight": 2, "tier": 1}],
                  "limits": {"maxTotalTokens": 1000}, "pricing": {"unit": "token", "prompt": "0.1", "completion": "0.2"}}
              },
              "interceptors": {"i1": {"endpoint": "http://i.invalid", "description": "d"}},
              "toolsets": {"t1": {"endpoint": "http://t.invalid/mcp", "transport": "HTTP", "display_name": "T", "allowed_tools": ["a", "b"],
                "auth_settings": {"authentication_type": "OAUTH", "client_id": "c", "client_secret": "%s", "token_endpoint": "https://a.invalid/t"}}},
              "applications": {
                "a-snake": {"display_name": "A", "endpoint": "http://a.invalid/chat", "dependencies": ["m-camel"], "interceptors": ["i1"],
                  "forward_auth_token": true, "user_roles": ["r"], "max_retry_attempts": 2},
                "a-camel": {"displayName": "A", "endpoint": "http://a.invalid/chat", "dependencies": ["m-camel"], "interceptors": ["i1"],
                  "forwardAuthToken": true, "userRoles": ["r"], "maxRetryAttempts": 2}
              },
              "applicationTypeSchemas": [{"$id": "%s", "$schema": "https://dial.epam.com/application_type_schemas/schema#",
                "dial:applicationTypeId": "s", "dial:applicationTypeEditorUrl": "http://e.invalid", "dial:applicationTypeDisplayName": "S",
                "dial:applicationTypeCompletionEndpoint": "http://c.invalid", "type": "object", "properties": {}}],
              "catalogSchemas": [{"$id": "%s", "$schema": "https://dial.epam.com/catalog_schemas/schema#", "type": "object", "properties": {}}]
            }
            """.formatted(MODEL_SECRET, MODEL_SECRET, TOOLSET_SECRET, SCHEMA_ID, CATALOG_ID);

    private static final List<String> SERVER_OWNED = List.of("author", "createdAt", "created_at", "updatedAt", "updated_at", "reference", "invalid");

    private final Config config = load();
    private final ExportBodyRedactor redactor = new ExportBodyRedactor();

    private static Config load() {
        try {
            return ProxyUtil.MAPPER.convertValue(ProxyUtil.MAPPER.readTree(CONFIG), Config.class);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ResourceDescriptor descriptor(ResourceTypes type, String name) {
        return ExportFileEntities.descriptorOf(type, name);
    }

    private JsonNode tree(ResourceTypes type, String name) {
        JsonNode tree = ExportFileEntities.readTree(config, descriptor(type, name));
        assertNotNull(tree, type + " " + name);
        return tree;
    }

    private <T> void assertRoundTrip(JsonNode tree, Class<T> cls) {
        T parsed = ConfigEntityCodec.treeToEntity(tree, cls);
        assertEquals(tree, ConfigEntityCodec.BLOB_MAPPER.valueToTree(parsed), cls.getSimpleName() + ": the representation parses into an entity that gives it again");
    }

    private void assertNoServerOwnedValue(JsonNode tree, String what) {
        for (String field : SERVER_OWNED) {
            assertTrue(tree.path(field).isMissingNode() || tree.path(field).isNull(), what + ": " + field + " holds a value");
        }
    }

    private void assertRedactsToTheEntry(ResourceTypes type, String name, ExportResourceIdentity identity) {
        JsonNode tree = tree(type, name);
        byte[] bytes = redactor.redact(identity, tree);
        JsonNode entry = readBack(bytes);
        assertTrue(entry.isObject(), "the redacted bytes of the tree are the JSON entry that is written");
        assertEquals(tree.get("endpoint"), entry.get("endpoint"), "a field that is not a secret is written as it is configured");
        entry.fieldNames().forEachRemaining(field -> assertTrue(tree.has(field), "the entry holds a field that the representation does not: " + field));
    }

    private static JsonNode readBack(byte[] bytes) {
        try {
            return ConfigEntityCodec.BLOB_MAPPER.readTree(bytes);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void testAliasesAndAnExplicitNullDoNotChangeTheRepresentation() {
        JsonNode camel = tree(ResourceTypes.MODEL, "m-camel");
        JsonNode snake = tree(ResourceTypes.MODEL, "m-snake");
        assertEquals(camel, snake, "the snake case aliases and the camel case names give one tree; an explicit null is the default");

        JsonNode applicationSnake = tree(ResourceTypes.APPLICATION, "a-snake");
        JsonNode applicationCamel = tree(ResourceTypes.APPLICATION, "a-camel");
        assertEquals(applicationSnake, applicationCamel);
    }

    /** The type, the name, the class and the configured values that the representation must hold, by JSON pointer. */
    static Stream<Arguments> entities() {
        return Stream.of(
                Arguments.of(ResourceTypes.MODEL, "m-camel", Model.class, Map.ofEntries(
                        Map.entry("/endpoint", "http://m.invalid/v1"),
                        Map.entry("/displayName", "M"),
                        Map.entry("/overrideName", "x"),
                        Map.entry("/type", "CHAT"),
                        Map.entry("/limits/maxTotalTokens", "1000"),
                        Map.entry("/pricing/completion", "0.2"),
                        Map.entry("/upstreams/0/endpoint", "http://u.invalid"),
                        Map.entry("/upstreams/0/weight", "2"),
                        Map.entry("/upstreams/0/tier", "1"))),
                Arguments.of(ResourceTypes.INTERCEPTOR, "i1", Interceptor.class, Map.of(
                        "/endpoint", "http://i.invalid",
                        "/description", "d")),
                Arguments.of(ResourceTypes.TOOL_SET, "t1", ToolSet.class, Map.of(
                        "/endpoint", "http://t.invalid/mcp",
                        "/transport", "HTTP",
                        "/display_name", "T",
                        "/allowed_tools/0", "a",
                        "/allowed_tools/1", "b",
                        "/auth_settings/client_id", "c",
                        "/auth_settings/token_endpoint", "https://a.invalid/t")),
                Arguments.of(ResourceTypes.APPLICATION, "a-snake", Application.class, Map.of(
                        "/display_name", "A",
                        "/endpoint", "http://a.invalid/chat",
                        "/dependencies/0", "m-camel",
                        "/interceptors/0", "i1",
                        "/forward_auth_token", "true",
                        "/user_roles/0", "r",
                        "/max_retry_attempts", "2")));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("entities")
    void testEntityLosesNoConfiguredField(ResourceTypes type, String name, Class<?> entityClass, Map<String, String> configured) {
        JsonNode tree = tree(type, name);

        configured.forEach((pointer, expected) -> assertEquals(expected, tree.at(pointer).asText(), type + " " + pointer));
        assertRoundTrip(tree, entityClass);
        assertNoServerOwnedValue(tree, type.name());
        assertRedactsToTheEntry(type, name, ExportResourceIdentity.ofFileEntity(type, name, null));
    }

    @Test
    void testSchemasAreTheParseOfTheirConfiguredTextAndAreFoundByTheDigestOfTheirId() throws IOException {
        for (Object[] schema : new Object[][] {{ResourceTypes.APP_TYPE_SCHEMA, SCHEMA_ID, config.getApplicationTypeSchemas().get(SCHEMA_ID)},
                {ResourceTypes.CATALOG_SCHEMA, CATALOG_ID, config.getCatalogSchemas().get(CATALOG_ID)}}) {
            ResourceTypes type = (ResourceTypes) schema[0];
            String id = (String) schema[1];
            ResourceDescriptor descriptor = ExportFileEntities.descriptorOf(type, ExportResourceIdentity.schemaDigest(id));

            JsonNode tree = ExportFileEntities.readTree(config, descriptor);

            assertEquals(ProxyUtil.MAPPER.readTree((String) schema[2]), tree, type + ": the configured document");
            assertEquals(id, tree.get("$id").asText());
            ExportResourceIdentity identity = ExportResourceIdentity.ofFileEntity(type, descriptor.getName(), id);
            assertEquals(identity.key(), ExportResourceIdentity.ofDescriptor(descriptor, id).key());
        }
    }

    @Test
    void testAnEntityThatTheFileConfigurationDoesNotHoldHasNoRepresentation() {
        assertNull(ExportFileEntities.readTree(config, descriptor(ResourceTypes.MODEL, "no-such")));
        assertNull(ExportFileEntities.readTree(config, ExportFileEntities.descriptorOf(ResourceTypes.APP_TYPE_SCHEMA, ExportResourceIdentity.schemaDigest("urn:none"))));
        assertNull(ExportFileEntities.readTree(config, descriptor(ResourceTypes.PROMPT, "p")), "a type without a file form");
        assertNull(ExportFileEntities.readTree(null, descriptor(ResourceTypes.MODEL, "m-camel")), "no file configuration");
    }

}
