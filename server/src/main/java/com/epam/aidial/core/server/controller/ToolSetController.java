package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ListData;
import com.epam.aidial.core.server.data.ResourceTypes;
import com.epam.aidial.core.server.data.ToolSetData;
import com.epam.aidial.core.server.service.DeploymentService;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.service.ResourceNotFoundException;
import com.epam.aidial.core.server.service.ToolSetService;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class ToolSetController {

    private final ProxyContext context;
    private final DeploymentService deploymentService;

    private final ToolSetService toolSetService;

    private final Vertx vertx;

    public ToolSetController(ProxyContext context) {
        this.context = context;
        this.vertx = context.getProxy().getVertx();
        this.deploymentService = context.getProxy().getDeploymentService();
        this.toolSetService = context.getProxy().getToolSetService();
    }

    public Future<?> getToolSet(String toolSetId) {
        vertx.executeBlocking(() -> {
            Deployment deployment = deploymentService.findDeployment(context, toolSetId);
            if (deployment instanceof ToolSet toolSet) {
                return toolSet;
            }
            throw new ResourceNotFoundException("Toolset is not found: " + toolSetId);
        }, false).map(ToolSetData::toData)
                .onSuccess(data -> context.respond(HttpStatus.OK, data))
                .onFailure(this::respondError);
        return Future.succeededFuture();
    }

    public Future<?> getToolSets() {
        Config config = context.getConfig();
        Proxy proxy = context.getProxy();

        return proxy.getVertx().executeBlocking(() -> {
            List<ToolSet> list = new ArrayList<>();
            for (ToolSet toolSet : config.getToolsets().values()) {
                if (toolSet.hasAccess(context.getUserRoles())) {
                    list.add(toolSet);
                }
            }
            list.addAll(getResourceToolSets());
            return list.stream().map(ToolSetData::toData).toList();
        }).onSuccess(toolSets -> context.respond(HttpStatus.OK, new ListData<>(toolSets)))
                .onFailure(this::respondError);
    }

    private List<ToolSet> getResourceToolSets() {
        return deploymentService.listDeployments(context, ResourceTypes.TOOL_SET, new DeploymentService.DeploymentExtractor() {
            @SuppressWarnings("unchecked")
            @Override
            public ToolSet extract(ResourceDescriptor resource, ProxyContext context) {
                return toolSetService.getToolSet(resource).getValue();
            }
        });
    }

    private void respondError(Throwable error) {
        if (error instanceof IllegalArgumentException) {
            context.respond(HttpStatus.BAD_REQUEST, error.getMessage());
        } else if (error instanceof PermissionDeniedException) {
            context.respond(HttpStatus.FORBIDDEN, error.getMessage());
        } else if (error instanceof ResourceNotFoundException) {
            context.respond(HttpStatus.NOT_FOUND, error.getMessage());
        } else {
            log.error("Failed to handle application request", error);
            context.respond(error, "Internal error");
        }
    }
}
