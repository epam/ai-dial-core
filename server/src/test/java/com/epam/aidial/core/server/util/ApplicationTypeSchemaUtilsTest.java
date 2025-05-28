package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.validation.ApplicationTypeResourceException;
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

    private final String schema = """
            {
              "$schema" : "https://dial.epam.com/application_type_schemas/schema#",
              "$id" : "https://mydial.epam.com/custom_application_schemas/specific_application_type",
              "dial:applicationTypeEditorUrl" : "https://mydial.epam.com/specific_application_type_editor",
              "dial:applicationTypeDisplayName" : "Specific Application Type",
              "dial:applicationTypeCompletionEndpoint" : "http://specific_application_service/opeani/v1/completion",
              "properties" : {
                "clientFile" : {
                  "type" : "string",
                  "format" : "dial-file-encoded",
                  "dial:meta" : {
                    "dial:propertyKind" : "client",
                    "dial:propertyOrder" : 1
                  },
                  "dial:file" : true
                },
                "serverFile" : {
                  "type" : "string",
                  "format" : "dial-file-encoded",
                  "dial:meta" : {
                    "dial:propertyKind" : "server",
                    "dial:propertyOrder" : 2
                  },
                  "dial:file" : true
                }
              },
              "required" : [ "clientFile", "serverFile" ]
            }""";

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
    void consumeServerProperties_returnsProperties_whenSchemaExists() {
        when(config.getCustomApplicationSchema(any())).thenReturn(schema);
        application.setApplicationProperties(customProperties);
        application.setApplicationTypeSchemaId(URI.create("schemaId"));

        ApplicationTypeSchemaUtils.consumeServerProperties(config, application, (properties, appendApplicationPropertiesHeader) -> {
            Assertions.assertEquals(serverProperties, properties);
            Assertions.assertTrue(appendApplicationPropertiesHeader);
        });
    }

    @Test
    void consumeServerProperties_returnsEmptyMap_whenSchemaIsNull() {
        application.setApplicationTypeSchemaId(null);

        ApplicationTypeSchemaUtils.consumeServerProperties(config, application, (properties, appendApplicationPropertiesHeader) -> {
            Assertions.assertEquals(Collections.emptyMap(), properties);
            Assertions.assertTrue(appendApplicationPropertiesHeader);
        });
    }

    @Test
    void consumeServerProperties_throws_whenSchemaNotFound() {
        application.setApplicationTypeSchemaId(URI.create("schemaId"));
        when(config.getCustomApplicationSchema(any())).thenReturn(null);

        assertThrows(ApplicationTypeSchemaValidationException.class, () ->
                ApplicationTypeSchemaUtils.consumeServerProperties(config, application, (properties, appendApplicationPropertiesHeader) -> {
                }));
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
    public void modifyEndpointForCustomApplication_setsCustomEndpoints_whenSchemaExists() {
        application.setApplicationTypeSchemaId(URI.create("schemaId"));
        when(config.getCustomApplicationSchema(any())).thenReturn(schema);

        Application result = ApplicationTypeSchemaUtils.modifyEndpointsForCustomApplication(config, application);

        Assertions.assertNotSame(application, result);
        Assertions.assertEquals("http://specific_application_service/opeani/v1/completion", result.getEndpoint());
    }

    @Test
    public void modifyEndpointsForCustomApplication_return_original_app_whenSchemaIsNull() {
        application.setApplicationTypeSchemaId(null);

        Assertions.assertSame(application,
                ApplicationTypeSchemaUtils.modifyEndpointsForCustomApplication(config, application));
    }

    @Test
    public void modifyEndpointForCustomApplication_throws_whenEndpointsNotFound() {
        String schemaWithoutEndpoint = """
                {
                "$schema": "https://dial.epam.com/application_type_schemas/schema#",
                "$id": "https://mydial.epam.com/custom_application_schemas/specific_application_type",
                "properties": {
                  "clientFile": {
                    "type": "string",
                    "format": "dial-file-encoded",
                    "dial:meta": {
                      "dial:propertyKind": "client",
                      "dial:propertyOrder": 1
                    }
                  }
                },
                "required": ["clientFile"]
                }""";
        application.setApplicationTypeSchemaId(URI.create("schemaId"));
        when(config.getCustomApplicationSchema(any())).thenReturn(schemaWithoutEndpoint);

        Assertions.assertThrows(ApplicationTypeSchemaProcessingException.class, () ->
                ApplicationTypeSchemaUtils.modifyEndpointsForCustomApplication(config, application));
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

        Assertions.assertThrows(ApplicationTypeResourceException.class, () ->
                ApplicationTypeSchemaUtils.getFiles(config, application, encryptionService, resourceService));
    }

    @Test
    public void getServerFiles_returnsListOfServerFiles_whenSchemaExists() {
        application.setApplicationTypeSchemaId(URI.create("schemaId"));
        application.setApplicationProperties(customProperties);
        when(config.getCustomApplicationSchema(any())).thenReturn(schema);

        EncryptionService encryptionService = mock(EncryptionService.class);
        ResourceService resourceService = mock(ResourceService.class);

        when(resourceService.hasResource(any())).thenReturn(true);

        List<ResourceDescriptor> result = ApplicationTypeSchemaUtils.getServerFiles(config, application, encryptionService, resourceService);

        Assertions.assertEquals(1, result.size());
        Assertions.assertEquals(result.get(0).getUrl(), serverProperties.get("serverFile"));
    }

    @Test
    public void getServerFiles_returnsEmptyList_whenSchemaIsNull() {
        application.setApplicationTypeSchemaId(null);

        EncryptionService encryptionService = mock(EncryptionService.class);
        ResourceService resourceService = mock(ResourceService.class);

        List<ResourceDescriptor> result = ApplicationTypeSchemaUtils.getServerFiles(config, application, encryptionService, resourceService);

        Assertions.assertTrue(result.isEmpty());
    }

    @Test
    public void getServerFiles_throwsException_whenResourceNotFound() {
        application.setApplicationTypeSchemaId(URI.create("schemaId"));
        application.setApplicationProperties(customProperties);
        when(config.getCustomApplicationSchema(any())).thenReturn(schema);

        EncryptionService encryptionService = mock(EncryptionService.class);
        ResourceService resourceService = mock(ResourceService.class);

        when(resourceService.hasResource(any())).thenReturn(false);

        Assertions.assertThrows(ApplicationTypeResourceException.class, () ->
                ApplicationTypeSchemaUtils.getServerFiles(config, application, encryptionService, resourceService));
    }

    @Test
    public void getServerFiles_returnsListOfServerFiles_whenOneOfSchema() {
        final String schema = """
                {
                  "$schema" : "https://dial.epam.com/application_type_schemas/schema#",
                  "$id" : "https://mydial.epam.com/custom_application_schemas/specific_application_type",
                  "dial:applicationTypeEditorUrl" : "https://mydial.epam.com/specific_application_type_editor",
                  "dial:applicationTypeDisplayName" : "Specific Application Type",
                  "dial:applicationTypeCompletionEndpoint" : "http://specific_application_service/opeani/v1/completion",
                  "properties" : {
                    "clientFile" : {
                      "type" : "string",
                      "format" : "dial-file-encoded",
                      "dial:meta" : {
                        "dial:propertyKind" : "client",
                        "dial:propertyOrder" : 1
                      },
                      "dial:file" : true
                    },
                    "serverFile" : {
                       "oneOf": [
                           {
                             "type": "string",
                             "format": "dial-file-encoded",
                             "dial:file": true
                           },
                           {
                             "type": "array",
                             "items": {
                               "type": "string",
                               "dial:file": true,
                               "format": "dial-file-encoded"
                             }
                           },
                           {
                              "type": null
                           }
                         ],
                       "dial:meta": {
                         "dial:propertyKind": "server",
                         "dial:propertyOrder": 2
                       }
                    }
                  },
                  "required" : [ "clientFile", "serverFile" ]
                }""";

        final Map<String, Object> customServerProperties = Map.of(
                "serverFile",
                List.of("files/public/valid-file-path/valid-sub-path/valid%20file%20name2.ext", "files/public/valid-file-path/valid-sub-path/valid%20file%20name3.ext"));
        final Map<String, Object> customProperties = new HashMap<>();
        customProperties.putAll(customServerProperties);
        customProperties.putAll(clientProperties);

        application.setApplicationTypeSchemaId(URI.create("schemaId"));
        application.setApplicationProperties(customProperties);
        when(config.getCustomApplicationSchema(any())).thenReturn(schema);

        EncryptionService encryptionService = mock(EncryptionService.class);
        ResourceService resourceService = mock(ResourceService.class);

        when(resourceService.hasResource(any())).thenReturn(true);

        List<ResourceDescriptor> resultServer = ApplicationTypeSchemaUtils.getServerFiles(config, application, encryptionService, resourceService);
        List<ResourceDescriptor> resultAll = ApplicationTypeSchemaUtils.getFiles(config, application, encryptionService, resourceService);

        //check server files
        List<?> serverFiles = (List<?>) customServerProperties.get("serverFile");
        Assertions.assertEquals(serverFiles.size(), resultServer.size());
        for (int i = 0; i < resultServer.size(); i++) {
            Assertions.assertEquals(serverFiles.get(i), resultServer.get(i).getUrl());
        }

        //check all files
        Assertions.assertEquals(3, resultAll.size());
        Assertions.assertEquals(clientProperties.get("clientFile"), resultAll.get(0).getUrl());
        Assertions.assertEquals(serverFiles.get(0), resultAll.get(1).getUrl());
        Assertions.assertEquals(serverFiles.get(1), resultAll.get(2).getUrl());
    }

    @Test
    public void getServerFiles_returnsListOfServerProperties_whenRefInSchema() {
        final String schema = """
                {
                  "$schema" : "https://dial.epam.com/application_type_schemas/schema#",
                  "$id" : "https://mydial.epam.com/custom_application_schemas/specific_application_type",
                  "dial:applicationTypeEditorUrl" : "https://mydial.epam.com/specific_application_type_editor",
                  "dial:applicationTypeDisplayName" : "Specific Application Type",
                  "dial:applicationTypeCompletionEndpoint" : "http://specific_application_service/opeani/v1/completion",
                  "definitions" : {
                            "ExampleDef": {
                              "properties": {
                                  "str" : {
                                      "type": "string"
                                  }
                              },
                              "required": [
                                  "str"
                              ],
                              "title": "ExampleDef",
                              "type": "object"
                          }
                  },
                  "properties" : {
                      "obj": {
                         "$ref": "#/definitions/ExampleDef",
                         "description": "Example config",
                         "dial:meta" : {
                            "dial:propertyKind" : "server",
                            "dial:propertyOrder" : 1
                         }
                      }
                  },
                  "required" : [ "obj" ]
                }""";

        final Map<String, Object> customProperties = Map.of(
                "obj",
                Map.of("str", "test"));

        application.setApplicationTypeSchemaId(URI.create("schemaId"));
        application.setApplicationProperties(customProperties);
        when(config.getCustomApplicationSchema(any())).thenReturn(schema);

        ApplicationTypeSchemaUtils.consumeServerProperties(config, application, (properties, appendApplicationPropertiesHeader) -> {
            Assertions.assertEquals(customProperties, properties);
            Assertions.assertTrue(appendApplicationPropertiesHeader);
        });
    }
}
