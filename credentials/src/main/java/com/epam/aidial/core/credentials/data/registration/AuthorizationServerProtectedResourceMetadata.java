package com.epam.aidial.core.credentials.data.registration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class AuthorizationServerProtectedResourceMetadata {

    @JsonProperty("resource")
    private String resource;

    @JsonProperty("authorization_servers")
    private List<String> authorizationServers;
}
