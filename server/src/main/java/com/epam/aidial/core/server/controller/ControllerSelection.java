package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;

import java.util.function.BiFunction;

public final class ControllerSelection {

    private final String pathTemplate;
    private final BiFunction<Proxy, ProxyContext, Controller> controllerProvider;

    ControllerSelection(String pathTemplate, BiFunction<Proxy, ProxyContext, Controller> controllerProvider) {
        this.pathTemplate = pathTemplate;
        this.controllerProvider = controllerProvider;
    }

    public Controller controller(Proxy proxy, ProxyContext context) {
        return controllerProvider.apply(proxy, context);
    }

    public String pathTemplate() {
        return pathTemplate;
    }
}
