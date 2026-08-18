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

    /** Wire names of the fields the JSON-level redaction in the admin config API has to keep in step. */
    public static final String CLIENT_SECRET_FIELD = "client_secret";
    public static final String CODE_VERIFIER_FIELD = "code_verifier";
    public static final String CLIENT_SECRET_HINT_FIELD = "client_secret_hint";

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
     * Returns a copy with credential material ({@code clientSecret}, {@code codeVerifier}) removed; the receiver
     * is left unchanged. A newly added secret field has to be cleared here <em>and</em> in
     * {@code ConfigResourceController.redactSecretFields}, which redacts the admin config API's JSON projection
     * of the merged config rather than this object — see {@link #CLIENT_SECRET_FIELD} and its neighbours.
     */
    public ResourceAuthSettings withoutSecrets() {
        ResourceAuthSettings copy = toBuilder().build();
        copy.redactSecrets();
        return copy;
    }

    /**
     * As {@link #withoutSecrets()}, but keeps a {@code clientSecretHint} derived from the secret being removed.
     * Reserved for callers that may manage the resource — they can overwrite the secret outright — which is why
     * this is a separate method rather than a boolean: the risky call sites stay greppable.
     */
    public ResourceAuthSettings withoutSecretsKeepingHint() {
        ResourceAuthSettings copy = toBuilder().build();
        copy.redactSecretsKeepingHint();
        return copy;
    }

    /**
     * In-place {@link #withoutSecrets()} for callers that already hold a private copy of the settings.
     */
    public void redactSecrets() {
        clientSecretHint = null;
        clientSecret = null;
        codeVerifier = null;
    }

    /**
     * In-place {@link #withoutSecretsKeepingHint()}.
     */
    public void redactSecretsKeepingHint() {
        clientSecretHint = hintFor(clientSecret);
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
