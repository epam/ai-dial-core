package com.epam.aidial.core.upstream;

import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.config.Upstream;

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
