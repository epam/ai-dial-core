package com.epam.aidial.core.server.function;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.function.request.ChatCompletionRequest;
import com.epam.aidial.core.server.function.request.RequestObject;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.service.ApplicationSchemaService;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
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

    @Mock
    private ApplicationSchemaService applicationSchemaService;


    @InjectMocks
    private CollectRequestApplicationFilesFn fn;

    private Application application;
    private RequestObject request;

    @BeforeEach
    void setUp() {
        application = new Application();
        request = new ChatCompletionRequest(JsonNodeFactory.instance.objectNode());
    }

    @Test
    void apply_doesNotAppendsFilesToApiKeyData_whenDeploymentIsNotApplication() {
        Deployment deployment = mock(Deployment.class);
        when(context.getDeployment()).thenReturn(deployment);

        assertFalse(fn.apply(request));
        verify(proxy, never()).getApiKeyStore();
        verify(context, never()).getProxyApiKeyData();
    }

    @Test
    void apply_doesNotAppendsFilesToApiKeyData_whenApplicationHasNoCustomSchemaId() {
        when(context.getDeployment()).thenReturn(application);
        application.setApplicationTypeSchemaId(null);

        assertFalse(fn.apply(request));
        verify(proxy, never()).getApiKeyStore();
        verify(context, never()).getProxyApiKeyData();
    }

    @Test
    void apply_appendsFilesToApiKeyData_whenApplicationHasCustomSchemaId() {
        when(context.getProxyApiKeyData()).thenReturn(new ApiKeyData());
        String serverFile = "files/public/valid-file-path/valid-sub-path/valid%20file%20name2.ext";
        when(context.getDeployment()).thenReturn(application);
        application.setApplicationTypeSchemaId(URI.create("customSchemaId"));
        Map<String, Object> customProps = new HashMap<>();
        customProps.put("clientFile", "files/public/valid-file-path/valid-sub-path/valid%20file%20name1.ext");
        customProps.put("serverFile", serverFile);
        application.setApplicationProperties(customProps);
        when(accessService.hasReadAccess(any(), any())).thenReturn(true);
        ApiKeyData apiKeyData = new ApiKeyData();
        when(context.getProxyApiKeyData()).thenReturn(apiKeyData);
        when(proxy.getApplicationSchemaService()).thenReturn(applicationSchemaService);
        when(proxy.getAccessService()).thenReturn(accessService);
        ResourceDescriptor file = mock(ResourceDescriptor.class);
        when(file.getUrl()).thenReturn(serverFile);
        when(applicationSchemaService.getFiles(eq(application))).thenReturn(List.of(file));

        boolean result = fn.apply(request);

        assertFalse(result);
        assertNotNull(apiKeyData.getAttachedFiles().get(serverFile));
        assertEquals(1, apiKeyData.getAttachedFiles().size());
    }

    @Test
    void apply_appendsFilesToApiKeyData_whenApplicationHasCustomSchemaId_AndSchemaDescribesArray() {
        when(context.getProxyApiKeyData()).thenReturn(new ApiKeyData());
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
        when(accessService.hasReadAccess(any(), any())).thenReturn(true);
        ApiKeyData apiKeyData = new ApiKeyData();
        when(context.getProxyApiKeyData()).thenReturn(apiKeyData);
        when(proxy.getApplicationSchemaService()).thenReturn(applicationSchemaService);
        when(proxy.getAccessService()).thenReturn(accessService);
        ResourceDescriptor file1 = mock(ResourceDescriptor.class);
        when(file1.getUrl()).thenReturn(ragFiles[0]);
        ResourceDescriptor file2 = mock(ResourceDescriptor.class);
        when(file2.getUrl()).thenReturn(ragFiles[1]);
        when(applicationSchemaService.getFiles(eq(application))).thenReturn(List.of(file1, file2));

        boolean result = fn.apply(request);

        assertFalse(result);
        assertEquals(ragFiles.length, apiKeyData.getAttachedFiles().size());
        for (String ragFile : ragFiles) {
            assertNotNull(apiKeyData.getAttachedFiles().get(ragFile));
        }
    }

    @Test
    void apply_appendsPromptsToApiKeyData_whenApplicationHasCustomSchemaId() {
        String promptUrl = "prompts/bucket/my-prompt";
        when(context.getDeployment()).thenReturn(application);
        application.setApplicationTypeSchemaId(URI.create("customSchemaId"));
        Map<String, Object> customProps = new HashMap<>();
        customProps.put("prompt", Map.of("name", "my-prompt", "dial_id", promptUrl));
        application.setApplicationProperties(customProps);
        when(accessService.hasReadAccess(any(), any())).thenReturn(true);
        ApiKeyData apiKeyData = new ApiKeyData();
        when(context.getProxyApiKeyData()).thenReturn(apiKeyData);
        when(proxy.getApplicationSchemaService()).thenReturn(applicationSchemaService);
        when(proxy.getAccessService()).thenReturn(accessService);
        ResourceDescriptor prompt = mock(ResourceDescriptor.class);
        when(prompt.getUrl()).thenReturn(promptUrl);
        when(applicationSchemaService.getPrompts(eq(application))).thenReturn(List.of(prompt));

        boolean result = fn.apply(request);

        assertFalse(result);
        assertNotNull(apiKeyData.getAttachedPrompts().get(promptUrl));
        assertEquals(1, apiKeyData.getAttachedPrompts().size());
    }

    @Test
    void apply_throws_whenAccessServiceHasNoReadAccessToPrompt() {
        String promptUrl = "prompts/bucket/my-prompt";
        when(context.getDeployment()).thenReturn(application);
        application.setApplicationTypeSchemaId(URI.create("customSchemaId"));
        Map<String, Object> customProps = new HashMap<>();
        customProps.put("prompt", Map.of("name", "my-prompt", "dial_id", promptUrl));
        application.setApplicationProperties(customProps);
        when(proxy.getApplicationSchemaService()).thenReturn(applicationSchemaService);
        when(proxy.getAccessService()).thenReturn(accessService);
        ResourceDescriptor prompt = mock(ResourceDescriptor.class);
        when(applicationSchemaService.getPrompts(eq(application))).thenReturn(List.of(prompt));
        when(accessService.hasReadAccess(any(), any())).thenReturn(false);
        ApiKeyData apiKeyData = new ApiKeyData();
        when(context.getProxyApiKeyData()).thenReturn(apiKeyData);

        Assertions.assertThrows(HttpException.class, () -> fn.apply(request));
    }

    @Test
    void apply_appendsSkillsToApiKeyData_whenApplicationHasCustomSchemaId() {
        String skillUrl = "skills/bucket/my-skill";
        when(context.getDeployment()).thenReturn(application);
        application.setApplicationTypeSchemaId(URI.create("customSchemaId"));
        Map<String, Object> customProps = new HashMap<>();
        customProps.put("skill", Map.of("name", "my-skill", "dial_id", skillUrl));
        application.setApplicationProperties(customProps);
        when(accessService.hasReadAccess(any(), any())).thenReturn(true);
        ApiKeyData apiKeyData = new ApiKeyData();
        when(context.getProxyApiKeyData()).thenReturn(apiKeyData);
        when(proxy.getApplicationSchemaService()).thenReturn(applicationSchemaService);
        when(proxy.getAccessService()).thenReturn(accessService);
        ResourceDescriptor skill = mock(ResourceDescriptor.class);
        when(skill.getUrl()).thenReturn(skillUrl);
        when(applicationSchemaService.getSkills(eq(application))).thenReturn(List.of(skill));

        boolean result = fn.apply(request);

        assertFalse(result);
        assertNotNull(apiKeyData.getAttachedSkills().get(skillUrl));
        assertEquals(1, apiKeyData.getAttachedSkills().size());
    }

    @Test
    void apply_throws_whenAccessServiceHasNoReadAccessToSkill() {
        String skillUrl = "skills/bucket/my-skill";
        when(context.getDeployment()).thenReturn(application);
        application.setApplicationTypeSchemaId(URI.create("customSchemaId"));
        Map<String, Object> customProps = new HashMap<>();
        customProps.put("skill", Map.of("name", "my-skill", "dial_id", skillUrl));
        application.setApplicationProperties(customProps);
        when(proxy.getApplicationSchemaService()).thenReturn(applicationSchemaService);
        when(proxy.getAccessService()).thenReturn(accessService);
        ResourceDescriptor skill = mock(ResourceDescriptor.class);
        when(applicationSchemaService.getSkills(eq(application))).thenReturn(List.of(skill));
        when(accessService.hasReadAccess(any(), any())).thenReturn(false);
        ApiKeyData apiKeyData = new ApiKeyData();
        when(context.getProxyApiKeyData()).thenReturn(apiKeyData);

        Assertions.assertThrows(HttpException.class, () -> fn.apply(request));
    }

    @Test
    void apply_throws_whenAccessServiceHasNoReadAccess() {
        when(context.getProxyApiKeyData()).thenReturn(new ApiKeyData());
        String serverFile = "files/public/valid-file-path/valid-sub-path/valid%20file%20name2.ext";
        when(context.getDeployment()).thenReturn(application);
        application.setApplicationTypeSchemaId(URI.create("customSchemaId"));
        Map<String, Object> customProps = new HashMap<>();
        customProps.put("clientFile", "files/public/valid-file-path/valid-sub-path/valid%20file%20name1.ext");
        customProps.put("serverFile", serverFile);
        application.setApplicationProperties(customProps);
        when(proxy.getApplicationSchemaService()).thenReturn(applicationSchemaService);
        when(proxy.getAccessService()).thenReturn(accessService);
        ResourceDescriptor file = mock(ResourceDescriptor.class);
        when(applicationSchemaService.getFiles(eq(application))).thenReturn(List.of(file));
        when(accessService.hasReadAccess(any(), any())).thenReturn(false); //Has no Read Access
        ApiKeyData apiKeyData = new ApiKeyData();
        when(context.getProxyApiKeyData()).thenReturn(apiKeyData);

        Assertions.assertThrows(HttpException.class, () -> fn.apply(request));
    }
}