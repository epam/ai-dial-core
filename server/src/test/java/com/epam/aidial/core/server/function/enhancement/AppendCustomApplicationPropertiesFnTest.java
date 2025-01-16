package com.epam.aidial.core.server.function.enhancement;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.validation.ApplicationTypeSchemaValidationException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AppendCustomApplicationPropertiesFnTest {

    @Mock
    private Proxy proxy;

    @Mock
    private ProxyContext context;

    @Mock
    private Config config;

    private Application application;

    private AppendApplicationPropertiesFn function;

    private final String schema = "{"
                                  + "\"$schema\": \"https://dial.epam.com/application_type_schemas/schema#\","
                                  + "\"$id\": \"https://mydial.epam.com/custom_application_schemas/specific_application_type\","
                                  + "\"dial:applicationTypeEditorUrl\": \"https://mydial.epam.com/specific_application_type_editor\","
                                  + "\"dial:applicationTypeDisplayName\": \"Specific Application Type\","
                                  + "\"dial:applicationTypeCompletionEndpoint\": \"http://specific_application_service/opeani/v1/completion\","
                                  + "\"properties\": {"
                                  + "  \"clientFile\": {"
                                  + "    \"type\": \"string\","
                                  + "    \"format\": \"dial-file-encoded\","
                                  + "    \"dial:meta\": {"
                                  + "      \"dial:propertyKind\": \"client\","
                                  + "      \"dial:propertyOrder\": 1"
                                  + "    },"
                                  + "    \"dial:file\" : true"
                                  + "  },"
                                  + "  \"serverFile\": {"
                                  + "    \"type\": \"string\","
                                  + "    \"format\": \"dial-file-encoded\","
                                  + "    \"dial:meta\": {"
                                  + "      \"dial:propertyKind\": \"server\","
                                  + "      \"dial:propertyOrder\": 2"
                                  + "    },"
                                  + "    \"dial:file\" : true"
                                  + "  }"
                                  + "},"
                                  + "\"required\": [\"clientFile\"]"
                                  + "}";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        function = new AppendApplicationPropertiesFn(proxy, context);
        application = new Application();
        when(context.getConfig()).thenReturn(config);
    }

    @Test
    void apply_throws_whenApplicationHasCustomSchemaIdAndNoCustomFieldsPassedAndApplicationPropertiesIsNull() {
        when(context.getDeployment()).thenReturn(application);
        application.setApplicationTypeSchemaId(URI.create("customSchemaId"));
        when(config.getCustomApplicationSchema(eq(URI.create("customSchemaId")))).thenReturn(schema);
        ObjectNode tree = ProxyUtil.MAPPER.createObjectNode();
        assertThrows(ApplicationTypeSchemaValidationException.class, () -> function.apply(tree));
    }

    @Test
    void apply_appendsCustomProperties_whenApplicationHasCustomSchemaIdAndNoCustomFieldsPassed() {
        String serverFile = "files/public/valid-file-path/valid-sub-path/valid%20file%20name2.ext";
        when(context.getDeployment()).thenReturn(application);
        application.setApplicationTypeSchemaId(URI.create("customSchemaId"));
        Map<String, Object> customProps = new HashMap<>();
        customProps.put("clientFile", "files/public/valid-file-path/valid-sub-path/valid%20file%20name1.ext");
        customProps.put("serverFile", serverFile);
        application.setApplicationProperties(customProps);
        when(config.getCustomApplicationSchema(eq(URI.create("customSchemaId")))).thenReturn(schema);
        ObjectNode tree = ProxyUtil.MAPPER.createObjectNode();
        boolean result = function.apply(tree);
        assertTrue(result);
        assertNotNull(tree.get("custom_fields"));
        assertEquals(serverFile,
                tree.get("custom_fields").get("application_properties").get("serverFile").asText());
        assertFalse(tree.get("custom_fields").get("application_properties").has("clientFile"));
    }

    @Test
    void apply_returnsFalse_whenDeploymentIsNotApplication() {
        Deployment deployment = mock(Deployment.class);
        when(context.getDeployment()).thenReturn(deployment);

        ObjectNode tree = ProxyUtil.MAPPER.createObjectNode();
        boolean result = function.apply(tree);

        assertFalse(result);
        assertNull(tree.get("custom_fields"));
    }

    @Test
    void apply_returnsFalse_whenApplicationHasNoCustomSchemaId() {
        when(context.getDeployment()).thenReturn(application);
        application.setApplicationTypeSchemaId(null);

        ObjectNode tree = ProxyUtil.MAPPER.createObjectNode();
        boolean result = function.apply(tree);

        assertFalse(result);
        assertNull(tree.get("custom_fields"));
    }

    @Test
    void apply_returnsTrue_whenCustomPropertiesAreEmptyAndApplicationHasCustomSchemaId() {
        when(context.getDeployment()).thenReturn(application);
        application.setApplicationTypeSchemaId(URI.create("customSchemaId"));
        Map<String, Object> customProps = new HashMap<>();
        customProps.put("clientFile", "files/public/valid-file-path/valid-sub-path/valid%20file%20name1.ext");
        application.setApplicationProperties(customProps);
        when(config.getCustomApplicationSchema(eq(URI.create("customSchemaId")))).thenReturn(schema);
        ObjectNode tree = ProxyUtil.MAPPER.createObjectNode();
        boolean result = function.apply(tree);
        assertTrue(result);
        assertNotNull(tree.get("custom_fields"));
    }

    @Test
    void apply_appendsCustomProperties_whenApplicationHasCustomSchemaIdAndCustomFieldsPassed() throws JsonProcessingException {
        String serverFile = "files/public/valid-file-path/valid-sub-path/valid%20file%20name2.ext";
        when(context.getDeployment()).thenReturn(application);
        application.setApplicationTypeSchemaId(URI.create("customSchemaId"));
        Map<String, Object> customProps = new HashMap<>();
        customProps.put("clientFile", "files/public/valid-file-path/valid-sub-path/valid%20file%20name1.ext");
        customProps.put("serverFile", serverFile);
        application.setApplicationProperties(customProps);
        when(config.getCustomApplicationSchema(eq(URI.create("customSchemaId")))).thenReturn(schema);
        ObjectNode tree = (ObjectNode) ProxyUtil.MAPPER.readTree("""
                {
                    "custom_fields": {
                        "foo": "bar"
                    }
                }
                """);
        boolean result = function.apply(tree);
        assertTrue(result);
        assertNotNull(tree.get("custom_fields"));
        assertEquals(serverFile,
                tree.get("custom_fields").get("application_properties").get("serverFile").asText());
        assertFalse(tree.get("custom_fields").get("application_properties").has("clientFile"));
        assertTrue(tree.get("custom_fields").has("foo"));
        assertEquals("bar",
                tree.get("custom_fields").get("foo").asText());

    }
}