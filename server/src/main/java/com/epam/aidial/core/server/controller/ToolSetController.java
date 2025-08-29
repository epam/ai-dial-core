package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsService;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ListData;
import com.epam.aidial.core.server.data.ResourceTypes;
import com.epam.aidial.core.server.data.ToolSetData;
import com.epam.aidial.core.server.service.DeploymentService;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.service.ToolSetService;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import io.vertx.core.Future;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class ToolSetController {

    private final ProxyContext context;
    private final DeploymentService deploymentService;

    private final ToolSetService toolSetService;

    private final ResourceAuthSettingsService resourceAuthSettingsService;

    private final AsyncTaskExecutor taskExecutor;

    public ToolSetController(ProxyContext context) {
        this.context = context;
        this.taskExecutor = context.getProxy().getTaskExecutor();
        this.deploymentService = context.getProxy().getDeploymentService();
        this.toolSetService = context.getProxy().getToolSetService();
        this.resourceAuthSettingsService = context.getProxy().getResourceAuthSettingsService();
    }

    public Future<?> getToolSet(String toolSetId) {
        taskExecutor.submit(() -> {
            Deployment deployment = deploymentService.findDeployment(context, toolSetId);
            if (deployment instanceof ToolSet toolSet) {
                resourceAuthSettingsService.setResourceAuthStatuses(toolSetId, toolSet.getAuthSettings());
                toolSet.clearAuthSettings();
                return toolSet;
            }
            throw new ResourceNotFoundException("Toolset is not found: " + toolSetId);
        }).map(ToolSetData::toData)
                .onSuccess(data -> context.respond(HttpStatus.OK, data))
                .onFailure(this::respondError);
        return Future.succeededFuture();
    }

    public Future<?> getToolSets() {
        Config config = context.getConfig();

        return taskExecutor.submit(() -> {
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
                ToolSet toolSet = toolSetService.getToolSet(resource).getValue();
                toolSet.clearAuthSettings();
                resourceAuthSettingsService.setResourceAuthStatuses(toolSet.getName(), toolSet.getAuthSettings());
                return toolSet;
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
            context.respond(error, "Internal error");
            log.error("Failed to handle application request", error);
        }
    }
}
