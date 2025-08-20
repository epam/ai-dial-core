package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ToolSetAuthSettings {

    @NotNull(message = "ToolsetAuthenticationType must be defined")
    @JsonAlias({"authenticationType", "authentication_type"})
    private AuthenticationType authenticationType;

    @JsonAlias({"clientId", "client_id"})
    private String clientId;

    @JsonAlias({"clientSecret", "client_secret"})
    private String clientSecret;

    @JsonAlias({"authorizationEndpoint", "authorization_endpoint"})
    private String authorizationEndpoint;

    @JsonAlias({"tokenEndpoint", "token_endpoint"})
    private String tokenEndpoint;

    @JsonAlias({"redirectUri", "redirect_uri"})
    private String redirectUri;

    @JsonAlias({"apiKeyHeader", "api_key_header"})
    private String apiKeyHeader;

    @JsonAlias({"globalAuthStatus", "global_auth_status"})
    private ToolsetAuthStatus globalAuthStatus;

    @JsonAlias({"userLevelAuthStatus", "user_level_auth_status"})
    private ToolsetAuthStatus userLevelAuthStatus;

    @JsonAlias({"appLevelAuthStatus", "app_level_auth_status"})
    private ToolsetAuthStatus appLevelAuthStatus;
}
