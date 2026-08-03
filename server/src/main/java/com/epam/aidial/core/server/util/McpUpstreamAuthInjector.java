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

import java.util.function.BiConsumer;

import static com.epam.aidial.core.server.Proxy.HEADER_APPLICATION_ID;
import static com.epam.aidial.core.server.Proxy.HEADER_APPLICATION_PROPERTIES;

/**
 * Transport-agnostic auth injector for outgoing MCP upstream requests.
 * Accepts a {@code BiConsumer<String, String>} header setter so the same logic works
 * with both Vert.x {@code HttpClientRequest} and Java {@code HttpRequest.Builder}.
 */
public class McpUpstreamAuthInjector {

    private final AuthSettingsResolver authSettingsResolver;
    private final AuthorizationHeaderProvider authorizationHeaderProvider;
    private final ApiKeyStore apiKeyStore;
    private final ApplicationSchemaService applicationSchemaService;

    public McpUpstreamAuthInjector(Proxy proxy) {
        this.authSettingsResolver = proxy.getAuthSettingsResolver();
        this.authorizationHeaderProvider = proxy.getAuthorizationHeaderProvider();
        this.apiKeyStore = proxy.getApiKeyStore();
        this.applicationSchemaService = proxy.getApplicationSchemaService();
    }

    /**
     * Injects auth headers for a ToolSet upstream.
     * Handles OAuth/API-key credentials and per-request DIAL key forwarding.
     */
    public void inject(BiConsumer<String, String> headers, ToolSet toolSet,
                       ProxyContext context, CredentialsLocator credentialsLocator) {
        ResourceAuthSettings authSettings = authSettingsResolver.resolve(toolSet, context);
        AuthorizationHeader authHeader = authorizationHeaderProvider.createAuthorizationHeader(
                credentialsLocator, authSettings, context.getInitiatorId());
        if (authHeader != null) {
            headers.accept(authHeader.getHeaderName(), authHeader.getHeaderValue());
        }
        if (toolSet.isForwardPerRequestKey()) {
            headers.accept(Proxy.HEADER_API_KEY, assignPerRequestKey(context));
        }
    }

    /**
     * Injects auth headers for an Application upstream.
     * Handles per-request DIAL key forwarding and config-delivery-via-header.
     */
    public void inject(BiConsumer<String, String> headers, Application app, ProxyContext context) {
        headers.accept(HEADER_APPLICATION_ID, app.getName());
        Application.Mcp mcp = app.getMcp();
        if (mcp.isForwardPerRequestKey()) {
            headers.accept(Proxy.HEADER_API_KEY, assignPerRequestKey(context));
        }
        if (mcp.getConfigDelivery() == Application.McpConfigDelivery.HEADER) {
            applicationSchemaService.consumeMetadataProperties(app, (properties, appendHeader) -> {
                if (appendHeader) {
                    headers.accept(HEADER_APPLICATION_PROPERTIES,
                            ProxyUtil.MAPPER.writeValueAsString(properties));
                }
            });
        }
    }

    private String assignPerRequestKey(ProxyContext context) {
        ApiKeyData keyData = new ApiKeyData();
        context.setProxyApiKeyData(keyData);
        ApiKeyData.initFromContext(keyData, context);
        apiKeyStore.assignPerRequestApiKey(keyData);
        return keyData.getPerRequestKey();
    }
}
