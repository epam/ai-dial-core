package com.epam.deltix.dial.proxy.controller;

import com.epam.deltix.dial.proxy.ProxyContext;
import com.epam.deltix.dial.proxy.config.Application;
import com.epam.deltix.dial.proxy.config.Config;
import com.epam.deltix.dial.proxy.data.ApplicationData;
import com.epam.deltix.dial.proxy.data.ListData;
import com.epam.deltix.dial.proxy.util.HttpStatus;
import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
public class ApplicationController {

    private final ProxyContext context;

    public Future<?> getApplication(String applicationId) {
        Config config = context.getConfig();
        Application application = config.getApplications().get(applicationId);

        if (application == null) {
            return context.respond(HttpStatus.NOT_FOUND);
        }

        if (!DeploymentController.hasAccess(context, application)) {
            return context.respond(HttpStatus.FORBIDDEN);
        }

        ApplicationData data = createApplication(application);
        return context.respond(HttpStatus.OK, data);
    }

    public Future<?> getApplications() {
        Config config = context.getConfig();
        List<ApplicationData> applications = new ArrayList<>();

        for (Application application : config.getApplications().values()) {
            if (DeploymentController.hasAccess(context, application)) {
                ApplicationData data = createApplication(application);
                applications.add(data);
            }
        }

        ListData<ApplicationData> list = new ListData<>();
        list.setData(applications);

        return context.respond(HttpStatus.OK, list);
    }

    private static ApplicationData createApplication(Application application) {
        ApplicationData data = new ApplicationData();
        data.setId(application.getName());
        data.setApplication(application.getName());
        return data;
    }
}