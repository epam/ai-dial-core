package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.credentials.data.credentials.AuthorizationHeader;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.credentials.service.AuthorizationHeaderProvider;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.service.ApplicationSchemaService;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.epam.aidial.core.server.Proxy.HEADER_APPLICATION_ID;
import static com.epam.aidial.core.server.Proxy.HEADER_APPLICATION_PROPERTIES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpUpstreamAuthInjectorTest {

    @Mock
    private Proxy proxy;
    @Mock
    private ProxyContext context;
    @Mock
    private AuthSettingsResolver authSettingsResolver;
    @Mock
    private AuthorizationHeaderProvider authorizationHeaderProvider;
    @Mock
    private ApiKeyStore apiKeyStore;
    @Mock
    private ApplicationSchemaService applicationSchemaService;
    @Mock
    private CredentialsLocator credentialsLocator;

    private McpUpstreamAuthInjector injector;

    @BeforeEach
    void setup() {
        when(proxy.getAuthSettingsResolver()).thenReturn(authSettingsResolver);
        when(proxy.getAuthorizationHeaderProvider()).thenReturn(authorizationHeaderProvider);
        when(proxy.getApiKeyStore()).thenReturn(apiKeyStore);
        when(proxy.getApplicationSchemaService()).thenReturn(applicationSchemaService);
        injector = new McpUpstreamAuthInjector(proxy);
    }

    // --- ToolSet ---

    @Test
    void toolSet_noCredentials_noPerRequestKey_emptyHeaders() {
        ToolSet toolSet = new ToolSet();
        toolSet.setForwardPerRequestKey(false);
        when(authSettingsResolver.resolve(toolSet, context)).thenReturn(mock(ResourceAuthSettings.class));
        when(authorizationHeaderProvider.createAuthorizationHeader(any(), any(), any())).thenReturn(null);

        Map<String, String> headers = collect(toolSet);

        assertTrue(headers.isEmpty());
    }

    @Test
    void toolSet_withCredentials_addsAuthorizationHeader() {
        ToolSet toolSet = new ToolSet();
        toolSet.setForwardPerRequestKey(false);
        when(authSettingsResolver.resolve(toolSet, context)).thenReturn(mock(ResourceAuthSettings.class));
        AuthorizationHeader authHeader = mock(AuthorizationHeader.class);
        when(authHeader.getHeaderName()).thenReturn("Authorization");
        when(authHeader.getHeaderValue()).thenReturn("Bearer token123");
        when(authorizationHeaderProvider.createAuthorizationHeader(any(), any(), any())).thenReturn(authHeader);

        Map<String, String> headers = collect(toolSet);

        assertEquals("Bearer token123", headers.get("Authorization"));
    }

    @Test
    void toolSet_forwardPerRequestKey_addsApiKeyHeader() {
        ToolSet toolSet = new ToolSet();
        toolSet.setForwardPerRequestKey(true);
        when(authSettingsResolver.resolve(toolSet, context)).thenReturn(mock(ResourceAuthSettings.class));
        when(authorizationHeaderProvider.createAuthorizationHeader(any(), any(), any())).thenReturn(null);
        mockPerRequestKeyAssignment("per-request-key-001");

        Map<String, String> headers = collect(toolSet);

        assertEquals("per-request-key-001", headers.get(Proxy.HEADER_API_KEY));
    }

    @Test
    void toolSet_noForwardPerRequestKey_noApiKeyHeader() {
        ToolSet toolSet = new ToolSet();
        toolSet.setForwardPerRequestKey(false);
        when(authSettingsResolver.resolve(toolSet, context)).thenReturn(mock(ResourceAuthSettings.class));
        when(authorizationHeaderProvider.createAuthorizationHeader(any(), any(), any())).thenReturn(null);

        Map<String, String> headers = collect(toolSet);

        assertFalse(headers.containsKey(Proxy.HEADER_API_KEY));
    }

    // --- Application ---

    @Test
    void application_alwaysAddsApplicationIdHeader() {
        Application app = appWithMcp(false, Application.McpConfigDelivery.META);
        app.setName("my-app");

        Map<String, String> headers = collect(app);

        assertEquals("my-app", headers.get(HEADER_APPLICATION_ID));
    }

    @Test
    void application_forwardPerRequestKey_addsApiKeyHeader() {
        Application app = appWithMcp(true, Application.McpConfigDelivery.META);
        app.setName("my-app");
        mockPerRequestKeyAssignment("per-request-key-app");

        Map<String, String> headers = collect(app);

        assertEquals("per-request-key-app", headers.get(Proxy.HEADER_API_KEY));
    }

    @Test
    void application_noForwardPerRequestKey_noApiKeyHeader() {
        Application app = appWithMcp(false, Application.McpConfigDelivery.META);
        app.setName("my-app");

        Map<String, String> headers = collect(app);

        assertFalse(headers.containsKey(Proxy.HEADER_API_KEY));
    }

    @Test
    void application_configDeliveryHeader_addsPropertiesHeader() {
        Application app = appWithMcp(false, Application.McpConfigDelivery.HEADER);
        app.setName("my-app");
        doAnswer(inv -> {
            ApplicationSchemaService.MetadataPropertiesConsumer consumer = inv.getArgument(1);
            consumer.accept(Map.of("key", "value"), true);
            return null;
        }).when(applicationSchemaService).consumeMetadataProperties(eq(app), any());

        Map<String, String> headers = collect(app);

        assertNotNull(headers.get(HEADER_APPLICATION_PROPERTIES));
        assertTrue(headers.get(HEADER_APPLICATION_PROPERTIES).contains("key"));
    }

    @Test
    void application_configDeliveryHeader_appendFalse_noPropertiesHeader() {
        Application app = appWithMcp(false, Application.McpConfigDelivery.HEADER);
        app.setName("my-app");
        doAnswer(inv -> {
            ApplicationSchemaService.MetadataPropertiesConsumer consumer = inv.getArgument(1);
            consumer.accept(Map.of("key", "value"), false);
            return null;
        }).when(applicationSchemaService).consumeMetadataProperties(eq(app), any());

        Map<String, String> headers = collect(app);

        assertFalse(headers.containsKey(HEADER_APPLICATION_PROPERTIES));
    }

    @Test
    void application_configDeliveryMeta_noPropertiesHeader() {
        Application app = appWithMcp(false, Application.McpConfigDelivery.META);
        app.setName("my-app");

        collect(app);

        verify(applicationSchemaService, never()).consumeMetadataProperties(any(), any());
    }

    // --- helpers ---

    private Map<String, String> collect(ToolSet toolSet) {
        Map<String, String> headers = new LinkedHashMap<>();
        injector.inject(headers::put, toolSet, context, credentialsLocator);
        return headers;
    }

    private Map<String, String> collect(Application app) {
        Map<String, String> headers = new LinkedHashMap<>();
        injector.inject(headers::put, app, context);
        return headers;
    }

    private void mockPerRequestKeyAssignment(String key) {
        HttpServerRequest httpRequest = mock(HttpServerRequest.class);
        lenient().when(httpRequest.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        lenient().when(context.getRequest()).thenReturn(httpRequest);
        ApiKeyData existingKeyData = mock(ApiKeyData.class);
        lenient().when(context.getApiKeyData()).thenReturn(existingKeyData);
        lenient().when(existingKeyData.getHttpHeaders()).thenReturn(Map.of());
        lenient().when(existingKeyData.getPerRequestKey()).thenReturn(null);
        doAnswer(inv -> {
            ApiKeyData keyData = inv.getArgument(0);
            keyData.setPerRequestKey(key);
            return null;
        }).when(apiKeyStore).assignPerRequestApiKey(any());
    }

    private static Application appWithMcp(boolean forwardPerRequestKey,
                                          Application.McpConfigDelivery configDelivery) {
        Application.Mcp mcp = new Application.Mcp();
        mcp.setForwardPerRequestKey(forwardPerRequestKey);
        mcp.setConfigDelivery(configDelivery);
        Application app = new Application();
        app.setMcp(mcp);
        return app;
    }
}
