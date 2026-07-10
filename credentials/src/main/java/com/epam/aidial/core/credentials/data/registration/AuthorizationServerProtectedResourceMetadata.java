package com.epam.aidial.core.credentials.data.registration;

import com.epam.aidial.core.credentials.databind.SingleElementArrayOrStringDeserializer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class AuthorizationServerProtectedResourceMetadata {

    @JsonProperty("resource")
    @JsonDeserialize(using = SingleElementArrayOrStringDeserializer.class)
    private String resource;

    @JsonProperty("authorization_servers")
    private List<String> authorizationServers;

    @JsonProperty("scopes_supported")
    private List<String> scopesSupported;
}
