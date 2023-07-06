package com.epam.deltix.dial.proxy.endpoint;

import com.epam.deltix.dial.proxy.config.Deployment;
import com.epam.deltix.dial.proxy.config.Model;

import java.util.Map;

public record DeploymentEndpointProvider(Deployment deployment) implements EndpointProvider {

    @Override
    public String getName() {
        return deployment.getName();
    }

    @Override
    public Map<String, String> getEndpoints() {
        if (deployment instanceof Model model && !model.getUpstreams().isEmpty()) {
            return model.getUpstreams();
        }

        return Map.of(deployment.getEndpoint(), "whatever");
    }
}
