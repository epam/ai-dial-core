package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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
    @JsonAlias({"baseUrl", "base_url"})
    private String baseUrl;

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
