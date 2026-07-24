package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Per-interface routing configuration for a {@link Deployment}. Intentionally minimal;
 * future per-interface options (auth mode, defaults, ...) go here.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeploymentInterface {

    /**
     * Source (adapter) root the matching ingress path is appended to at request time.
     */
    @JsonProperty("base_url")
    private String baseUrl;

    /**
     * Deployment id to route to instead of the deployment declaring this interface. Restores the legacy
     * alias pattern (a deployment whose {@code endpoint} pointed at another deployment's route) for the
     * base_url flow, where the request otherwise carries the alias's own id back to {@code base_url}.
     */
    @JsonProperty("deployment_name")
    private String deploymentName;

    public DeploymentInterface(String baseUrl) {
        this(baseUrl, null);
    }

    @JsonCreator
    public DeploymentInterface(
            @JsonProperty(value = "base_url", required = true)
            @JsonAlias({"baseUrl", "base_url"})
            String baseUrl,
            @JsonProperty("deployment_name")
            @JsonAlias({"deploymentName", "deployment_name"})
            String deploymentName
    ) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            throw new IllegalArgumentException("baseUrl cannot be null or empty");
        }
        this.baseUrl = baseUrl;
        this.deploymentName = deploymentName;
    }
}
