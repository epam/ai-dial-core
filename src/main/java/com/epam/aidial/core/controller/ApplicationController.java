package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.data.ApplicationData;
import com.epam.aidial.core.data.ListData;
import com.epam.aidial.core.service.ApplicationService;
import com.epam.aidial.core.service.PermissionDeniedException;
import com.epam.aidial.core.service.ResourceNotFoundException;
import com.epam.aidial.core.util.HttpStatus;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class ApplicationController {

    private final ProxyContext context;
    private final Vertx vertx;
    private final ApplicationService applications;

    public ApplicationController(ProxyContext context, Proxy proxy) {
        this.context = context;
        this.vertx = proxy.getVertx();
        this.applications = proxy.getApplicationService();
    }

    public Future<?> getApplication(String applicationId) {
        DeploymentController.selectDeployment(context, applicationId)
                .map(deployment -> {
                    if (deployment instanceof Application application) {
                        return application;
                    }

                    throw new ResourceNotFoundException("Application is not found: " + applicationId);
                })
                .map(ApplicationUtil::mapApplication)
                .onSuccess(data -> context.respond(HttpStatus.OK, data))
                .onFailure(this::handleRequestError);

        return Future.succeededFuture();
    }

    public Future<?> getApplications() {
        Config config = context.getConfig();
        List<ApplicationData> list = new ArrayList<>();

        for (Application application : config.getApplications().values()) {
            if (DeploymentController.hasAccess(context, application)) {
                ApplicationData data = ApplicationUtil.mapApplication(application);
                list.add(data);
            }
        }

        Future<List<ApplicationData>> future = Future.succeededFuture(list);

        if (applications.isIncludeCustomApps()) {
            future = vertx.executeBlocking(() -> applications.getAllApplications(context), false)
                    .map(apps -> {
                        apps.forEach(app -> list.add(ApplicationUtil.mapApplication(app)));
                        return list;
                    });
        }

        future.onSuccess(apps -> context.respond(HttpStatus.OK, new ListData<>(apps)))
                .onFailure(this::handleRequestError);

        return Future.succeededFuture();
    }

    private void handleRequestError(Throwable error) {
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