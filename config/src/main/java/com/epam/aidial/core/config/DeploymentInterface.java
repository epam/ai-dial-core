package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Map;

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
     * Headers added to a request for this interface that carries none under that name, laid over the
     * deployment-level {@code defaultHeaders}. Resolved by {@link Deployment#resolveDefaultHeaders}.
     */
    @JsonAlias({"defaultHeaders", "default_headers"})
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, String> defaultHeaders = Map.of();

    @JsonCreator
    public DeploymentInterface(
            @JsonProperty(value = "base_url", required = true)
            @JsonAlias({"baseUrl", "base_url"})
            String baseUrl
    ) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            throw new IllegalArgumentException("baseUrl cannot be null or empty");
        }
        this.baseUrl = baseUrl;
    }
}
