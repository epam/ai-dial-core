package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Per-interface routing configuration for a {@link Deployment}. Intentionally minimal;
 * future per-interface options (auth mode, defaults, ...) go here.
 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeploymentInterface {

    /**
     * Root url the matching ingress path is appended to at request time. Optional: an entry declaring
     * none is served by the deployment-level {@link Deployment#getBaseUrl()}.
     */
    @JsonProperty("base_url")
    @JsonAlias({"baseUrl", "base_url"})
    private String baseUrl;

    /**
     * Whether the interface is forwarded as it arrived or translated first. Absent means
     * {@link InterfaceMode#PASSTHROUGH}, which is what every pre-{@code mode} config is.
     */
    private InterfaceMode mode;

    /**
     * The translator serving this interface, named or defined inline, when {@link #mode} is
     * {@link InterfaceMode#TRANSLATOR}. An interface is served either by a base url or by a translator,
     * never by both.
     */
    private TranslatorRef translator;

    /**
     * Headers added to a request for this interface that carries none under that name, laid over the
     * deployment-level {@code defaultHeaders}. Resolved by {@link Deployment#resolveDefaultHeaders}.
     */
    @JsonAlias({"defaultHeaders", "default_headers"})
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, String> defaultHeaders = Map.of();

    public DeploymentInterface(String baseUrl) {
        this.baseUrl = baseUrl;
    }
}
