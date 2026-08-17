package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
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

    public static final int HINT_LENGTH = 4;
    public static final int SHORT_HINT_LENGTH = 2;
    public static final int HINT_MIN_SECRET_LENGTH = 8;
    public static final int FULL_HINT_SECRET_LENGTH = 12;

    @NotNull(message = "AuthenticationType must be defined")
    @JsonAlias({"authenticationType", "authentication_type"})
    @Builder.Default
    private AuthenticationType authenticationType = AuthenticationType.NONE;

    @JsonAlias({"clientId", "client_id"})
    private String clientId;

    @JsonAlias({"clientSecret", "client_secret"})
    @ToString.Exclude
    private String clientSecret;

    // Computed on read for callers who may manage the resource; never client-settable.
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String clientSecretHint;

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

    @JsonAlias({"dynamicallyRegistered", "dynamically_registered"})
    private Boolean dynamicallyRegistered;

    /**
     * Returns a copy with credential material ({@code clientSecret}, {@code codeVerifier}) removed — the single
     * place read responses strip secrets, so a newly added secret field only has to be cleared here. The
     * receiver is left unchanged.
     */
    public ResourceAuthSettings withoutSecrets() {
        return withoutSecrets(false);
    }

    /**
     * As {@link #withoutSecrets()}, additionally exposing {@code clientSecretHint} when {@code revealHint} —
     * reserved for callers that may manage the resource (they can overwrite the secret outright).
     */
    public ResourceAuthSettings withoutSecrets(boolean revealHint) {
        ResourceAuthSettings copy = toBuilder().build();
        copy.redactSecrets(revealHint);
        return copy;
    }

    /**
     * In-place variant for callers that already hold a private copy of the settings.
     */
    public void redactSecrets(boolean revealHint) {
        clientSecretHint = revealHint ? hintFor(clientSecret) : null;
        clientSecret = null;
        codeVerifier = null;
    }

    /**
     * Clears every field core computes on read, so a write can never persist one a client echoed back.
     */
    public void clearComputedFields() {
        globalAuthStatus = null;
        userLevelAuthStatus = null;
        appLevelAuthStatus = null;
        clientSecretHint = null;
    }

    /**
     * The trailing characters of the secret, letting a manager recognize which secret is stored without core ever
     * revealing it. Scaled to the secret's length so the fragment never approaches the whole value (PCI DSS 4.0
     * §3.4.1 caps partial disclosure the same way): nothing at all below {@value #HINT_MIN_SECRET_LENGTH},
     * {@value #SHORT_HINT_LENGTH} characters up to {@value #FULL_HINT_SECRET_LENGTH}, {@value #HINT_LENGTH} beyond
     * it. Every secret a real authorization server issues lands in the last band, so the width is constant in
     * practice; the shorter bands only cover values stored before {@code ClientSecretValidation} existed.
     */
    public static String hintFor(String secret) {
        if (secret == null || secret.length() < HINT_MIN_SECRET_LENGTH) {
            return null;
        }
        int revealed = secret.length() < FULL_HINT_SECRET_LENGTH ? SHORT_HINT_LENGTH : HINT_LENGTH;
        return secret.substring(secret.length() - revealed);
    }
}
