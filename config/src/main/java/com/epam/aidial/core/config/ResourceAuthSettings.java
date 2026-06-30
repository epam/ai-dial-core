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
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder(toBuilder = true)
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ResourceAuthSettings {

    @NotNull(message = "AuthenticationType must be defined")
    @JsonAlias({"authenticationType", "authentication_type"})
    @Builder.Default
    private AuthenticationType authenticationType = AuthenticationType.NONE;

    @JsonAlias({"clientId", "client_id"})
    private String clientId;

    @JsonAlias({"clientSecret", "client_secret"})
    @ToString.Exclude
    private String clientSecret;

    @JsonAlias({"authorizationEndpoint", "authorization_endpoint"})
    private String authorizationEndpoint;

    @JsonAlias({"tokenEndpoint", "token_endpoint"})
    private String tokenEndpoint;

    @JsonAlias({"redirectUri", "redirect_uri"})
    private String redirectUri;

    @JsonAlias({"codeChallenge", "code_challenge"})
    private String codeChallenge;

    @JsonAlias({"codeChallengeMethod", "code_challenge_method"})
    private String codeChallengeMethod;

    @JsonAlias({"codeVerifier", "code_verifier"})
    @ToString.Exclude
    private String codeVerifier;

    @JsonAlias({"apiKeyHeader", "api_key_header"})
    private String apiKeyHeader;

    // Statuses are computed per-user on read and stripped on write; never client-settable.
    @JsonAlias({"globalAuthStatus", "global_auth_status"})
    private ResourceAuthStatus globalAuthStatus;

    @JsonAlias({"userLevelAuthStatus", "user_level_auth_status"})
    private ResourceAuthStatus userLevelAuthStatus;

    @JsonAlias({"appLevelAuthStatus", "app_level_auth_status"})
    private ResourceAuthStatus appLevelAuthStatus;

    @JsonAlias({"scopesSupported", "scopes_supported"})
    private List<String> scopesSupported;

    @JsonAlias({"tokenEndpointAuthMethod", "token_endpoint_auth_method"})
    private String tokenEndpointAuthMethod;
}
