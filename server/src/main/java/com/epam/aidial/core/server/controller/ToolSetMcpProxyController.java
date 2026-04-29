package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.credentials.data.credentials.AuthorizationHeader;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;
import com.epam.aidial.core.credentials.service.AuthorizationHeaderProvider;
import com.epam.aidial.core.credentials.service.ResourceCredentialsService;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.openapi.ApiOperation;
import com.epam.aidial.core.server.openapi.ApiOperations;
import com.epam.aidial.core.server.util.CredentialsLocatorFactory;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.util.UrlUtil;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpClientRequest;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ToolSetMcpProxyController extends McpProxyController {

    private final CredentialsLocator credentialsLocator;
    private final AuthorizationHeaderProvider authorizationHeaderProvider;
    private final ResourceCredentialsService resourceCredentialsService;


    private ToolSet toolSet;

    public ToolSetMcpProxyController(Proxy proxy, ProxyContext context, String toolSetId) {
        super(proxy, context, toolSetId);
        this.authorizationHeaderProvider = proxy.getAuthorizationHeaderProvider();
        this.credentialsLocator = CredentialsLocatorFactory.fromAnyUrl(UrlUtil.encodePath(toolSetId), context, ResourceTypes.TOOL_SET);
        this.resourceCredentialsService = proxy.getResourceCredentialsService();
    }

    @Override
    @ApiOperations({
            @ApiOperation(method = "GET", path = "/v1/toolset/{toolset_name}/mcp",
                    operationId = "getToolSetMcp", tags = {"Toolsets", "MCP"}),
            @ApiOperation(method = "POST", path = "/v1/toolset/{toolset_name}/mcp",
                    operationId = "postToolSetMcp", tags = {"Toolsets", "MCP"}),
            @ApiOperation(method = "DELETE", path = "/v1/toolset/{toolset_name}/mcp",
                    operationId = "deleteToolSetMcp", tags = {"Toolsets", "MCP"})
    })
    public Future<?> handle() {
        return super.handle();
    }

    @Override
    protected void validateDeployment(Deployment deployment) {
        if (!(deployment instanceof ToolSet)) {
            throw new ResourceNotFoundException("Toolset is not found: " + deployment.getName());
        }
        toolSet = (ToolSet) deployment;
    }

    @Override
    protected void injectProxyRequestHeaders(HttpClientRequest proxyRequest, MultiMap excludeHeaders) {
        ResourceCredentials resourceCredentials = resourceCredentialsService.getRefreshedResourceCredentials(
                credentialsLocator, toolSet.getAuthSettings(), context.getInitiatorId()
        );

        if (resourceCredentials != null) {
            log.debug("Credentials found: User: {}, Resource: {}, CredentialsLevel: {}",
                    context.getUserId(), deploymentId, resourceCredentials.getCredentialsLevel());
            addAuthorizationHeader(proxyRequest, resourceCredentials);
        } else {
            log.debug("Credentials not found - User: {}, Resource: {}", context.getUserId(), deploymentId);
        }

        if (toolSet.isForwardPerRequestKey()) {
            String perRequestKey = assignPerRequestKey();
            proxyRequest.putHeader(Proxy.HEADER_API_KEY, perRequestKey);
        }

    }

    private void addAuthorizationHeader(HttpClientRequest proxyRequest,
                                        ResourceCredentials resourceCredentials) {
        AuthorizationHeader authorizationHeader = authorizationHeaderProvider.createAuthorizationHeader(resourceCredentials);
        if (authorizationHeader != null) {
            log.debug("AuthorizationHeader added: User: {}, Resource: {}", context.getUserId(), deploymentId);
            proxyRequest.putHeader(authorizationHeader.getHeaderName(), authorizationHeader.getHeaderValue());
        }
    }
}
