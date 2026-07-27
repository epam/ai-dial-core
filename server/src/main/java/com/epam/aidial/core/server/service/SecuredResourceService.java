package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.SecuredResource;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.credentials.data.credentials.CredentialsDescriptor;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.credentials.data.credentials.ResourceSignInRequest;
import com.epam.aidial.core.credentials.data.credentials.ResourceSignOutRequest;
import com.epam.aidial.core.credentials.service.ResourceCredentialsService;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.util.CredentialsLocatorFactory;
import com.epam.aidial.core.server.util.McpClientUtils;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceType;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.util.UrlUtil;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.time.Duration;

@Slf4j
@AllArgsConstructor
public class SecuredResourceService {

    // deliberately not the client idleTimeout (5 min in production) — sign-in is interactive
    private static final Duration MCP_PROBE_TIMEOUT = Duration.ofSeconds(20);

    private final ResourceCredentialsService resourceCredentialsService;
    private final McpHttpClientBuilderService mcpHttpClientBuilderService;

    public void signIn(ProxyContext context, SecuredResource securedResource, ResourceSignInRequest request) {
        if (AuthenticationType.API_KEY.equals(request.getAuthenticationType())) {
            validateApiKey(securedResource, request.getApiKey());
        }
        CredentialsLocator credentialsLocator = createCredentialsLocator(context, securedResource, request.getUrl());
        CredentialsDescriptor credentialsDescriptor = credentialsLocator.getCredentialsDescriptors().get(request.getCredentialsLevel());
        resourceCredentialsService.addResourceCredentials(credentialsDescriptor, securedResource.getAuthSettings(),
                request, context.getInitiatorId());
    }

    public boolean signOut(ProxyContext context, SecuredResource securedResource, ResourceSignOutRequest request) {
        CredentialsLocator credentialsLocator = createCredentialsLocator(context, securedResource, request.getUrl());
        return resourceCredentialsService.deleteResourceCredentials(credentialsLocator, request, context.getInitiatorId());
    }

    private static CredentialsLocator createCredentialsLocator(ProxyContext context, SecuredResource securedResource, String resourceUrl) {
        ResourceType resourceType = resolveResourceType(securedResource);
        return CredentialsLocatorFactory.fromAnyUrl(UrlUtil.encodePath(resourceUrl), context, resourceType);
    }

    private static ResourceType resolveResourceType(SecuredResource resource) {
        if (resource instanceof ToolSet) {
            return ResourceTypes.TOOL_SET;
        }
        throw new IllegalArgumentException("Unsupported secured resource type: " + resource.getClass().getSimpleName());
    }

    /**
     * Validates an API key before it is stored at sign-in: rejects blank/malformed keys, then probes
     * the resource endpoint, assuming it speaks MCP (initialize + tools/list). Rejects only when the
     * server explicitly refuses the request (HTTP 401/403 or a JSON-RPC error); connectivity issues
     * and non-MCP endpoints are tolerated because the key may be provisioned before the server is
     * reachable (#1698).
     */
    private void validateApiKey(SecuredResource securedResource, String apiKey) {
        if (StringUtils.isBlank(apiKey)) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "api_key must not be blank");
        }
        if (containsIllegalHeaderChars(apiKey)) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "api_key contains illegal control characters");
        }

        String endpoint = securedResource.getEndpoint();
        String apiKeyHeader = securedResource.getAuthSettings().getApiKeyHeader();
        if (StringUtils.isBlank(endpoint) || StringUtils.isBlank(apiKeyHeader)) {
            return;
        }

        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder(endpoint)
                .clientBuilder(mcpHttpClientBuilderService.httpClientBuilder())
                .jsonMapper(McpClientUtils.MCP_JSON_MAPPER)
                .httpRequestCustomizer((builder, method, uri, body, transportContext) ->
                        builder.header(apiKeyHeader, apiKey))
                .build();

        try (McpSyncClient client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("DIAL", "1.0"))
                .requestTimeout(MCP_PROBE_TIMEOUT)
                .initializationTimeout(MCP_PROBE_TIMEOUT)
                .jsonSchemaValidator(McpClientUtils.NOOP_SCHEMA_VALIDATOR)
                .build()) {
            client.initialize();
            client.listTools(null);
        } catch (Exception e) {
            McpHttpClientTransportAuthorizationException authError =
                    ExceptionUtils.throwableOfType(e, McpHttpClientTransportAuthorizationException.class);
            if (authError != null) {
                log.warn("API key validation failed for endpoint {}: MCP server responded with status {}",
                        endpoint, authError.getResponseInfo().statusCode());
                throw new HttpException(HttpStatus.BAD_REQUEST,
                        "API key validation failed: MCP server rejected the provided API key");
            }
            McpError mcpError = ExceptionUtils.throwableOfType(e, McpError.class);
            if (mcpError != null) {
                log.warn("API key validation failed for endpoint {}: MCP server returned an error: {}",
                        endpoint, mcpError.getMessage());
                throw new HttpException(HttpStatus.BAD_REQUEST,
                        "API key validation failed: MCP server rejected the request: " + mcpError.getMessage());
            }
            log.warn("Skipping API key validation for endpoint {}: {}", endpoint, e.getMessage());
        }
    }

    // java.net.http rejects header values containing control characters other than HTAB
    private static boolean containsIllegalHeaderChars(String value) {
        return value.chars().anyMatch(c -> (c < 0x20 && c != '\t') || c == 0x7f);
    }
}
