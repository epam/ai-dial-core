package com.epam.aidial.core.server.data.toolset.registration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@Builder
public class ToolsetAuthorizationServerMetadata {

    @JsonProperty("authorization_endpoint")
    private String authorizationEndpoint;

    @JsonProperty("registration_endpoint")
    private String registrationEndpoint;

    @JsonProperty("token_endpoint")
    private String tokenEndpoint;
}
