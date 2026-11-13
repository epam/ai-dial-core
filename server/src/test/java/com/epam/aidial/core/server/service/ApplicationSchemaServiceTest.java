package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.config.Router;
import com.epam.aidial.core.server.config.ConfigStore;
import com.epam.aidial.core.server.config.ValidationWarning;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.resource.ComplexResourceService;
import com.epam.aidial.core.server.util.ApplicationTypeSchemaProcessingException;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.validation.ApplicationTypeResourceException;
import com.epam.aidial.core.server.validation.ApplicationTypeSchemaValidationException;
import com.epam.aidial.core.server.validation.CollectionIssue;
import com.epam.aidial.core.server.validation.CollectionIssueKind;
import com.epam.aidial.core.server.validation.MarkerCollection;
import com.epam.aidial.core.server.validation.MarkerCollection.MarkerReference;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    private ComplexResourceService complexResourceService;

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
        service = new ApplicationSchemaService(resourceService, configStore, complexResourceService, encryptionService, httpClient);
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
        when(complexResourceService.hasResource(any())).thenReturn(true);
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
    public void getSkills_throwsException_whenResourceNotFound() {
        customProperties.put("toolset", Map.of("name", "my-skill", "dial_id", "skills/bucket/my-skill"));
        when(configStore.get()).thenReturn(config);
        when(config.getCustomApplicationSchema(any())).thenReturn(schema);
        application.setApplicationProperties(customProperties);
        application.setApplicationTypeSchemaId(URI.create("schemaId"));
        when(complexResourceService.hasResource(any())).thenReturn(false);
        when(encryptionService.decrypt(anyString())).thenReturn("/Users/123/");

        Assertions.assertThrows(ApplicationTypeResourceException.class, () -> service.getSkills(application));
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

    private static final URI SCHEMA_ID = URI.create("schemaId");

    private static Map<String, Object> properties(Object... keyValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            result.put((String) keyValues[i], keyValues[i + 1]);
        }
        return result;
    }

    /** Collects as the export does: builds the schema from the given configuration, then collects the properties against it. */
    private MarkerCollection collect(ApplicationSchemaService under, Config source) {
        ApplicationSchemaService.ExportSchema built = under.buildExportSchema(application.getApplicationTypeSchemaId(), source);
        return built.issue() != null ? MarkerCollection.ofIssue(built.issue()) : under.collectMarkerReferences(application, built.schema());
    }

    private MarkerCollection collect(Config source) {
        return collect(service, source);
    }

    private MarkerCollection collectFrom(String mergedSchema, Map<String, Object> applicationProperties) {
        application.setApplicationTypeSchemaId(SCHEMA_ID);
        application.setApplicationProperties(applicationProperties);
        when(config.getCustomApplicationSchema(SCHEMA_ID)).thenReturn(mergedSchema);
        return collect(config);
    }

    private static MarkerReference reference(String pointer, String value) {
        return new MarkerReference(pointer, value);
    }

    @Test
    public void collectMarkerReferences_returnsPointerAndValueOfEachMarker() {
        String mergedSchema = """
                {"type":"object","properties":{
                  "first":{"type":"string","dial:resource":true},
                  "second":{"type":"string","dial:file":true}}}""";

        MarkerCollection result = collectFrom(mergedSchema,
                properties("first", "toolsets/platform/a", "second", "files/public/x"));

        Assertions.assertEquals(List.of(reference("/first", "toolsets/platform/a"),
                reference("/second", "files/public/x")), result.references());
        Assertions.assertTrue(result.issues().isEmpty());
    }

    @Test
    public void collectMarkerReferences_collectsThroughOneOfAnyOfAndAllOfAndCollapsesRepeatedVisitsOfOneLocation() {
        String mergedSchema = """
                {"type":"object","properties":{
                  "viaOneOf":{"oneOf":[{"type":"string","dial:resource":true},{"type":"integer"}]},
                  "viaAnyOf":{"anyOf":[{"type":"string","dial:file":true},{"type":"null"}]},
                  "viaAllOf":{"allOf":[{"type":"string","dial:resource":true},{"minLength":1}]},
                  "visitedTwice":{"oneOf":[
                    {"type":"string","minLength":100,"dial:resource":true},
                    {"type":"string","dial:resource":true}]}}}""";

        MarkerCollection result = collectFrom(mergedSchema,
                properties("viaOneOf", "toolsets/platform/a", "viaAnyOf", "files/public/x", "viaAllOf", "toolsets/platform/b",
                        "visitedTwice", "toolsets/platform/c"));

        Assertions.assertEquals(List.of(reference("/viaOneOf", "toolsets/platform/a"),
                reference("/viaAnyOf", "files/public/x"),
                reference("/viaAllOf", "toolsets/platform/b"),
                reference("/visitedTwice", "toolsets/platform/c")), result.references());
        Assertions.assertTrue(result.issues().isEmpty());
    }

    @Test
    public void collectMarkerReferences_keepsOtherReferencesAndRecordsIssueWhenOnePropertyIsInvalid() {
        String mergedSchema = """
                {"type":"object","required":["absent"],"properties":{
                  "broken":{"type":"integer"},
                  "good":{"type":"string","dial:resource":true}}}""";

        MarkerCollection result = collectFrom(mergedSchema,
                properties("broken", "not-a-number", "good", "toolsets/platform/a"));

        Assertions.assertEquals(List.of(reference("/good", "toolsets/platform/a")), result.references());
        Assertions.assertEquals(List.of("", "/broken"), result.issues().stream().map(CollectionIssue::pointer).toList(),
                "an issue of the root has the empty pointer");
        for (CollectionIssue issue : result.issues()) {
            Assertions.assertEquals(CollectionIssueKind.INSTANCE_INVALID, issue.kind());
        }
    }

    @Test
    public void collectMarkerReferences_keepsMarkedValueThatViolatesItsFormat() {
        String mergedSchema = """
                {"type":"object","properties":{
                  "file":{"type":"string","format":"dial-file-encoded","dial:file":true}}}""";

        MarkerCollection result = collectFrom(mergedSchema, properties("file", "not-a-file-url"));

        Assertions.assertEquals(List.of(reference("/file", "not-a-file-url")), result.references());
        Assertions.assertEquals(1, result.issues().size());
        Assertions.assertEquals(CollectionIssueKind.INSTANCE_INVALID, result.issues().get(0).kind());
        Assertions.assertEquals("/file", result.issues().get(0).pointer());
    }

    @Test
    public void collectMarkerReferences_returnsMalformedAndExternalValuesRaw() {
        String mergedSchema = """
                {"type":"object","properties":{
                  "url":{"type":"string","dial:resource":true},
                  "shortName":{"type":"string","dial:resource":true},
                  "spaced":{"type":"string","dial:resource":true}}}""";
        String external = "https://user:secret@example.com/x?token=abc";

        MarkerCollection result = collectFrom(mergedSchema,
                properties("url", external, "shortName", "gpt-4o", "spaced", "files/b/a b"));

        Assertions.assertEquals(List.of(reference("/url", external),
                reference("/shortName", "gpt-4o"),
                reference("/spaced", "files/b/a b")), result.references());
        Assertions.assertTrue(result.issues().isEmpty());
    }

    @Test
    public void collectMarkerReferences_ignoresEmptyValueLikeTheKeywordsDo() {
        String mergedSchema = """
                {"type":"object","properties":{"empty":{"type":"string","dial:resource":true}}}""";

        MarkerCollection result = collectFrom(mergedSchema, properties("empty", ""));

        Assertions.assertTrue(result.references().isEmpty());
        Assertions.assertTrue(result.issues().isEmpty());
    }

    @Test
    public void collectMarkerReferences_reportsSchemaNotFoundWithoutThrowing() {
        application.setApplicationTypeSchemaId(SCHEMA_ID);
        application.setApplicationProperties(properties("first", "toolsets/platform/a"));
        when(configStore.get()).thenReturn(config);
        when(config.getCustomApplicationSchema(SCHEMA_ID)).thenReturn(null);

        MarkerCollection result = collect(configStore.get());

        Assertions.assertTrue(result.references().isEmpty());
        Assertions.assertEquals(1, result.issues().size());
        Assertions.assertEquals(CollectionIssueKind.SCHEMA_NOT_FOUND, result.issues().get(0).kind());
    }

    @Test
    public void buildExportSchema_propagatesAnUnexpectedRuntimeExceptionInsteadOfRecordingAnIssue() {
        Config failing = mock(Config.class);
        when(failing.getCustomApplicationSchema(SCHEMA_ID)).thenThrow(new IllegalStateException("boom"));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.buildExportSchema(SCHEMA_ID, failing));

        Assertions.assertEquals("boom", thrown.getMessage());
    }

    @Test
    public void collectMarkerReferences_acceptsLocalRefsOfSchemaWithAbsoluteId() {
        String mergedSchema = """
                {"$id":"https://mydial.example/custom_application_schemas/quick","type":"object",
                 "properties":{"a":{"$ref":"#/$defs/res"}},
                 "$defs":{"res":{"type":"string","dial:resource":true}}}""";

        MarkerCollection result = collectFrom(mergedSchema, properties("a", "toolsets/platform/a"));

        Assertions.assertEquals(List.of(reference("/a", "toolsets/platform/a")), result.references());
        Assertions.assertTrue(result.issues().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"remote", "local file"})
    public void collectMarkerReferences_refusesRefOutsideTheDocumentWithoutFetchingOrReadingIt(String kind) throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ref.json", exchange -> {
            requests.incrementAndGet();
            byte[] body = "{\"type\":\"string\"}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        java.nio.file.Path file = java.nio.file.Files.createTempFile("ref", ".json");
        try {
            java.nio.file.Files.writeString(file, "{\"type\":\"string\"}");
            String address = kind.equals("remote") ? "http://127.0.0.1:" + server.getAddress().getPort() + "/ref.json" : file.toUri().toString();
            String mergedSchema = "{\"type\":\"object\",\"properties\":{\"a\":{\"$ref\":\"" + address + "\"}}}";

            MarkerCollection result = collectFrom(mergedSchema, properties("a", "toolsets/platform/a"));

            Assertions.assertEquals(0, requests.get(), "a schema document must not make Core send a request");
            Assertions.assertTrue(result.references().isEmpty());
            Assertions.assertEquals(1, result.issues().size());
            CollectionIssue issue = result.issues().get(0);
            Assertions.assertEquals(CollectionIssueKind.SCHEMA_UNAVAILABLE, issue.kind());
            Assertions.assertFalse(issue.toString().contains(address), "the issue must not carry the IRI");
        } finally {
            server.stop(0);
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    @Test
    public void collectMarkerReferences_returnsIssuesInStableOrderWithoutTheInstanceValue() {
        String mergedSchema = """
                {"type":"object","properties":{
                  "b":{"type":"integer"},
                  "a":{"type":"integer"}}}""";

        MarkerCollection result = collectFrom(mergedSchema, properties("b", "secret-value-b", "a", "secret-value-a"));

        Assertions.assertEquals(List.of("/a", "/b"), result.issues().stream().map(CollectionIssue::pointer).toList());
        for (CollectionIssue issue : result.issues()) {
            Assertions.assertFalse(issue.toString().contains("secret-value"), "the issue must not hold the instance value");
        }
    }

    @Test
    public void collectMarkerReferences_checksNoExistenceOfTheResources() {
        MarkerCollection collection = collectFrom(schema, customProperties);

        Assertions.assertEquals(2, collection.references().size());
        Assertions.assertTrue(collection.issues().isEmpty());
        verifyNoInteractions(resourceService, complexResourceService);
    }

    private static final String RUNNER_SCHEMA = """
            {"type":"object","properties":{"first":{"type":"string","dial:resource":true}}}""";

    private void givenRunnerAnswers(String body) throws IOException, InterruptedException {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(body);
        when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString()))).thenReturn(response);
    }

    private ApplicationSchemaService serviceWithTheRealClient() {
        return new ApplicationSchemaService(resourceService, configStore, complexResourceService, encryptionService,
                (java.net.ProxySelector) null);
    }

    private void givenSchemaWhoseRunnerIs(URI schemaId, String runnerUrl) {
        application.setApplicationTypeSchemaId(schemaId);
        application.setApplicationProperties(properties("first", "toolsets/platform/a"));
        when(configStore.get()).thenReturn(config);
        when(config.getCustomApplicationSchema(schemaId)).thenReturn(
                "{\"dial:applicationTypeSchemaEndpoint\":\"" + runnerUrl + "\"}");
    }

    @Test
    public void collectMarkerReferences_doesNotReplaceTheSchemaThatRequestProcessingUses()
            throws IOException, InterruptedException {
        URI schemaId = URI.create("schemaId");
        givenSchemaWhoseRunnerIs(schemaId, "http://runner.example/schema");
        givenRunnerAnswers(RUNNER_SCHEMA);

        MarkerCollection beforeAnythingIsCached = collect(configStore.get());

        Assertions.assertEquals(List.of(reference("/first", "toolsets/platform/a")), beforeAnythingIsCached.references());
        Assertions.assertEquals(0, service.schemaCacheSize(), "the export neither reads nor fills the cache");

        String cachedByRequestProcessing = service.getSchema(schemaId, false);
        givenRunnerAnswers("{\"type\":\"object\",\"properties\":{\"first\":{\"type\":\"string\"}}}");

        MarkerCollection result = collect(configStore.get());

        Assertions.assertTrue(result.references().isEmpty(), "the export evaluated the live document, not the cached one");
        Assertions.assertEquals(cachedByRequestProcessing, service.getSchema(schemaId, false),
                "request processing still sees what it cached");
        Assertions.assertEquals(1, service.schemaCacheSize());
    }

    @Test
    public void getSchema_withForceReloadStillReplacesTheCachedSchema() throws IOException, InterruptedException {
        URI schemaId = URI.create("schemaId");
        givenSchemaWhoseRunnerIs(schemaId, "http://runner.example/schema");
        givenRunnerAnswers(RUNNER_SCHEMA);
        String before = service.getSchema(schemaId, false);
        givenRunnerAnswers("{\"type\":\"object\",\"properties\":{}}");

        String reloaded = service.getSchema(schemaId, true);

        Assertions.assertNotEquals(before, reloaded);
        Assertions.assertEquals(reloaded, service.getSchema(schemaId, false), "existing callers keep the refresh semantics");
    }

    @Test
    public void collectMarkerReferences_withTheRealClientGetsTheRunnersSchemaAndSendsNoCredentials() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        java.util.concurrent.CopyOnWriteArrayList<String> headerNames = new java.util.concurrent.CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/schema", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestHeaders().keySet().forEach(name -> headerNames.add(name.toLowerCase()));
            byte[] body = RUNNER_SCHEMA.getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            givenSchemaWhoseRunnerIs(URI.create("schemaId"), "http://127.0.0.1:" + server.getAddress().getPort() + "/schema");

            MarkerCollection result = collect(serviceWithTheRealClient(), configStore.get());

            Assertions.assertEquals(List.of(reference("/first", "toolsets/platform/a")), result.references());
            Assertions.assertTrue(result.issues().isEmpty());
            Assertions.assertEquals(1, requests.get());
            Assertions.assertFalse(headerNames.contains("authorization") || headerNames.contains("cookie")
                    || headerNames.contains("proxy-authorization"), "no credential is sent: " + headerNames);
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void collectMarkerReferences_withTheRealClientDoesNotFollowRedirectOfTheRunner() throws IOException {
        AtomicInteger targetRequests = new AtomicInteger();
        HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/other", exchange -> {
            targetRequests.incrementAndGet();
            byte[] body = RUNNER_SCHEMA.getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        target.start();
        AtomicInteger runnerRequests = new AtomicInteger();
        HttpServer runner = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        runner.createContext("/schema", exchange -> {
            runnerRequests.incrementAndGet();
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + target.getAddress().getPort() + "/other");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        runner.start();
        try {
            givenSchemaWhoseRunnerIs(URI.create("schemaId"), "http://127.0.0.1:" + runner.getAddress().getPort() + "/schema");

            MarkerCollection result = collect(serviceWithTheRealClient(), configStore.get());

            Assertions.assertEquals(1, runnerRequests.get());
            Assertions.assertEquals(0, targetRequests.get(), "the redirect target is never contacted");
            Assertions.assertEquals(1, result.issues().size());
            Assertions.assertEquals(CollectionIssueKind.SCHEMA_UNAVAILABLE, result.issues().get(0).kind());
        } finally {
            runner.stop(0);
            target.stop(0);
        }
    }

    private static final class LogCapture implements AutoCloseable {
        private final ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ApplicationSchemaService.class);
        private final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();

        private final ch.qos.logback.classic.Level level = logger.getLevel();

        LogCapture() {
            appender.start();
            logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
            logger.addAppender(appender);
        }

        String text() {
            StringBuilder out = new StringBuilder();
            for (ch.qos.logback.classic.spi.ILoggingEvent event : appender.list) {
                out.append(event.getFormattedMessage());
                if (event.getThrowableProxy() != null) {
                    out.append(' ').append(ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(event.getThrowableProxy()));
                }
                out.append('\n');
            }
            return out.toString();
        }

        @Override
        public void close() {
            logger.setLevel(level);
            logger.detachAppender(appender);
        }
    }

    @Test
    public void collectMarkerReferences_logsNoEndpointAndNoExceptionWhenTheDownloadFails()
            throws IOException, InterruptedException {
        givenSchemaWhoseRunnerIs(URI.create("schemaId"), "http://runner.example/SECRET-PATH?token=abc");
        when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenThrow(new IOException("cannot reach http://runner.example/SECRET-PATH?token=abc"));

        try (LogCapture logs = new LogCapture()) {
            MarkerCollection result = collect(configStore.get());

            Assertions.assertEquals(CollectionIssueKind.SCHEMA_UNAVAILABLE, result.issues().get(0).kind());
            Assertions.assertFalse(logs.text().isBlank(), "the failure is still logged");
            Assertions.assertFalse(logs.text().contains("SECRET-PATH") || logs.text().contains("token=abc")
                    || logs.text().contains("runner.example"), "no IRI in the export's log: " + logs.text());
        }
    }

    private static final String LOG_MARKERS_URL = "http://user:PASSWORD-MARKER@runner.example/PATH-MARKER?token=TOKEN-MARKER";

    private void givenRunnerFailsWith(int status) throws IOException, InterruptedException {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn("BODY-MARKER");
        org.mockito.Mockito.lenient().when(response.headers()).thenReturn(java.net.http.HttpHeaders.of(
                Map.of("X-Secret", List.of("HEADER-MARKER")), (name, value) -> true));
        when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString()))).thenReturn(response);
    }

    private static void assertNoMarkerIsLogged(String logged) {
        for (String marker : List.of("PASSWORD-MARKER", "PATH-MARKER", "TOKEN-MARKER", "BODY-MARKER", "HEADER-MARKER", "runner.example")) {
            Assertions.assertFalse(logged.contains(marker), marker + " is logged: " + logged);
        }
    }

    @Test
    public void collectMarkerReferences_logsOnlyTheStatusForAnErrorAnswer() throws IOException, InterruptedException {
        givenSchemaWhoseRunnerIs(URI.create("schemaId"), LOG_MARKERS_URL);
        givenRunnerFailsWith(503);

        try (LogCapture logs = new LogCapture()) {
            collect(configStore.get());

            assertNoMarkerIsLogged(logs.text());
            Assertions.assertTrue(logs.text().contains("503"), "the status code is still logged: " + logs.text());
        }
    }

    @Test
    public void getSchema_withForceReloadKeepsTheEndpointAndTheCauseOfFailedDownloadInTheLogAndInTheException()
            throws IOException, InterruptedException {
        URI schemaId = URI.create("schemaId");
        givenSchemaWhoseRunnerIs(schemaId, "http://runner.example/schema");
        IOException failure = new IOException("cannot reach the runner");
        when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString()))).thenThrow(failure);

        try (LogCapture logs = new LogCapture()) {
            ApplicationTypeSchemaProcessingException thrown = assertThrows(ApplicationTypeSchemaProcessingException.class,
                    () -> service.getSchema(schemaId, true));

            // request processing is unchanged by the export: the endpoint and the cause are logged and wrapped
            Assertions.assertEquals("Failed to download application schema: http://runner.example/schema", thrown.getMessage());
            Assertions.assertSame(failure, thrown.getCause());
            Assertions.assertTrue(logs.text().contains("http://runner.example/schema"), "the endpoint is logged: " + logs.text());
        }
    }

    @Test
    public void getSchema_withForceReloadWrapsAnInterruptLikeAnyOtherFailureOfTheDownload() throws IOException, InterruptedException {
        URI schemaId = URI.create("schemaId");
        givenSchemaWhoseRunnerIs(schemaId, "http://runner.example/schema");
        when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString()))).thenThrow(new InterruptedException("interrupted"));

        try {
            ApplicationTypeSchemaProcessingException thrown = assertThrows(ApplicationTypeSchemaProcessingException.class,
                    () -> service.getSchema(schemaId, true));

            Assertions.assertTrue(thrown.getCause() instanceof InterruptedException, "the request path wraps the interrupt, as before");
        } finally {
            Thread.interrupted();
        }
    }

    private MarkerCollection collectAfterRunnerFailure() {
        URI schemaId = URI.create("schemaId");
        givenSchemaWhoseRunnerIs(schemaId, "http://runner.example/schema");
        return collect(configStore.get());
    }

    private static void assertSchemaUnavailable(MarkerCollection result) {
        Assertions.assertEquals(1, result.issues().size());
        Assertions.assertEquals(CollectionIssueKind.SCHEMA_UNAVAILABLE, result.issues().get(0).kind());
    }

    @ParameterizedTest
    @ValueSource(strings = {"connection failure", "timeout", "status 418", "body that is a JSON array", "body that is not JSON"})
    public void collectMarkerReferences_aRunnerFailureOfAnyCauseIsSchemaUnavailable(String cause) throws IOException, InterruptedException {
        switch (cause) {
            case "connection failure" -> when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                    .thenThrow(new java.net.ConnectException("Connection refused"));
            case "timeout" -> when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                    .thenThrow(new java.net.http.HttpTimeoutException("request timed out"));
            case "status 418" -> givenRunnerFailsWith(418);
            case "body that is a JSON array" -> givenRunnerAnswers("[1,2]");
            case "body that is not JSON" -> givenRunnerAnswers("this is not json");
            default -> throw new IllegalArgumentException(cause);
        }

        assertSchemaUnavailable(collectAfterRunnerFailure());
    }

    @Test
    public void getSchema_withForceReloadKeepsItsExceptionAndItsCause() throws IOException, InterruptedException {
        URI schemaId = URI.create("schemaId");
        givenSchemaWhoseRunnerIs(schemaId, "http://runner.example/schema");
        givenRunnerFailsWith(418);

        ApplicationTypeSchemaProcessingException thrown = assertThrows(ApplicationTypeSchemaProcessingException.class,
                () -> service.getSchema(schemaId, true));

        Assertions.assertTrue(thrown.getCause() instanceof com.epam.aidial.core.storage.http.HttpException, "the request path keeps its cause");
    }

    @Test
    public void collectMarkerReferences_withGivenConfigLooksTheSchemaUpThereAndNotInTheLiveStore() {
        URI schemaId = URI.create("schemaId");
        application.setApplicationTypeSchemaId(schemaId);
        application.setApplicationProperties(properties("first", "toolsets/platform/a"));
        Config snapshotConfig = mock(Config.class);
        when(snapshotConfig.getCustomApplicationSchema(schemaId)).thenReturn("""
                {"type":"object","properties":{"first":{"type":"string","dial:resource":true}}}""");

        MarkerCollection result = collect(snapshotConfig);

        Assertions.assertEquals(List.of(reference("/first", "toolsets/platform/a")), result.references());
        Assertions.assertTrue(result.issues().isEmpty());
        verifyNoInteractions(configStore);
        Assertions.assertEquals(0, service.schemaCacheSize());
    }

    @Test
    public void collectMarkerReferences_propagatesInterruptInsteadOfRecordingSchemaFailure() throws Exception {
        application.setApplicationTypeSchemaId(URI.create("schemaId"));
        String staticSchema = """
                {
                  "$schema": "https://dial.epam.com/application_type_schemas/schema#",
                  "$id": "https://mydial.epam.com/custom_application_schemas/specific_application_type",
                  "dial:applicationTypeSchemaEndpoint": "http://mydial.epam.com/schema"
                }""";
        when(config.getCustomApplicationSchema(any())).thenReturn(staticSchema);
        when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString()))).thenThrow(new InterruptedException("deadline"));

        try {
            Assertions.assertThrows(InterruptedException.class, () -> collect(config));
            Assertions.assertTrue(Thread.currentThread().isInterrupted(), "the flag is restored for the caller");
        } finally {
            Thread.interrupted();
        }
    }

    private static final String ROUTER_SCHEMA = """
            {
              "$schema": "https://dial.epam.com/application_type_schemas/schema#",
              "$id": "https://dial.test/router-type",
              "dial:applicationTypeDisplayName": "Router type",
              "properties": {
                "tier": {"type": "string", "dial:meta": {"dial:propertyKind": "server", "dial:propertyOrder": 1}},
                "preferredModels": {"type": "array", "items": {"type": "string"},
                  "dial:meta": {"dial:propertyKind": "client", "dial:propertyOrder": 2}}
              },
              "required": ["tier"]
            }
            """;
    private static final URI ROUTER_SCHEMA_ID = URI.create("https://dial.test/router-type");

    @Test
    public void validateProperties_unknownSchema() {
        List<ValidationWarning> warnings = new ArrayList<>();

        service.validateProperties(new Config(), ROUTER_SCHEMA_ID, Map.of("tier", "medium"), warnings);

        Assertions.assertEquals(1, warnings.size());
        Assertions.assertEquals("applicationTypeSchemaId", warnings.getFirst().getField());
        Assertions.assertEquals("Unknown application type schema: https://dial.test/router-type", warnings.getFirst().getMessage());
    }

    @Test
    public void validateProperties_invalidPropertiesJoinTheMessages() {
        List<ValidationWarning> warnings = new ArrayList<>();

        service.validateProperties(routerSchemaConfig(), ROUTER_SCHEMA_ID, Map.of("tier", 5, "preferredModels", "x"), warnings);

        Assertions.assertEquals(1, warnings.size());
        Assertions.assertEquals("applicationProperties", warnings.getFirst().getField());
        String message = warnings.getFirst().getMessage();
        Assertions.assertTrue(message.contains("tier") && message.contains("preferredModels") && message.contains(", "), message);
    }

    @Test
    public void validateProperties_validPropertiesLeaveTheCacheUntouched() {
        List<ValidationWarning> warnings = new ArrayList<>();

        service.validateProperties(routerSchemaConfig(), ROUTER_SCHEMA_ID, Map.of("tier", "medium"), warnings);

        Assertions.assertTrue(warnings.isEmpty(), warnings.toString());
        Assertions.assertEquals(0, service.schemaCacheSize());
    }

    @Test
    public void isSchemaReady_onlyWhileTheCompiledEntryMatchesTheConfig() {
        Router router = new Router();
        router.setName("r");
        router.setApplicationTypeSchemaId(ROUTER_SCHEMA_ID);
        router.setApplicationProperties(Map.of("tier", "medium"));
        when(configStore.get()).thenReturn(routerSchemaConfig());
        Assertions.assertFalse(service.isSchemaReady(ROUTER_SCHEMA_ID));

        service.routerPropertiesHeader(router);
        Assertions.assertTrue(service.isSchemaReady(ROUTER_SCHEMA_ID));

        Config changed = new Config();
        changed.setApplicationTypeSchemas(Map.of(ROUTER_SCHEMA_ID.toString(), ROUTER_SCHEMA.replace("Router type", "Other")));
        when(configStore.get()).thenReturn(changed);
        Assertions.assertFalse(service.isSchemaReady(ROUTER_SCHEMA_ID));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void validateProperties_sameTextAsTheCachedEntryIsNotDownloadedAgain() throws Exception {
        String source = """
                {"$schema": "https://dial.epam.com/application_type_schemas/schema#", "$id": "https://dial.test/router-type",
                 "dial:applicationTypeSchemaEndpoint": "http://dial.test/schema"}
                """;
        Config live = new Config();
        live.setApplicationTypeSchemas(Map.of(ROUTER_SCHEMA_ID.toString(), source));
        when(configStore.get()).thenReturn(live);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(ROUTER_SCHEMA);
        when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString()))).thenReturn(response);
        service.getSchema(ROUTER_SCHEMA_ID, false);
        List<ValidationWarning> warnings = new ArrayList<>();

        service.validateProperties(live, ROUTER_SCHEMA_ID, Map.of("tier", "medium"), warnings);
        service.validateProperties(live, ROUTER_SCHEMA_ID, Map.of("tier", 5), warnings);

        Assertions.assertEquals(1, warnings.size(), warnings.toString());
        verify(httpClient, times(1)).send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString()));
    }

    @Test
    public void consumeMetadataProperties_bySchemaIdReturnsTheDeclaredPropertiesOnly() throws Exception {
        when(configStore.get()).thenReturn(config);
        when(config.getCustomApplicationSchema(ROUTER_SCHEMA_ID)).thenReturn(ROUTER_SCHEMA);
        Map<String, Object> received = new HashMap<>();
        boolean[] append = {false};

        service.consumeMetadataProperties(ROUTER_SCHEMA_ID,
                Map.of("tier", "medium", "preferredModels", List.of("gpt-4"), "undeclared", 1), (properties, header) -> {
                    received.putAll(properties);
                    append[0] = header;
                });

        Assertions.assertEquals(Map.of("tier", "medium", "preferredModels", List.of("gpt-4")), received);
        Assertions.assertTrue(append[0]);
    }

    @Test
    public void resolveSchema_changedTextInTheConfigRebuildsTheEntry() {
        when(configStore.get()).thenReturn(config);
        when(config.getCustomApplicationSchema(ROUTER_SCHEMA_ID)).thenReturn(ROUTER_SCHEMA);
        Assertions.assertEquals(ROUTER_SCHEMA, service.getSchema(ROUTER_SCHEMA_ID, false));

        String changed = ROUTER_SCHEMA.replace("\"Router type\"", "\"Router type v2\"");
        when(config.getCustomApplicationSchema(ROUTER_SCHEMA_ID)).thenReturn(changed);

        Assertions.assertEquals(changed, service.getSchema(ROUTER_SCHEMA_ID, false));
        Assertions.assertEquals(1, service.schemaCacheSize());
    }

    @Test
    public void resolveSchema_deletedSchemaDropsTheEntry() {
        when(configStore.get()).thenReturn(config);
        when(config.getCustomApplicationSchema(ROUTER_SCHEMA_ID)).thenReturn(ROUTER_SCHEMA);
        service.getSchema(ROUTER_SCHEMA_ID, false);

        when(config.getCustomApplicationSchema(ROUTER_SCHEMA_ID)).thenReturn(null);

        Assertions.assertNull(service.getSchema(ROUTER_SCHEMA_ID, false));
        Assertions.assertEquals(0, service.schemaCacheSize());
    }

    @Test
    public void resolveSchema_unchangedTextDoesNotDownloadAgain() throws Exception {
        when(configStore.get()).thenReturn(config);
        String withEndpoint = ROUTER_SCHEMA.replace("\"dial:applicationTypeDisplayName\"",
                "\"dial:applicationTypeSchemaEndpoint\": \"http://runner/schema\", \"dial:applicationTypeDisplayName\"");
        when(config.getCustomApplicationSchema(ROUTER_SCHEMA_ID)).thenReturn(withEndpoint);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"properties\": {}}");
        when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString()))).thenReturn(response);

        service.getSchema(ROUTER_SCHEMA_ID, false);
        service.getSchema(ROUTER_SCHEMA_ID, false);

        verify(httpClient, times(1)).send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString()));
    }

    private static Config routerSchemaConfig() {
        Config config = new Config();
        config.setApplicationTypeSchemas(Map.of(ROUTER_SCHEMA_ID.toString(), ROUTER_SCHEMA));
        return config;
    }
}
