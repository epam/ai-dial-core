package com.epam.deltix.dial.proxy.upstream;

import com.epam.deltix.dial.proxy.config.Deployment;
import com.epam.deltix.dial.proxy.config.Model;
import com.epam.deltix.dial.proxy.config.Upstream;

import java.util.List;

public record DeploymentUpstreamProvider(Deployment deployment) implements UpstreamProvider {

    @Override
    public String getName() {
        return deployment.getName();
    }

    @Override
    public List<Upstream> getUpstreams() {
        if (deployment instanceof Model model && !model.getUpstreams().isEmpty()) {
            return model.getUpstreams();
        }

        Upstream upstream = new Upstream();
        upstream.setEndpoint(deployment.getEndpoint());
        upstream.setKey("whatever");
        return List.of(upstream);
    }
}
