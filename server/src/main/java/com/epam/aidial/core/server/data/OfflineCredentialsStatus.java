package com.epam.aidial.core.server.data;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Whether the caller has offline credentials and, when they do not, what chat needs to build the authorization URL.
 * Carries only non-secret settings — the client secret stays in core, which performs the exchange.
 *
 * <p>{@code available} distinguishes "not connected yet" from "cannot connect": the caller's identity provider may
 * carry no offline client at all, and chat should not offer a connect flow that can only end in an error.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OfflineCredentialsStatus(boolean connected, boolean available, @JsonProperty("connect") Connect connect) {

    public static OfflineCredentialsStatus of(boolean connected, ResourceAuthSettings offlineClient) {
        if (connected || offlineClient == null) {
            return new OfflineCredentialsStatus(connected, connected || offlineClient != null, null);
        }
        return new OfflineCredentialsStatus(false, true, new Connect(
                offlineClient.getAuthorizationEndpoint(),
                offlineClient.getClientId(),
                offlineClient.getRedirectUri(),
                offlineClient.getScopesSupported()));
    }

    public record Connect(@JsonProperty("authorization_endpoint") String authorizationEndpoint,
                          @JsonProperty("client_id") String clientId,
                          @JsonProperty("redirect_uri") String redirectUri,
                          @JsonProperty("scopes") List<String> scopes) {
    }
}
