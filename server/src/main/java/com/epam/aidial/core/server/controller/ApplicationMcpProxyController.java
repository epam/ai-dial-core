package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiOperations;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.OpenApiDescriptions;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.function.BaseRequestFunction;
import com.epam.aidial.core.server.function.enhancement.InjectApplicationPropsToMcpRequest;
import com.epam.aidial.core.server.service.ApplicationSchemaService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
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
    @ApiOperations({
            @ApiOperation(
                    method = "POST",
                    path = "/v1/deployments/{deployment_name}/mcp",
                    operationId = "postApplicationMcp",
                    tags = {"Deployments", "MCP"},
                    requestBody = @ApiSchema(schemaRef = "ProxyRequest"),
                    parameters = {
                            @ApiParameter(name = "deployment_name", in = ParameterIn.PATH, required = true,
                                    description = OpenApiDescriptions.DEPLOYMENT_IDENTIFIER)
                    },
                    responses = {
                            @ApiResponse(code = 200, description = "Success", body = @ApiSchema(schemaRef = "ProxyResponse")),
                            @ApiResponse(code = 400),
                            @ApiResponse(code = 403),
                            @ApiResponse(code = 404),
                            @ApiResponse(code = 500)
                    })
    })
    public Future<?> handle() {
        return super.handle();
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