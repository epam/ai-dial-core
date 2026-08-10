package com.epam.aidial.core.server.data;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Whether the caller has offline credentials and, when they do not, what chat needs to build the authorization URL.
 * Carries only non-secret settings — the client secret stays in core, which performs the exchange.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OfflineCredentialsStatus(boolean connected, @JsonProperty("connect") Connect connect) {

    public static OfflineCredentialsStatus of(boolean connected, ResourceAuthSettings offlineClient) {
        if (connected || offlineClient == null) {
            return new OfflineCredentialsStatus(connected, null);
        }
        return new OfflineCredentialsStatus(false, new Connect(
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
