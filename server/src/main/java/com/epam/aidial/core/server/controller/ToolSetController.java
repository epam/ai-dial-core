package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.OpenApiDescriptions;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.openapi.annotations.ResponseProfile;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ListData;
import com.epam.aidial.core.server.data.ToolSetData;
import com.epam.aidial.core.server.service.DeploymentService;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.service.ToolSetService;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.util.UrlUtil;
import io.vertx.core.Future;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class ToolSetController {

    private final ProxyContext context;
    private final DeploymentService deploymentService;

    private final ToolSetService toolSetService;

    private final AsyncTaskExecutor taskExecutor;

    public ToolSetController(ProxyContext context) {
        this.context = context;
        this.taskExecutor = context.getProxy().getTaskExecutor();
        this.deploymentService = context.getProxy().getDeploymentService();
        this.toolSetService = context.getProxy().getToolSetService();
    }

    @ApiOperation(
            method = "GET",
            path = "/openai/toolsets/{toolset_name}",
            operationId = "getToolset",
            tags = {"Deployment listing"},
            parameters = {
                    @ApiParameter(name = "toolset_name", in = ParameterIn.PATH, required = true,
                            description = OpenApiDescriptions.TOOLSET_NAME)
            },
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = ToolSetData.class)
            },
            responseProfile = ResponseProfile.AUTHENTICATED_READ_EXTENDED
    )
    public Future<?> getToolSet(String toolSetId) {
        taskExecutor.submit(() -> {
            Deployment deployment = deploymentService.findDeployment(context, toolSetId);
            if (deployment instanceof ToolSet toolSet) {
                String encodedToolSetId = UrlUtil.encodePath(toolSetId);
                toolSetService.setResourceAuthStatuses(context, toolSet, encodedToolSetId);
                return toolSet;
            }
            throw new ResourceNotFoundException("Toolset is not found: " + toolSetId);
        }).map(ToolSetData::toData)
                .onSuccess(data -> context.respond(HttpStatus.OK, data))
                .onFailure(this::respondError);
        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "GET",
            path = "/openai/toolsets",
            operationId = "getToolSets",
            tags = {"Deployment listing"},
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = ToolSetData.class, wrapper = ListData.class)
            },
            responseProfile = ResponseProfile.AUTHENTICATED_READ_EXTENDED
    )
    public Future<?> getToolSets() {
        Config config = context.getConfig();
        return taskExecutor.submit(this::getResourceToolSets)
                .compose(this::enrichToolsets)
                .compose(toolsets -> taskExecutor.submit(() -> mergeToolsets(toolsets, config)))
                .map(toolsets -> toolsets.stream().map(ToolSetData::toData).toList())
                .onSuccess(toolSets -> context.respond(HttpStatus.OK, new ListData<>(toolSets)))
                .onFailure(this::respondError);
    }

    private List<ToolSet> mergeToolsets(List<ToolSet> resourceToolsets, Config config) {
        List<ToolSet> list = new ArrayList<>();
        for (ToolSet toolSet : config.getToolsets().values()) {
            if (toolSet.hasAccess(context.getUserRoles())) {
                toolSetService.setResourceAuthStatuses(context, toolSet, toolSet.getName());
                list.add(toolSet);
            }
        }
        list.addAll(resourceToolsets);
        return list;
    }

    private Future<List<ToolSet>> enrichToolsets(List<ToolSet> toolSets) {
        int size = toolSets.size();
        int batchSize = 50;
        int batches = size / batchSize;
        int rem = size % batchSize;
        int cur = 0;
        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < batches; i++) {
            int end = cur + batchSize;
            int start = cur;
            Future<Void> future = taskExecutor.submit(() -> updateAuthStatus(start, end, toolSets));
            futures.add(future);
            cur = end;
        }
        if (rem > 0) {
            int end = cur + rem;
            int start = cur;
            Future<Void> future = taskExecutor.submit(() -> updateAuthStatus(start, end, toolSets));
            futures.add(future);
        }
        return Future.all(futures).map(res -> toolSets);
    }

    private Void updateAuthStatus(int start, int end, List<ToolSet> toolSets) {
        if (end - start <= 0) {
            return null;
        }
        for (int i = start; i < end; i++) {
            ToolSet toolSet = toolSets.get(i);
            toolSetService.setResourceAuthStatuses(context, toolSet, toolSet.getName());
        }
        return null;
    }

    private List<ToolSet> getResourceToolSets() {
        return deploymentService.listDeployments(context, ResourceTypes.TOOL_SET, new DeploymentService.DeploymentExtractor() {
            @SuppressWarnings("unchecked")
            @Override
            public ToolSet extract(String content, ResourceItemMetadata metadata, ProxyContext context) {
                return toolSetService.extractFrom(content, metadata);
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