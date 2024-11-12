package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;

import java.util.function.BiFunction;

public final class ControllerTemplate {

    private final String pathTemplate;
    private final BiFunction<Proxy, ProxyContext, Controller> controllerProvider;

    ControllerTemplate(String pathTemplate, BiFunction<Proxy, ProxyContext, Controller> controllerProvider) {
        this.pathTemplate = pathTemplate;
        this.controllerProvider = controllerProvider;
    }

    public Controller build(Proxy proxy, ProxyContext context) {
        return controllerProvider.apply(proxy, context);
    }

    public String pathTemplate() {
        return pathTemplate;
    }
}
