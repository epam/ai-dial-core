package com.epam.aidial.core.server.data.wellknown;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ResourceMetadata {
    private String resource;
    @JsonProperty("authorization_servers")
    private List<String> authorizationServers;
    @JsonProperty("scopes_supported")
    private List<String> scopesSupported;
}
