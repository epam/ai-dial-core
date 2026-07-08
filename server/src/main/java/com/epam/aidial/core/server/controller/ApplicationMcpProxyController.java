package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.function.BaseRequestFunction;
import com.epam.aidial.core.server.function.enhancement.InjectApplicationPropsToMcpRequest;
import com.epam.aidial.core.server.service.ApplicationSchemaService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpClientRequest;

import java.util.List;

import static com.epam.aidial.core.server.Proxy.HEADER_APPLICATION_ID;
import static com.epam.aidial.core.server.Proxy.HEADER_APPLICATION_PROPERTIES;

public class ApplicationMcpProxyController extends McpProxyController {

    private final List<BaseRequestFunction<ObjectNode>> enhancementFunctions;
    private final ApplicationSchemaService applicationSchemaService;

    private Application application;

    public ApplicationMcpProxyController(Proxy proxy, ProxyContext context, String toolSetId) {
        super(proxy, context, toolSetId);
        this.applicationSchemaService = proxy.getApplicationSchemaService();
        this.enhancementFunctions = List.of(new InjectApplicationPropsToMcpRequest(proxy, context));
    }

    @Override
    protected void validateDeployment(Deployment deployment) {
        if (!(deployment instanceof Application)) {
            throw new ResourceNotFoundException("Application is not found: " + deployment.getName());
        }
        application = (Application) deployment;
        resolveMcpProperties(application);
    }

    private void resolveMcpProperties(Application application) {
        Application.Mcp mcp;
        if (application.hasApplicationTypeSchemaId()) {
            mcp = applicationSchemaService.getMcp(application);
            application.setMcp(mcp);
        } else {
            mcp = application.getMcp();
        }
        if (mcp == null) {
            throw new IllegalArgumentException("Application doesn't support MCP protocol: " + application.getName());
        }
    }

    protected String getUpstreamEndpoint(Deployment deployment) {
        return application.getMcp().getEndpoint();
    }

    @Override
    protected void injectProxyRequestHeaders(HttpClientRequest proxyRequest, MultiMap excludeHeaders) {
        excludeHeaders.add(HEADER_APPLICATION_PROPERTIES, "whatever");
        excludeHeaders.add(HEADER_APPLICATION_ID, "whatever");
        Application.Mcp mcp = application.getMcp();
        if (mcp.isForwardPerRequestKey()) {
            String perRequestKey = assignPerRequestKey();
            proxyRequest.putHeader(Proxy.HEADER_API_KEY, perRequestKey);
        }

        proxyRequest.putHeader(HEADER_APPLICATION_ID, application.getName());

        if (application.getMcp().getConfigDelivery() == Application.McpConfigDelivery.HEADER) {
            applicationSchemaService.consumeMetadataProperties(application, (properties, appendApplicationPropertiesHeader) -> {
                if (appendApplicationPropertiesHeader) {
                    String propsString = ProxyUtil.MAPPER.writeValueAsString(properties);
                    proxyRequest.putHeader(HEADER_APPLICATION_PROPERTIES, propsString);
                }
            });
        }
    }

    @Override
    protected List<BaseRequestFunction<ObjectNode>> getRequestEnhancementFunctions() {
        return enhancementFunctions;
    }
}
