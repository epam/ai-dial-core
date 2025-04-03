package com.epam.aidial.core.server.function;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.service.ResourceService;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class CollectRequestApplicationFilesFnTest {

    @Mock
    private Proxy proxy;

    @Mock
    private ProxyContext context;

    @Mock
    private Config config;

    @Mock
    private AccessService accessService;

    @Mock
    private ResourceService resourceService;


    @InjectMocks
    private CollectRequestApplicationFilesFn fn;

    private Application application;
    private ObjectNode tree;

    private final String schema = """
            {
              "$schema": "https://dial.epam.com/application_type_schemas/schema#",
              "$id": "https://mydial.epam.com/custom_application_schemas/specific_application_type",
              "dial:applicationTypeEditorUrl": "https://mydial.epam.com/specific_application_type_editor",
              "dial:applicationTypeDisplayName": "Specific Application Type",
              "dial:applicationTypeCompletionEndpoint": "http://specific_application_service/opeani/v1/completion",
              "properties": {
                "clientFile": {
                  "type": "string",
                  "format": "dial-file-encoded",
                  "dial:meta": {
                    "dial:propertyKind": "client",
                    "dial:propertyOrder": 1
                  },
                  "dial:file": true
                },
                "serverFile": {
                  "type": "string",
                  "format": "dial-file-encoded",
                  "dial:meta": {
                    "dial:propertyKind": "server",
                    "dial:propertyOrder": 2
                  },
                  "dial:file": true
                }
              },
              "required": [
                "clientFile"
              ]
            }""";

    @BeforeEach
    void setUp() {
        application = new Application();
        tree = JsonNodeFactory.instance.objectNode();
    }

    @Test
    void apply_doesNotAppendsFilesToApiKeyData_whenDeploymentIsNotApplication() {
        Deployment deployment = mock(Deployment.class);
        when(context.getDeployment()).thenReturn(deployment);

        assertFalse(fn.apply(tree));
        verify(proxy, never()).getApiKeyStore();
        verify(context, never()).getProxyApiKeyData();
    }

    @Test
    void apply_doesNotAppendsFilesToApiKeyData_whenApplicationHasNoCustomSchemaId() {
        when(context.getDeployment()).thenReturn(application);
        application.setApplicationTypeSchemaId(null);

        assertFalse(fn.apply(tree));
        verify(proxy, never()).getApiKeyStore();
        verify(context, never()).getProxyApiKeyData();
    }

    @Test
    void apply_appendsFilesToApiKeyData_whenApplicationHasCustomSchemaId() {
        when(proxy.getAccessService()).thenReturn(accessService);
        when(proxy.getResourceService()).thenReturn(resourceService);
        when(context.getProxyApiKeyData()).thenReturn(new ApiKeyData());
        when(context.getConfig()).thenReturn(config);
        String serverFile = "files/public/valid-file-path/valid-sub-path/valid%20file%20name2.ext";
        when(context.getDeployment()).thenReturn(application);
        application.setApplicationTypeSchemaId(URI.create("customSchemaId"));
        Map<String, Object> customProps = new HashMap<>();
        customProps.put("clientFile", "files/public/valid-file-path/valid-sub-path/valid%20file%20name1.ext");
        customProps.put("serverFile", serverFile);
        application.setApplicationProperties(customProps);
        when(config.getCustomApplicationSchema(eq(URI.create("customSchemaId")))).thenReturn(schema);
        when(accessService.hasReadAccess(any(), any())).thenReturn(true);
        when(resourceService.hasResource(any())).thenReturn(true);
        ApiKeyData apiKeyData = new ApiKeyData();
        when(context.getProxyApiKeyData()).thenReturn(apiKeyData);

        boolean result = fn.apply(tree);

        assertFalse(result);
        assertNotNull(apiKeyData.getAttachedFiles().get(serverFile));
        assertEquals(1, apiKeyData.getAttachedFiles().size());
    }

    @Test
    void apply_appendsFilesToApiKeyData_whenApplicationHasCustomSchemaId_AndSchemaDescribesArray() {
        when(proxy.getAccessService()).thenReturn(accessService);
        when(proxy.getResourceService()).thenReturn(resourceService);
        when(context.getProxyApiKeyData()).thenReturn(new ApiKeyData());
        when(context.getConfig()).thenReturn(config);
        String[] ragFiles = {
                "files/public/valid-file-path/valid-sub-path/valid%20file%20name1.ext",
                "files/public/valid-file-path/valid-sub-path/valid%20file%20name2.ext"
        };
        when(context.getDeployment()).thenReturn(application);
        application.setApplicationTypeSchemaId(URI.create("customSchemaId"));
        Map<String, Object> customProps = new HashMap<>();
        customProps.put("rag_files", ragFiles);
        application.setApplicationProperties(customProps);

        String schemaWithArray = """
                {
                    "$schema": "https://dial.epam.com/application_type_schemas/schema#",
                    "$id": "https://dial.epam.com/custom_application_schemas/dial-rag-app-runner",
                    "dial:applicationTypeDisplayName": "DIAL RAG",
                    "dial:applicationTypeCompletionEndpoint": "https://somewhere.com/openai/deployments/dial-rag-app-runner/chat/completions",
                    "dial:applicationTypeEditorUrl": "https://somewhere.com",
                    "$defs": {},
                    "properties": {
                        "rag_files": {
                            "type": "array",
                            "items": {
                                "type": "string",
                                "dial:file": true,
                                "format": "dial-file-encoded"
                            },
                            "dial:meta": {
                                "dial:propertyKind": "server",
                                "dial:propertyOrder": 1
                            }
                        }
                    },
                    "required": [
                        "rag_files"
                    ]
                }
                """;
        when(config.getCustomApplicationSchema(eq(URI.create("customSchemaId")))).thenReturn(schemaWithArray);
        when(accessService.hasReadAccess(any(), any())).thenReturn(true);
        when(resourceService.hasResource(any())).thenReturn(true);
        ApiKeyData apiKeyData = new ApiKeyData();
        when(context.getProxyApiKeyData()).thenReturn(apiKeyData);

        boolean result = fn.apply(tree);

        assertFalse(result);
        assertEquals(ragFiles.length, apiKeyData.getAttachedFiles().size());
        for (String ragFile : ragFiles) {
            assertNotNull(apiKeyData.getAttachedFiles().get(ragFile));
        }
    }

    @Test
    void apply_throws_whenResourceServiceHasNoResource() {
        when(proxy.getResourceService()).thenReturn(resourceService);
        when(context.getConfig()).thenReturn(config);
        String serverFile = "files/public/valid-file-path/valid-sub-path/valid%20file%20name2.ext";
        when(context.getDeployment()).thenReturn(application);
        application.setApplicationTypeSchemaId(URI.create("customSchemaId"));
        Map<String, Object> customProps = new HashMap<>();
        customProps.put("clientFile", "files/public/valid-file-path/valid-sub-path/valid%20file%20name1.ext");
        customProps.put("serverFile", serverFile);
        application.setApplicationProperties(customProps);
        when(config.getCustomApplicationSchema(eq(URI.create("customSchemaId")))).thenReturn(schema);
        when(resourceService.hasResource(any())).thenReturn(false); //Has No Resource

        Assertions.assertThrows(HttpException.class, () -> fn.apply(tree));
    }

    @Test
    void apply_throws_whenAccessServiceHasNoReadAccess() {
        when(proxy.getAccessService()).thenReturn(accessService);
        when(proxy.getResourceService()).thenReturn(resourceService);
        when(context.getProxyApiKeyData()).thenReturn(new ApiKeyData());
        when(context.getConfig()).thenReturn(config);
        String serverFile = "files/public/valid-file-path/valid-sub-path/valid%20file%20name2.ext";
        when(context.getDeployment()).thenReturn(application);
        application.setApplicationTypeSchemaId(URI.create("customSchemaId"));
        Map<String, Object> customProps = new HashMap<>();
        customProps.put("clientFile", "files/public/valid-file-path/valid-sub-path/valid%20file%20name1.ext");
        customProps.put("serverFile", serverFile);
        application.setApplicationProperties(customProps);
        when(config.getCustomApplicationSchema(eq(URI.create("customSchemaId")))).thenReturn(schema);
        when(accessService.hasReadAccess(any(), any())).thenReturn(false); //Has no Read Access
        when(resourceService.hasResource(any())).thenReturn(true);
        ApiKeyData apiKeyData = new ApiKeyData();
        when(context.getProxyApiKeyData()).thenReturn(apiKeyData);

        Assertions.assertThrows(HttpException.class, () -> fn.apply(tree));
    }
}