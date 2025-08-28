package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

@Data
public class ResourceSignInRequest {

    @JsonAlias("url")
    private String url;

    @JsonAlias({"credentialsLevel", "credentials_level"})
    private CredentialsLevel credentialsLevel;

    @JsonAlias({"authenticationType", "authentication_type"})
    private AuthenticationType authenticationType;

    @JsonAlias("code")
    private String code;

    @JsonAlias({"apiKey", "api_key"})
    private String apiKey;
}
