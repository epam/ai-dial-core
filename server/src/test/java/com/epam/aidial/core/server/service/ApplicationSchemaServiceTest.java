package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.server.config.ConfigStore;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.util.ApplicationTypeSchemaProcessingException;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.validation.ApplicationTypeResourceException;
import com.epam.aidial.core.server.validation.ApplicationTypeSchemaValidationException;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ApplicationSchemaServiceTest {
    @Mock
    private Config config;
    @Mock
    private ConfigStore configStore;
    private Application application;
    @Mock
    private ResourceService resourceService;
    @Mock
    private EncryptionService encryptionService;

    @Mock
    private HttpClient httpClient;

    private ApplicationSchemaService service;

    private final String schema = """
            {
              "$schema" : "https://dial.epam.com/application_type_schemas/schema#",
              "$id" : "https://mydial.epam.com/custom_application_schemas/specific_application_type",
              "dial:applicationTypeEditorUrl" : "https://mydial.epam.com/specific_application_type_editor",
              "dial:applicationTypeDisplayName" : "Specific Application Type",
              "dial:applicationTypeCompletionEndpoint" : "http://specific_application_service/opeani/v1/completion",
              "dial:applicationTypeResponsesEndpoint" : "http://specific_application_service/opeani/v1/responses",
              "dial:applicationTypeAssistantAttachmentsInRequestSupported": true,
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
                },
                "application": {
                     "properties": {
                            "name": {
                              "description": "The name of the tool set.",
                              "title": "Name",
                              "type": "string"
                            },
                            "dial_id": {
                              "description": "The Dial ID associated with this MCP toolset.",
                              "title": "Dial Id",
                              "type": "string",
                              "dial:resource": true
                            }
                     },
                     "required": [
                            "name",
                            "dial_id"
                          ],
                     "title": "DialApp",
                     "type": "object"
                   },
                "toolset": {
                          "properties": {
                            "name": {
                              "description": "The name of the tool set.",
                              "title": "Name",
                              "type": "string"
                            },
                            "dial_id": {
                              "description": "The Dial ID associated with this MCP toolset.",
                              "title": "Dial Id",
                              "type": "string",
                              "dial:resource": true
                            },
                            "allowed_tools": {
                              "anyOf": [
                                {
                                  "items": {
                                    "type": "string"
                                  },
                                  "type": "array"
                                },
                                {
                                  "type": "null"
                                }
                              ],
                              "default": null,
                              "description": "Allowed MCP tool names from the server",
                              "title": "Allowed Tools"
                            }
                          },
                          "required": [
                            "name",
                            "dial_id"
                          ],
                          "title": "DialMCPToolSet",
                          "type": "object"
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

    @BeforeEach
    void setUp() {
        service = new ApplicationSchemaService(resourceService, configStore, encryptionService, httpClient);
        customProperties.putAll(clientProperties);
        customProperties.putAll(serverProperties);
        application = new Application();
    }

    @Test
    public void getCustomApplicationSchemaOrThrow_WithForceReload() throws IOException, InterruptedException {
        when(configStore.get()).thenReturn(config);
        URI schemaId = URI.create("schemaId");
        application.setApplicationTypeSchemaId(schemaId);
        String schema = """
                  {
                "$schema" : "https://dial.epam.com/application_type_schemas/schema#",
                "$id" : "https://mydial.epam.com/custom_application_schemas/specific_application_type",
                "dial:applicationTypeEditorUrl" : "https://mydial.epam.com/specific_application_type_editor",
                "dial:applicationTypeDisplayName" : "Specific Application Type",
                "dial:applicationTypeCompletionEndpoint" : "http://specific_application_service/opeani/v1/completion",
                "dial:applicationTypeAssistantAttachmentsInRequestSupported": true,
                "dial:applicationTypeSchemaEndpoint": "http://mydial.epam.com/schema"
                }
                  """;
        when(config.getCustomApplicationSchema(schemaId)).thenReturn(schema);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString()))).thenReturn(response);
        String appSchema = """
                {
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
                   }
                }
                """;
        when(response.body()).thenReturn(appSchema);
        when(response.statusCode()).thenReturn(200);

        String result = service.getCustomApplicationSchemaOrThrow(application, true);

        JsonNode expectedResult = ProxyUtil.MAPPER.createObjectNode();
        expectedResult = ProxyUtil.MAPPER.readerForUpdating(expectedResult).readTree(schema);
        expectedResult = ProxyUtil.MAPPER.readerForUpdating(expectedResult).readTree(appSchema);
        JsonNode actualResult = ProxyUtil.MAPPER.readTree(result);

        Assertions.assertEquals(expectedResult, actualResult);
    }

    @Test
    public void getCustomApplicationSchemaOrThrow_WithoutForceReload() throws IOException, InterruptedException {
        when(configStore.get()).thenReturn(config);
        URI schemaId = URI.create("schemaId");
        application.setApplicationTypeSchemaId(schemaId);
        String schema = """
                  {
                "$schema" : "https://dial.epam.com/application_type_schemas/schema#",
                "$id" : "https://mydial.epam.com/custom_application_schemas/specific_application_type",
                "dial:applicationTypeEditorUrl" : "https://mydial.epam.com/specific_application_type_editor",
                "dial:applicationTypeDisplayName" : "Specific Application Type",
                "dial:applicationTypeCompletionEndpoint" : "http://specific_application_service/opeani/v1/completion",
                "dial:applicationTypeAssistantAttachmentsInRequestSupported": true,
                "dial:applicationTypeSchemaEndpoint": "http://mydial.epam.com/schema"
                }
                  """;
        when(config.getCustomApplicationSchema(schemaId)).thenReturn(schema);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString()))).thenReturn(response);
        String appSchema = """
                {
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
                   }
                }
                """;
        when(response.body()).thenReturn(appSchema);
        when(response.statusCode()).thenReturn(200);

        String result = service.getCustomApplicationSchemaOrThrow(application, false);

        JsonNode expectedResult = ProxyUtil.MAPPER.createObjectNode();
        expectedResult = ProxyUtil.MAPPER.readerForUpdating(expectedResult).readTree(schema);
        expectedResult = ProxyUtil.MAPPER.readerForUpdating(expectedResult).readTree(appSchema);
        JsonNode actualResult = ProxyUtil.MAPPER.readTree(result);

        Assertions.assertEquals(expectedResult, actualResult);
    }

    @Test
    public void getCustomApplicationSchemaOrThrow_returnsSchema_whenSchemaIdExists() {
        when(configStore.get()).thenReturn(config);
        URI schemaId = URI.create("schemaId");
        application.setApplicationTypeSchemaId(schemaId);
        when(config.getCustomApplicationSchema(schemaId)).thenReturn("{}");

        String result = service.getCustomApplicationSchemaOrThrow(application, false);

        Assertions.assertEquals("{}", result);
    }

    @Test
    public void getCustomApplicationSchemaOrThrow_throws_whenSchemaNotFound() {
        when(configStore.get()).thenReturn(config);
        URI schemaId = URI.create("schemaId");
        application.setApplicationTypeSchemaId(schemaId);
        when(config.getCustomApplicationSchema(schemaId)).thenReturn(null);

        assertThrows(ApplicationTypeSchemaValidationException.class, () ->
                service.getCustomApplicationSchemaOrThrow(application, false));
    }

    @Test
    public void getCustomApplicationSchemaOrThrow_returnsNull_whenSchemaIdIsNull() {
        application.setApplicationTypeSchemaId(null);

        String result = service.getCustomApplicationSchemaOrThrow(application, false);

        Assertions.assertNull(result);
    }

    @Test
    void consumeMetadataProperties_returnsProperties_whenSchemaExists() {
        when(configStore.get()).thenReturn(config);
        when(config.getCustomApplicationSchema(any())).thenReturn(schema);
        application.setApplicationProperties(customProperties);
        application.setApplicationTypeSchemaId(URI.create("schemaId"));

        service.consumeMetadataProperties(application, (properties, appendApplicationPropertiesHeader) -> {
            Assertions.assertEquals(customProperties, properties);
            Assertions.assertTrue(appendApplicationPropertiesHeader);
        });
    }

    @Test
    void consumeMetadataProperties_returnsEmptyMap_whenSchemaIsNull() {
        application.setApplicationTypeSchemaId(null);
        application.setApplicationProperties(Map.of());

        service.consumeMetadataProperties(application, (properties, appendApplicationPropertiesHeader) -> {
            Assertions.assertTrue(properties.isEmpty());
            Assertions.assertTrue(appendApplicationPropertiesHeader);
        });
    }

    @Test
    void consumeMetadataProperties_throws_whenSchemaNotFound() {
        application.setApplicationTypeSchemaId(URI.create("schemaId"));

        assertThrows(ApplicationTypeSchemaValidationException.class, () ->
                service.consumeMetadataProperties(application, (properties, appendApplicationPropertiesHeader) -> {
                }));
    }


    @Test
    public void filterCustomClientProperties_returnsFilteredProperties_whenSchemaExists() {
        when(configStore.get()).thenReturn(config);
        when(config.getCustomApplicationSchema(any())).thenReturn(schema);
        application.setApplicationTypeSchemaId(URI.create("schemaId"));
        application.setApplicationProperties(customProperties);

        Application result = service.filterCustomClientProperties(application);

        Assertions.assertNotSame(application, result);
        Assertions.assertEquals(clientProperties, result.getApplicationProperties());
    }

    @Test
    public void filterCustomClientProperties_returnsOriginalApplication_whenApplicationPropertiesIsNull() {
        when(configStore.get()).thenReturn(config);
        when(config.getCustomApplicationSchema(any())).thenReturn(schema);
        application.setApplicationTypeSchemaId(URI.create("schemaId"));
        application.setApplicationProperties(null);

        Application result = service.filterCustomClientProperties(application);

        Assertions.assertSame(application, result);
    }

    @Test
    public void filterCustomClientProperties_returnsOriginalApplication_whenSchemaIsNull() {
        application.setApplicationTypeSchemaId(null);

        Application result = service.filterCustomClientProperties(application);

        Assertions.assertSame(application, result);
        Assertions.assertEquals(application, result);
    }

    @Test
    public void modifySchemaRichApplication() {
        when(configStore.get()).thenReturn(config);
        application.setApplicationTypeSchemaId(URI.create("schemaId"));
        when(config.getCustomApplicationSchema(any())).thenReturn(schema);

        Application result = service.modifySchemaRichApplication(application, false);

        Assertions.assertNotSame(application, result);
        Assertions.assertEquals("http://specific_application_service/opeani/v1/completion", result.getEndpoint());
        Assertions.assertEquals("http://specific_application_service/opeani/v1/responses", result.getResponsesEndpoint());
        Assertions.assertTrue(result.getFeatures().getAssistantAttachmentsInRequestSupported());
    }

    @Test
    public void modifySchemaRichApplication_setsRoutes_whenSchemaHasRoutes() {
        when(configStore.get()).thenReturn(config);
        application.setApplicationTypeSchemaId(URI.create("schemaId"));
        final String schemaWithRoutes = """
                {
                  "$schema" : "https://dial.epam.com/application_type_schemas/schema#",
                  "$id" : "https://mydial.epam.com/custom_application_schemas/specific_application_type",
                  "dial:applicationTypeCompletionEndpoint" : "http://specific_application_service/v1/completion",
                  "dial:applicationTypeRoutes": {
                    "data_sync": {
                      "dial:paths": ["/v1/data"],
                      "dial:rewritePath": true,
                      "dial:methods": ["PUT"],
                      "dial:upstreams": [{"dial:endpoint": "http://localhost:8080"}],
                      "dial:order": 5
                    }
                  }
                }""";
        when(config.getCustomApplicationSchema(any())).thenReturn(schemaWithRoutes);

        Application result = service.modifySchemaRichApplication(application, false);

        Assertions.assertNotNull(result.getRoutes());
        Assertions.assertEquals(1, result.getRoutes().size());
        Route route = result.getRoutes().get("data_sync");
        Assertions.assertNotNull(route);
        Assertions.assertTrue(route.isRewritePath());
        Assertions.assertEquals(Collections.singleton("PUT"), route.getMethods());
        Assertions.assertEquals(5, route.getOrder());
        Assertions.assertEquals(1, route.getUpstreams().size());
        Assertions.assertEquals("http://localhost:8080", route.getUpstreams().get(0).getEndpoint());
    }

    @Test
    public void modifySchemaRichApplication_withRoutesAndSchemaEndpoint() throws IOException, InterruptedException {
        when(configStore.get()).thenReturn(config);
        application.setApplicationTypeSchemaId(URI.create("schemaId"));
        String staticSchema = """
                {
                  "$schema": "https://dial.epam.com/application_type_schemas/schema#",
                  "$id": "https://mydial.epam.com/custom_application_schemas/specific_application_type",
                  "dial:applicationTypeCompletionEndpoint": "http://specific_application_service/v1/completion",
                  "dial:applicationTypeSchemaEndpoint": "http://mydial.epam.com/schema"
                }""";
        when(config.getCustomApplicationSchema(any())).thenReturn(staticSchema);
        String downloadedSchema = """
                {
                  "dial:applicationTypeRoutes": {
                    "data_sync": {
                      "dial:paths": ["/v1/data"],
                      "dial:rewritePath": true,
                      "dial:methods": ["PUT"],
                      "dial:upstreams": [{"dial:endpoint": "http://localhost:8080"}],
                      "dial:order": 5
                    }
                  }
                }""";
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(downloadedSchema);
        when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString()))).thenReturn(response);

        Application result = service.modifySchemaRichApplication(application, false);

        Assertions.assertNull(result.getInvalid());
        Assertions.assertNotNull(result.getRoutes());
        Assertions.assertEquals(1, result.getRoutes().size());
        Route route = result.getRoutes().get("data_sync");
        Assertions.assertNotNull(route);
        Assertions.assertTrue(route.isRewritePath());
        Assertions.assertEquals(Collections.singleton("PUT"), route.getMethods());
        Assertions.assertEquals(5, route.getOrder());
        Assertions.assertEquals(1, route.getUpstreams().size());
        Assertions.assertEquals("http://localhost:8080", route.getUpstreams().get(0).getEndpoint());
    }

    @Test
    public void modifySchemaRichApplication_setsInvalid_whenSchemaLoadingFails() throws IOException, InterruptedException {
        when(configStore.get()).thenReturn(config);
        application.setApplicationTypeSchemaId(URI.create("schemaId"));
        String staticSchema = """
                {
                  "$schema": "https://dial.epam.com/application_type_schemas/schema#",
                  "$id": "https://mydial.epam.com/custom_application_schemas/specific_application_type",
                  "dial:applicationTypeCompletionEndpoint": "http://specific_application_service/v1/completion",
                  "dial:applicationTypeSchemaEndpoint": "http://mydial.epam.com/schema"
                }""";
        when(config.getCustomApplicationSchema(any())).thenReturn(staticSchema);
        when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenThrow(new IOException("Connection refused"));

        Application result = service.modifySchemaRichApplication(application, false);

        Assertions.assertEquals(Boolean.TRUE, result.getInvalid());
        Assertions.assertNull(result.getApplicationProperties());
    }

    @Test
    public void modifyEndpointForCustomApplication_setsCustomEndpoints_whenSchemaExists() {
        when(configStore.get()).thenReturn(config);
        application.setApplicationTypeSchemaId(URI.create("schemaId"));
        when(config.getCustomApplicationSchema(any())).thenReturn(schema);

        Application result = service.modifyEndpointsForCustomApplication(application);

        Assertions.assertNotSame(application, result);
        Assertions.assertEquals("http://specific_application_service/opeani/v1/completion", result.getEndpoint());
        Assertions.assertEquals("http://specific_application_service/opeani/v1/responses", result.getResponsesEndpoint());
    }

    @Test
    public void modifyEndpointsForCustomApplication_return_original_app_whenSchemaIsNull() {
        application.setApplicationTypeSchemaId(null);

        Assertions.assertSame(application, service.modifyEndpointsForCustomApplication(application));
    }

    @Test
    public void modifyEndpointForCustomApplication_ChatEndpointIsOptional() {
        when(configStore.get()).thenReturn(config);
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

        Assertions.assertNull(service.modifyEndpointsForCustomApplication(application).getEndpoint());
    }

    @Test
    public void getFiles_returnsListOfFiles_whenSchemaExists() {
        when(configStore.get()).thenReturn(config);
        application.setApplicationTypeSchemaId(URI.create("schemaId"));
        application.setApplicationProperties(customProperties);
        when(config.getCustomApplicationSchema(any())).thenReturn(schema);

        when(resourceService.hasResource(any())).thenReturn(true);

        List<ResourceDescriptor> result = service.getFiles(application);

        Assertions.assertEquals(2, result.size());
    }

    @Test
    public void getFiles_returnsEmptyList_whenSchemaIsNull() {
        application.setApplicationTypeSchemaId(null);

        List<ResourceDescriptor> result = service.getFiles(application);

        Assertions.assertTrue(result.isEmpty());
    }

    @Test
    public void getFiles_throwsException_whenResourceNotFound() {
        when(configStore.get()).thenReturn(config);
        application.setApplicationTypeSchemaId(URI.create("schemaId"));
        application.setApplicationProperties(customProperties);
        when(config.getCustomApplicationSchema(any())).thenReturn(schema);

        when(resourceService.hasResource(any())).thenReturn(false);

        Assertions.assertThrows(ApplicationTypeResourceException.class, () -> service.getFiles(application));
    }

    @Test
    public void getFiles_dedupsDuplicateFileReferences() {
        when(configStore.get()).thenReturn(config);
        application.setApplicationTypeSchemaId(URI.create("schemaId"));

        String sharedUrl = "files/public/valid-file-path/valid-sub-path/valid%20file%20name1.ext";
        Map<String, Object> duplicateProperties = new HashMap<>();
        duplicateProperties.put("clientFile", sharedUrl);
        duplicateProperties.put("serverFile", sharedUrl);
        application.setApplicationProperties(duplicateProperties);

        when(config.getCustomApplicationSchema(any())).thenReturn(schema);
        when(resourceService.hasResource(any())).thenReturn(true);

        List<ResourceDescriptor> result = service.getFiles(application);

        Assertions.assertEquals(1, result.size());
        Assertions.assertEquals(sharedUrl, result.get(0).getUrl());
    }

    @Test
    public void getServerFiles_returnsListOfServerFiles_whenSchemaExists() {
        when(configStore.get()).thenReturn(config);
        application.setApplicationTypeSchemaId(URI.create("schemaId"));
        application.setApplicationProperties(customProperties);
        when(config.getCustomApplicationSchema(any())).thenReturn(schema);

        when(resourceService.hasResource(any())).thenReturn(true);

        List<ResourceDescriptor> result = service.getServerFiles(application);

        Assertions.assertEquals(1, result.size());
        Assertions.assertEquals(result.get(0).getUrl(), serverProperties.get("serverFile"));
    }

    @Test
    public void getServerFiles_returnsEmptyList_whenSchemaIsNull() {
        application.setApplicationTypeSchemaId(null);

        List<ResourceDescriptor> result = service.getServerFiles(application);

        Assertions.assertTrue(result.isEmpty());
    }

    @Test
    public void getServerFiles_throwsException_whenResourceNotFound() {
        when(configStore.get()).thenReturn(config);
        application.setApplicationTypeSchemaId(URI.create("schemaId"));
        application.setApplicationProperties(customProperties);
        when(config.getCustomApplicationSchema(any())).thenReturn(schema);

        when(resourceService.hasResource(any())).thenReturn(false);

        Assertions.assertThrows(ApplicationTypeResourceException.class, () ->
                service.getServerFiles(application));
    }

    @Test
    public void getServerFiles_returnsListOfServerFiles_whenOneOfSchema() {
        when(configStore.get()).thenReturn(config);
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

        when(resourceService.hasResource(any())).thenReturn(true);

        List<ResourceDescriptor> resultServer = service.getServerFiles(application);
        List<ResourceDescriptor> resultAll = service.getFiles(application);

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
    public void testGetRoutes_WhenSchemaAndRoutesExist() {
        when(configStore.get()).thenReturn(config);
        URI appSchemaUri = URI.create("schemaId");
        application.setApplicationTypeSchemaId(appSchemaUri);
        final String schema = """
                {
                  "$schema" : "https://dial.epam.com/application_type_schemas/schema#",
                  "$id" : "https://mydial.epam.com/custom_application_schemas/specific_application_type",
                  "dial:applicationTypeEditorUrl" : "https://mydial.epam.com/specific_application_type_editor",
                  "dial:applicationTypeDisplayName" : "Specific Application Type",
                  "dial:applicationTypeCompletionEndpoint" : "http://specific_application_service/opeani/v1/completion",
                  "dial:applicationTypeRoutes": {
                      "data_sync": {
                        "dial:paths": [
                          "/v1/data"
                        ],
                        "dial:rewritePath": true,
                        "dial:methods": [
                          "PUT"
                        ],
                        "dial:userRoles": [
                          "admin",
                          "user"
                        ],
                        "dial:upstreams": [
                          {
                            "dial:endpoint": "http://localhost:8080"
                          },
                          {
                            "dial:endpoint": "http://localhost:8081"
                          }
                        ],
                        "dial:order": 5,
                        "dial:permissions": [
                          "WRITE"
                        ]
                      }
                    }
                }""";

        when(config.getCustomApplicationSchema(eq(appSchemaUri))).thenReturn(schema);

        Map<String, Route> routes = service.getRoutes(application);

        Assertions.assertNotNull(routes);
        Assertions.assertEquals(1, routes.size());
        Assertions.assertTrue(routes.containsKey("data_sync"));
    }

    @Test
    public void testGetRoutes_WhenSchemaExist() {
        when(configStore.get()).thenReturn(config);
        URI appSchemaUri = URI.create("schemaId");
        application.setApplicationTypeSchemaId(appSchemaUri);
        final String schema = """
                {
                  "$schema" : "https://dial.epam.com/application_type_schemas/schema#",
                  "$id" : "https://mydial.epam.com/custom_application_schemas/specific_application_type",
                  "dial:applicationTypeEditorUrl" : "https://mydial.epam.com/specific_application_type_editor",
                  "dial:applicationTypeDisplayName" : "Specific Application Type",
                  "dial:applicationTypeCompletionEndpoint" : "http://specific_application_service/opeani/v1/completion"
                }""";

        when(config.getCustomApplicationSchema(eq(appSchemaUri))).thenReturn(schema);

        Map<String, Route> routes = service.getRoutes(application);

        Assertions.assertNull(routes);
    }

    @Test
    public void testGetRoutes_WhenSchemaNotExist() {
        Map<String, Route> routes = service.getRoutes(application);
        Assertions.assertNull(routes);
    }

    @Test
    public void testGetToolsets_ToolsetExistsAndHasDialResourceFormat() {
        customProperties.put("toolset", Map.of("name", "my-toolset", "dial_id", "toolsets/bucket/my-toolset"));
        customProperties.put("application", Map.of("name", "my-app", "dial_id", "applications/bucket/my-app"));
        when(configStore.get()).thenReturn(config);
        when(config.getCustomApplicationSchema(any())).thenReturn(schema);
        application.setApplicationProperties(customProperties);
        application.setApplicationTypeSchemaId(URI.create("schemaId"));
        when(resourceService.hasResource(any())).thenReturn(true);
        when(encryptionService.decrypt(anyString())).thenReturn("/Users/123/");
        List<ResourceDescriptor> result = service.getDeployments(application);
        Assertions.assertEquals(2, result.size());
        var sorted = result.stream().map(ResourceDescriptor::getName).sorted().toList();
        Assertions.assertEquals(List.of("my-app", "my-toolset"), sorted);
    }

    @Test
    public void getPrompts_returnsListOfPrompts_whenSchemaHasDialResourcePrompts() {
        customProperties.put("toolset", Map.of("name", "my-prompt", "dial_id", "prompts/bucket/my-prompt"));
        when(configStore.get()).thenReturn(config);
        when(config.getCustomApplicationSchema(any())).thenReturn(schema);
        application.setApplicationProperties(customProperties);
        application.setApplicationTypeSchemaId(URI.create("schemaId"));
        when(resourceService.hasResource(any())).thenReturn(true);
        when(encryptionService.decrypt(anyString())).thenReturn("/Users/123/");

        List<ResourceDescriptor> result = service.getPrompts(application);

        Assertions.assertEquals(1, result.size());
        Assertions.assertEquals("my-prompt", result.getFirst().getName());
    }

    @Test
    public void getPrompts_whenPromptUrlIsFolder() {
        customProperties.put("toolset", Map.of("name", "my-prompt", "dial_id", "prompts/bucket/my-folder/"));
        when(configStore.get()).thenReturn(config);
        when(config.getCustomApplicationSchema(any())).thenReturn(schema);
        application.setApplicationProperties(customProperties);
        application.setApplicationTypeSchemaId(URI.create("schemaId"));
        when(encryptionService.decrypt(anyString())).thenReturn("/Users/123/");

        List<ResourceDescriptor> result = service.getPrompts(application);

        Assertions.assertEquals(1, result.size());
        Assertions.assertEquals("my-folder", result.getFirst().getName());
    }

    @Test
    public void getPrompts_returnsEmptyList_whenSchemaIsNull() {
        application.setApplicationTypeSchemaId(null);

        List<ResourceDescriptor> result = service.getPrompts(application);

        Assertions.assertTrue(result.isEmpty());
    }

    @Test
    public void getSkills_returnsListOfSkills_whenSchemaHasDialResourceSkills() {
        customProperties.put("toolset", Map.of("name", "my-skill", "dial_id", "skills/bucket/my-skill"));
        when(configStore.get()).thenReturn(config);
        when(config.getCustomApplicationSchema(any())).thenReturn(schema);
        application.setApplicationProperties(customProperties);
        application.setApplicationTypeSchemaId(URI.create("schemaId"));
        when(resourceService.hasResource(any())).thenReturn(true);
        when(encryptionService.decrypt(anyString())).thenReturn("/Users/123/");

        List<ResourceDescriptor> result = service.getSkills(application);

        Assertions.assertEquals(1, result.size());
        Assertions.assertEquals("my-skill", result.getFirst().getName());
    }

    @Test
    public void getSkills_returnsEmptyList_whenSchemaIsNull() {
        application.setApplicationTypeSchemaId(null);

        List<ResourceDescriptor> result = service.getSkills(application);

        Assertions.assertTrue(result.isEmpty());
    }

    @Test
    public void testGetToolsets_ToolsetExistsNotDialResourceFormat() {
        customProperties.put("toolset", Map.of("name", "my-toolset", "dial_id", "mytoolset"));
        when(configStore.get()).thenReturn(config);
        when(config.getCustomApplicationSchema(any())).thenReturn(schema);
        application.setApplicationProperties(customProperties);
        application.setApplicationTypeSchemaId(URI.create("schemaId"));
        List<ResourceDescriptor> result = service.getDeployments(application);
        Assertions.assertTrue(result.isEmpty());
        when(resourceService.hasResource(any(ResourceDescriptor.class))).thenReturn(true);
        List<ResourceDescriptor> files = service.getFiles(application);
        Assertions.assertEquals(2, files.size());
    }

    @Test
    public void getMcp_calledTwiceWithSameSchema_reusesCacheEntry() {
        when(configStore.get()).thenReturn(config);
        when(config.getCustomApplicationSchema(any())).thenReturn(schema);
        application.setApplicationTypeSchemaId(URI.create("schemaId"));

        Application.Mcp first = service.getMcp(application);
        Application.Mcp second = service.getMcp(application);

        Assertions.assertEquals(first, second);
        Assertions.assertEquals(1, service.schemaCacheSize());
    }

    @Test
    public void filterCustomClientProperties_calledTwiceWithSameSchema_reusesCompiledSchema() {
        when(configStore.get()).thenReturn(config);
        when(config.getCustomApplicationSchema(any())).thenReturn(schema);
        application.setApplicationTypeSchemaId(URI.create("schemaId"));
        application.setApplicationProperties(customProperties);

        Application first = service.filterCustomClientProperties(application);
        Application second = service.filterCustomClientProperties(application);

        Assertions.assertEquals(first.getApplicationProperties(), second.getApplicationProperties());
        Assertions.assertEquals(1, service.schemaCacheSize());
    }

    @Test
    public void getSchema_differentSchemaIds_growsCacheIndependently() {
        when(configStore.get()).thenReturn(config);
        URI schemaId1 = URI.create("schemaId1");
        URI schemaId2 = URI.create("schemaId2");
        when(config.getCustomApplicationSchema(schemaId1)).thenReturn("{\"a\":1}");
        when(config.getCustomApplicationSchema(schemaId2)).thenReturn("{\"b\":2}");

        String result1 = service.getSchema(schemaId1, false);
        String result2 = service.getSchema(schemaId2, false);

        Assertions.assertEquals("{\"a\":1}", result1);
        Assertions.assertEquals("{\"b\":2}", result2);
        Assertions.assertEquals(2, service.schemaCacheSize());
    }
}
