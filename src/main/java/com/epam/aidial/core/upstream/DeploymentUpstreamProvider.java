package com.epam.aidial.core.upstream;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Upstream;

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
