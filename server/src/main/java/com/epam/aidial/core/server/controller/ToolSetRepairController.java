package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.service.ToolSetRepairService;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import io.vertx.core.Future;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ToolSetRepairController implements Controller {

    private final ProxyContext context;
    private final AsyncTaskExecutor taskExecutor;
    private final AccessService accessService;
    private final ToolSetRepairService repairService;
    private final ResourceDescriptor resource;

    public ToolSetRepairController(Proxy proxy, ProxyContext context, ResourceDescriptor resource) {
        this.context = context;
        this.taskExecutor = proxy.getTaskExecutor();
        this.accessService = proxy.getAccessService();
        this.repairService = proxy.getToolSetRepairService();
        this.resource = resource;
    }

    @Override
    public Future<?> handle() {
        return taskExecutor.submit(() -> {
            if (!accessService.hasWriteAccess(resource, context)) {
                throw new HttpException(HttpStatus.FORBIDDEN,
                        "Write access to the toolset is required to repair it");
            }
            repairService.repair(resource, context);
            return null;
        })
        .onSuccess(ignored -> context.respond(HttpStatus.OK,
                new RepairResponse("REREGISTERED", "re-registered: new client_id issued, all credentials cleared")))
        .onFailure(this::respondError);
    }

    record RepairResponse(String result, String message) {}

    private void respondError(Throwable error) {
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        String body = null;

        switch (error) {
            case HttpException e -> {
                status = e.getStatus();
                body = e.getMessage();
            }
            case ResourceNotFoundException e -> {
                status = HttpStatus.NOT_FOUND;
                body = e.getMessage();
            }
            case IllegalArgumentException e -> {
                status = HttpStatus.BAD_REQUEST;
                body = e.getMessage();
            }
            case null, default -> {
                log.error("Unexpected error during toolset repair for {}", resource.getUrl(), error);
                body = "An unexpected error occurred while repairing toolset " + resource.getUrl();
            }
        }

        context.respond(status, body);
    }
}
