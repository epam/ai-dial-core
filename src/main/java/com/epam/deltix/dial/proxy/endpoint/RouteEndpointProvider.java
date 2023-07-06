package com.epam.deltix.dial.proxy.endpoint;

import com.epam.deltix.dial.proxy.config.Route;

import java.util.Map;

public record RouteEndpointProvider(Route route) implements EndpointProvider {

    @Override
    public String getName() {
        return route.getName();
    }

    @Override
    public Map<String, String> getEndpoints() {
        return route.getEndpoints();
    }
}
