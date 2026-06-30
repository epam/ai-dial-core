package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/**
 * Per-interface routing configuration for a {@link Deployment}. Intentionally minimal;
 * future per-interface options (auth mode, defaults, ...) go here.
 */
@Getter
@ToString
@EqualsAndHashCode
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeploymentInterface {

    /**
     * Required, non-null. Source (adapter) root the matching ingress path is appended to at request time.
     * A deployment interface cannot be created without a {@code base_url}; config that declares one without it
     * is rejected at load time.
     */
    @JsonProperty("base_url")
    private final String baseUrl;

    @JsonCreator
    public DeploymentInterface(
            @NotBlank
            @JsonProperty(value = "base_url", required = true)
            @JsonAlias({"baseUrl", "base_url"})
            String baseUrl
    ) {
        this.baseUrl = baseUrl;
    }
}
