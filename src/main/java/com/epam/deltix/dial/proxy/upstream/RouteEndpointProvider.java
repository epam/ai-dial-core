package com.epam.deltix.dial.proxy.upstream;

import com.epam.deltix.dial.proxy.config.Route;
import com.epam.deltix.dial.proxy.config.Upstream;

import java.util.List;

public record RouteEndpointProvider(Route route) implements UpstreamProvider {

    @Override
    public String getName() {
        return route.getName();
    }

    @Override
    public List<Upstream> getUpstreams() {
        return route.getUpstreams();
    }
}
