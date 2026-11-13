package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An entity of the file configuration has no blob: {@link ExportFileEntities#findDeployment} gives the live entity,
 * {@link ExportFileEntities#findSchemaText} the configured text of a schema, found by the digest of its id.
 */
public class ExportFileEntitiesTest {

    private static final String SCHEMA_ID = "https://dial.example/schemas/s";
    private static final String CATALOG_ID = "https://dial.example/catalog/c";

    private static final String CONFIG = """
            {
              "models": {"m1": {"type": "chat", "displayName": "M", "endpoint": "http://m.invalid/v1"}},
              "interceptors": {"i1": {"endpoint": "http://i.invalid", "description": "d"}},
              "toolsets": {"t1": {"endpoint": "http://t.invalid/mcp", "transport": "HTTP", "display_name": "T"}},
              "applications": {"a1": {"display_name": "A", "endpoint": "http://a.invalid/chat", "dependencies": ["m1"]}},
              "applicationTypeSchemas": [{"$id": "%s", "$schema": "https://dial.epam.com/application_type_schemas/schema#",
                "dial:applicationTypeId": "s", "dial:applicationTypeEditorUrl": "http://e.invalid", "dial:applicationTypeDisplayName": "S",
                "dial:applicationTypeCompletionEndpoint": "http://c.invalid", "type": "object", "properties": {}}],
              "catalogSchemas": [{"$id": "%s", "$schema": "https://dial.epam.com/catalog_schemas/schema#", "type": "object", "properties": {}}]
            }
            """.formatted(SCHEMA_ID, CATALOG_ID);

    private final Config config = load();

    private static Config load() {
        try {
            return ProxyUtil.MAPPER.convertValue(ProxyUtil.MAPPER.readTree(CONFIG), Config.class);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ResourceDescriptor descriptor(ResourceTypes type, String name) {
        return ExportFileEntities.newDescriptor(type, name);
    }

    @Test
    void testDeploymentOfIsTheLiveEntityOfItsKind() {
        assertSame(config.getModels().get("m1"), assertInstanceOf(Model.class, ExportFileEntities.findDeployment(config, descriptor(ResourceTypes.MODEL, "m1"))));
        assertSame(config.getInterceptors().get("i1"),
                assertInstanceOf(Interceptor.class, ExportFileEntities.findDeployment(config, descriptor(ResourceTypes.INTERCEPTOR, "i1"))));
        assertSame(config.getToolsets().get("t1"),
                assertInstanceOf(ToolSet.class, ExportFileEntities.findDeployment(config, descriptor(ResourceTypes.TOOL_SET, "t1"))));
        Application application = assertInstanceOf(Application.class,
                ExportFileEntities.findDeployment(config, descriptor(ResourceTypes.APPLICATION, "a1")));
        assertSame(config.getApplications().get("a1"), application);
        assertEquals("m1", application.getDependencies().get(0));
    }

    @Test
    void testDeploymentOfIsNullForWhatTheFileConfigurationDoesNotHold() {
        assertNull(ExportFileEntities.findDeployment(config, descriptor(ResourceTypes.MODEL, "no-such")));
        assertNull(ExportFileEntities.findDeployment(config, descriptor(ResourceTypes.APPLICATION, "m1")), "a name of another kind");
        assertNull(ExportFileEntities.findDeployment(config, descriptor(ResourceTypes.PROMPT, "p")), "a type without a file form");
        assertNull(ExportFileEntities.findDeployment(config, descriptor(ResourceTypes.APP_TYPE_SCHEMA, "s")), "a schema is not a deployment");
        assertNull(ExportFileEntities.findDeployment(null, descriptor(ResourceTypes.MODEL, "m1")), "no file configuration");
    }

    @Test
    void testSchemasAreFoundByTheDigestOfTheirIdAndAreTheConfiguredText() throws IOException {
        for (Object[] schema : new Object[][] {{ResourceTypes.APP_TYPE_SCHEMA, SCHEMA_ID, config.getApplicationTypeSchemas().get(SCHEMA_ID)},
                {ResourceTypes.CATALOG_SCHEMA, CATALOG_ID, config.getCatalogSchemas().get(CATALOG_ID)}}) {
            ResourceTypes type = (ResourceTypes) schema[0];
            String id = (String) schema[1];
            ResourceDescriptor descriptor = ExportFileEntities.newSchemaDescriptor(type, id);

            String text = ExportFileEntities.findSchemaText(config, descriptor);

            assertEquals(schema[2], text, type + ": the configured document");
            JsonNode tree = ProxyUtil.MAPPER.readTree(text);
            assertEquals(id, tree.get("$id").asText());
            assertEquals(ExportFileEntities.computeSchemaDigest(id), descriptor.getName());
            assertEquals(ExportResourceNames.buildKey(descriptor),
                    ExportResourceNames.resolveTypeName(type) + ":file:" + ExportFileEntities.computeSchemaDigest(id));
        }
    }

    @Test
    void testSchemaDigestIsTheFirst32HexCharactersOfTheDigestOfTheId() {
        String id = "https://dial.example/schemas/a%20b?x=1#y";
        String digest = ExportFileEntities.computeSchemaDigest(id);

        assertTrue(digest.matches("[0-9a-f]{32}"), digest);
        assertEquals(digest, ExportFileEntities.computeSchemaDigest(id), "the digest follows the $id");
        assertNotEquals(digest, ExportFileEntities.computeSchemaDigest(id + "2"));
        assertEquals(digest, ExportFileEntities.newSchemaDescriptor(ResourceTypes.APP_TYPE_SCHEMA, id).getName());
    }

    @Test
    void testSchemaTextIsNullForWhatTheFileConfigurationDoesNotHold() {
        assertNull(ExportFileEntities.findSchemaText(config, ExportFileEntities.newSchemaDescriptor(ResourceTypes.APP_TYPE_SCHEMA, "urn:none")));
        assertNull(ExportFileEntities.findSchemaText(config, ExportFileEntities.newSchemaDescriptor(ResourceTypes.CATALOG_SCHEMA, SCHEMA_ID)),
                "an application type schema is not a catalog schema");
        assertNull(ExportFileEntities.findSchemaText(null, ExportFileEntities.newSchemaDescriptor(ResourceTypes.APP_TYPE_SCHEMA, SCHEMA_ID)));
    }

    @Test
    void testOnlyTheFileLocationMakesFileEntity() {
        assertTrue(ExportFileEntities.isFile(descriptor(ResourceTypes.MODEL, "m1")));
        assertFalse(ExportFileEntities.isFile(ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.MODEL, "platform", "platform/", "m1")));
    }
}
