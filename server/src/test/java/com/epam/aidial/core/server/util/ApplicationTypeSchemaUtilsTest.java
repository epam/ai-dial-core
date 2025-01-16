package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.validation.ApplicationTypeSchemaValidationException;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ApplicationTypeSchemaUtilsTest {
    private Config config;
    private Application application;
    private ProxyContext ctx;
    private ResourceDescriptor resource;
    private AccessService accessService;

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
            + "\"required\": [\"clientFile\",\"serverFile\"]"
            + "}";

    private final Map<String, Object> clientProperties = Map.of("clientFile",
            "files/public/valid-file-path/valid-sub-path/valid%20file%20name1.ext");
    private final Map<String, Object> serverProperties = Map.of(
            "serverFile",
            "files/public/valid-file-path/valid-sub-path/valid%20file%20name2.ext");
    private final Map<String, Object> customProperties = new HashMap<>();

    ApplicationTypeSchemaUtilsTest() {
        customProperties.putAll(clientProperties);
        customProperties.putAll(serverProperties);
    }

    @BeforeEach
    void setUp() {
        config = mock(Config.class);
        application = new Application();
        ctx = mock(ProxyContext.class);
        resource = mock(ResourceDescriptor.class);
        Proxy proxy = mock(Proxy.class);
        accessService = mock(AccessService.class);
        when(ctx.getProxy()).thenReturn(proxy);
        when(proxy.getAccessService()).thenReturn(accessService);
        when(ctx.getConfig()).thenReturn(config);
    }

    @Test
    public void getCustomApplicationSchemaOrThrow_returnsSchema_whenSchemaIdExists() {
        URI schemaId = URI.create("schemaId");
        application.setApplicationTypeSchemaId(schemaId);
        when(config.getCustomApplicationSchema(schemaId)).thenReturn("schema");

        String result = ApplicationTypeSchemaUtils.getCustomApplicationSchemaOrThrow(config, application);

        Assertions.assertEquals("schema", result);
    }

    @Test
    public void getCustomApplicationSchemaOrThrow_throws_whenSchemaNotFound() {
        URI schemaId = URI.create("schemaId");
        application.setApplicationTypeSchemaId(schemaId);
        when(config.getCustomApplicationSchema(schemaId)).thenReturn(null);

        assertThrows(ApplicationTypeSchemaValidationException.class, () ->
                ApplicationTypeSchemaUtils.getCustomApplicationSchemaOrThrow(config, application));
    }

    @Test
    public void getCustomApplicationSchemaOrThrow_returnsNull_whenSchemaIdIsNull() {
        application.setApplicationTypeSchemaId(null);

        String result = ApplicationTypeSchemaUtils.getCustomApplicationSchemaOrThrow(config, application);

        Assertions.assertNull(result);
    }

    @Test
    public void getCustomServerProperties_returnsProperties_whenSchemaExists() {
        when(config.getCustomApplicationSchema(any())).thenReturn(schema);
        application.setApplicationProperties(customProperties);
        application.setApplicationTypeSchemaId(URI.create("schemaId"));

        Map<String, Object> result = ApplicationTypeSchemaUtils.getCustomServerProperties(config, application);

        Assertions.assertEquals(serverProperties, result);
    }

    @Test
    public void getCustomServerProperties_returnsEmptyMap_whenSchemaIsNull() {
        application.setApplicationTypeSchemaId(null);

        Map<String, Object> result = ApplicationTypeSchemaUtils.getCustomServerProperties(config, application);

        Assertions.assertEquals(Collections.emptyMap(), result);
    }

    @Test
    public void getCustomServerProperties_throws_whenSchemaNotFound() {
        application.setApplicationTypeSchemaId(URI.create("schemaId"));
        when(config.getCustomApplicationSchema(any())).thenReturn(null);

        Assertions.assertThrows(ApplicationTypeSchemaValidationException.class, () ->
                ApplicationTypeSchemaUtils.getCustomServerProperties(config, application));
    }


    @Test
    public void filterCustomClientProperties_returnsFilteredProperties_whenSchemaExists() {
        when(config.getCustomApplicationSchema(any())).thenReturn(schema);
        application.setApplicationTypeSchemaId(URI.create("schemaId"));
        application.setApplicationProperties(customProperties);

        Application result = ApplicationTypeSchemaUtils.filterCustomClientProperties(config, application);

        Assertions.assertNotSame(application, result);
        Assertions.assertEquals(clientProperties, result.getApplicationProperties());
    }

    @Test
    public void filterCustomClientProperties_returnsOriginalApplication_whenApplicationPropertiesIsNull() {
        when(config.getCustomApplicationSchema(any())).thenReturn(schema);
        application.setApplicationTypeSchemaId(URI.create("schemaId"));
        application.setApplicationProperties(null);

        Application result = ApplicationTypeSchemaUtils.filterCustomClientProperties(config, application);

        Assertions.assertSame(application, result);
    }

    @Test
    public void filterCustomClientProperties_returnsOriginalApplication_whenSchemaIsNull() {
        application.setApplicationTypeSchemaId(null);

        Application result = ApplicationTypeSchemaUtils.filterCustomClientProperties(config, application);

        Assertions.assertSame(application, result);
        Assertions.assertEquals(application, result);
    }

    @Test
    public void filterCustomClientPropertiesWhenNoWriteAccess_returnsFilteredProperties_whenNoWriteAccess() {
        URI schemUri = URI.create("https://mydial.epam.com/custom_application_schemas/specific_application_type");
        application.setApplicationTypeSchemaId(schemUri);
        application.setApplicationProperties(customProperties);
        when(config.getCustomApplicationSchema(eq(schemUri))).thenReturn(schema);
        when(accessService.hasWriteAccess(resource, ctx)).thenReturn(false);

        Application result = ApplicationTypeSchemaUtils.filterCustomClientPropertiesWhenNoWriteAccess(ctx, resource, application);

        Assertions.assertNotSame(application, result);
        Assertions.assertEquals(clientProperties, result.getApplicationProperties());
    }

    @Test
    public void filterCustomClientPropertiesWhenNoWriteAccess_returnsOriginalApplication_whenHasWriteAccess() {
        URI schemUri = URI.create("https://mydial.epam.com/custom_application_schemas/specific_application_type");
        when(accessService.hasWriteAccess(resource, ctx)).thenReturn(true);
        application.setApplicationTypeSchemaId(schemUri);
        application.setApplicationProperties(customProperties);
        when(config.getCustomApplicationSchema(eq(schemUri))).thenReturn(schema);

        Application result = ApplicationTypeSchemaUtils.filterCustomClientPropertiesWhenNoWriteAccess(ctx, resource, application);

        Assertions.assertSame(application, result);
        Assertions.assertEquals(customProperties, result.getApplicationProperties());
    }

    @Test
    public void modifyEndpointForCustomApplication_setsCustomEndpoint_whenSchemaExists() {
        application.setApplicationTypeSchemaId(URI.create("schemaId"));
        when(config.getCustomApplicationSchema(any())).thenReturn(schema);

        Application result = ApplicationTypeSchemaUtils.modifyEndpointForCustomApplication(config, application);

        Assertions.assertNotSame(application, result);
        Assertions.assertEquals("http://specific_application_service/opeani/v1/completion", result.getEndpoint());
    }

    @Test
    public void modifyEndpointForCustomApplication_throws_whenSchemaIsNull() {
        application.setApplicationTypeSchemaId(null);

        Assertions.assertThrows(ApplicationTypeSchemaProcessingException.class,
                () -> ApplicationTypeSchemaUtils.modifyEndpointForCustomApplication(config, application));
    }

    @Test
    public void modifyEndpointForCustomApplication_throws_whenEndpointNotFound() {
        String schemaWithoutEndpoint = "{"
                + "\"$schema\": \"https://dial.epam.com/application_type_schemas/schema#\","
                + "\"$id\": \"https://mydial.epam.com/custom_application_schemas/specific_application_type\","
                + "\"properties\": {"
                + "  \"clientFile\": {"
                + "    \"type\": \"string\","
                + "    \"format\": \"dial-file-encoded\","
                + "    \"dial:meta\": {"
                + "      \"dial:propertyKind\": \"client\","
                + "      \"dial:propertyOrder\": 1"
                + "    }"
                + "  }"
                + "},"
                + "\"required\": [\"clientFile\"]"
                + "}";
        application.setApplicationTypeSchemaId(URI.create("schemaId"));
        when(config.getCustomApplicationSchema(any())).thenReturn(schemaWithoutEndpoint);

        Assertions.assertThrows(ApplicationTypeSchemaProcessingException.class, () ->
                ApplicationTypeSchemaUtils.modifyEndpointForCustomApplication(config, application));
    }

    @Test
    public void replaceCustomAppFiles_replacesLinksInCustomProperties() {
        Map<String, Object> customProperties = new HashMap<>();
        customProperties.put("clientFile", "oldLink1");
        customProperties.put("serverFile", "oldLink2");

        application.setApplicationTypeSchemaId(URI.create("schemaId"));
        application.setApplicationProperties(customProperties);


        Map<String, String> replacementLinks = new HashMap<>();
        replacementLinks.put("oldLink1", "newLink1");
        replacementLinks.put("oldLink2", "newLink2");

        ApplicationTypeSchemaUtils.replaceCustomAppFiles(application, replacementLinks);

        Map<String, Object> expectedProperties = new HashMap<>();
        expectedProperties.put("clientFile", "newLink1");
        expectedProperties.put("serverFile", "newLink2");

        Assertions.assertEquals(expectedProperties, application.getApplicationProperties());
    }

    @Test
    public void replaceCustomAppFiles_doesNothing_whenSchemaIdIsNull() {
        Map<String, Object> customProperties = new HashMap<>();
        customProperties.put("clientFile", "oldLink1");
        customProperties.put("serverFile", "oldLink2");

        application.setApplicationTypeSchemaId(null);
        application.setApplicationProperties(customProperties);

        Map<String, String> replacementLinks = new HashMap<>();
        replacementLinks.put("oldLink1", "newLink1");
        replacementLinks.put("oldLink2", "newLink2");

        ApplicationTypeSchemaUtils.replaceCustomAppFiles(application, replacementLinks);

        Assertions.assertEquals(customProperties, application.getApplicationProperties());
    }

    @Test
    public void replaceCustomAppFiles_replacesLinksInNestedJsonNode() {
        Map<String, Object> serverProps = new HashMap<>();
        serverProps.put("serverFiles", List.of("oldLink1", "oldLink2"));
        Map<String, Object> customProperties = new HashMap<>();
        customProperties.put("clientFile", "oldLink1");
        customProperties.put("serverProps", serverProps);

        application.setApplicationTypeSchemaId(URI.create("schemaId"));
        application.setApplicationProperties(customProperties);

        Map<String, String> replacementLinks = new HashMap<>();
        replacementLinks.put("oldLink1", "newLink1");
        replacementLinks.put("oldLink2", "newLink2");

        ApplicationTypeSchemaUtils.replaceCustomAppFiles(application, replacementLinks);

        Map<String, Object> expectedServerProps = new HashMap<>();
        expectedServerProps.put("serverFiles", List.of("newLink1", "newLink2"));
        Map<String, Object> expectedProperties = new HashMap<>();
        expectedProperties.put("clientFile", "newLink1");
        expectedProperties.put("serverProps", expectedServerProps);

        Assertions.assertEquals(expectedProperties, application.getApplicationProperties());
    }


    @Test
    public void getFiles_returnsListOfFiles_whenSchemaExists() {
        application.setApplicationTypeSchemaId(URI.create("schemaId"));
        application.setApplicationProperties(customProperties);
        when(config.getCustomApplicationSchema(any())).thenReturn(schema);

        EncryptionService encryptionService = mock(EncryptionService.class);
        ResourceService resourceService = mock(ResourceService.class);

        when(resourceService.hasResource(any())).thenReturn(true);

        List<ResourceDescriptor> result = ApplicationTypeSchemaUtils.getFiles(config, application, encryptionService, resourceService);

        Assertions.assertEquals(2, result.size());
    }

    @Test
    public void getFiles_returnsEmptyList_whenSchemaIsNull() {
        application.setApplicationTypeSchemaId(null);

        EncryptionService encryptionService = mock(EncryptionService.class);
        ResourceService resourceService = mock(ResourceService.class);

        List<ResourceDescriptor> result = ApplicationTypeSchemaUtils.getFiles(config, application, encryptionService, resourceService);

        Assertions.assertTrue(result.isEmpty());
    }

    @Test
    public void getFiles_throwsException_whenResourceNotFound() {
        application.setApplicationTypeSchemaId(URI.create("schemaId"));
        application.setApplicationProperties(customProperties);
        when(config.getCustomApplicationSchema(any())).thenReturn(schema);

        EncryptionService encryptionService = mock(EncryptionService.class);
        ResourceService resourceService = mock(ResourceService.class);

        when(resourceService.hasResource(any())).thenReturn(false);

        Assertions.assertThrows(ApplicationTypeSchemaValidationException.class, () ->
                ApplicationTypeSchemaUtils.getFiles(config, application, encryptionService, resourceService));
    }
}
