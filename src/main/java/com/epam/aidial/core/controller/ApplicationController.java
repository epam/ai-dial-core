package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.data.ApplicationData;
import com.epam.aidial.core.data.ListData;
import com.epam.aidial.core.service.CustomApplicationService;
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
    private final CustomApplicationService customApplicationService;
    private final boolean includeCustomApplications;

    public ApplicationController(ProxyContext context, Proxy proxy) {
        this.context = context;
        this.vertx = proxy.getVertx();
        this.customApplicationService = proxy.getCustomApplicationService();
        this.includeCustomApplications = customApplicationService.includeCustomApplications();
    }

    public Future<?> getApplication(String applicationId) {
        Config config = context.getConfig();
        Application application = config.getApplications().get(applicationId);

        Future<Application> applicationFuture;
        if (application != null) {
            if (!DeploymentController.hasAccess(context, application)) {
                return context.respond(HttpStatus.FORBIDDEN);
            }
            applicationFuture = Future.succeededFuture(application);
        } else {
            applicationFuture = vertx.executeBlocking(() -> customApplicationService.getCustomApplication(applicationId, context), false);
        }

        applicationFuture.map(app -> {
            if (app == null) {
                throw new ResourceNotFoundException(applicationId);
            }

            ApplicationData data = ApplicationUtil.mapApplication(app);
            context.respond(HttpStatus.OK, data);
            return null;
        }).onFailure(error -> handleRequestError(applicationId, error));

        return Future.succeededFuture();
    }

    public Future<?> getApplications() {
        Config config = context.getConfig();
        List<ApplicationData> applications = new ArrayList<>();

        for (Application application : config.getApplications().values()) {
            if (DeploymentController.hasAccess(context, application)) {
                ApplicationData data = ApplicationUtil.mapApplication(application);
                applications.add(data);
            }
        }

        ListData<ApplicationData> list = new ListData<>();
        list.setData(applications);

        if (includeCustomApplications) {
            vertx.executeBlocking(() -> {
                List<Application> ownCustomApplications = customApplicationService.getOwnCustomApplications(context);
                for (Application application : ownCustomApplications) {
                    ApplicationData data = ApplicationUtil.mapApplication(application);
                    applications.add(data);
                }
                List<Application> sharedApplications = customApplicationService.getSharedApplications(context);
                for (Application application : sharedApplications) {
                    ApplicationData data = ApplicationUtil.mapApplication(application);
                    applications.add(data);
                }
                List<Application> publicApplications = customApplicationService.getPublicApplications(context);
                for (Application application : publicApplications) {
                    ApplicationData data = ApplicationUtil.mapApplication(application);
                    applications.add(data);
                }
                return null;
            }, false)
                    .onSuccess(ignore -> context.respond(HttpStatus.OK, list)
                    .onFailure(error -> {
                        log.error("Can't fetch custom applications", error);
                        context.respond(HttpStatus.INTERNAL_SERVER_ERROR, error.getMessage());
                    }));
        } else {
            context.respond(HttpStatus.OK, list);
        }

        return Future.succeededFuture();
    }

    private void handleRequestError(String applicationId, Throwable error) {
        if (error instanceof PermissionDeniedException) {
            log.error("Forbidden application {}. Key: {}. User sub: {}", applicationId, context.getProject(), context.getUserSub());
            context.respond(HttpStatus.FORBIDDEN, error.getMessage());
        } else if (error instanceof ResourceNotFoundException) {
            log.error("Application not found {}", applicationId, error);
            context.respond(HttpStatus.NOT_FOUND, error.getMessage());
        } else {
            log.error("Failed to load application {}", applicationId, error);
            context.respond(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to load application: " + applicationId);
        }
    }
}