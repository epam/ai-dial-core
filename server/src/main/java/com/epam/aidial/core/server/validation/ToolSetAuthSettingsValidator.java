package com.epam.aidial.core.server.validation;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.ToolSetAuthSettings;

public class ToolSetAuthSettingsValidator {

    // TODO: add more validations
    public void validate(ToolSetAuthSettings toolSetAuthSettings) {
        AuthenticationType authenticationType = toolSetAuthSettings.getAuthenticationType();

        boolean noneOfOauthRequiredFieldsSet = toolSetAuthSettings.getClientId() == null && toolSetAuthSettings.getClientSecret() == null;

        if (authenticationType.equals(AuthenticationType.OAUTH)) {
            if (toolSetAuthSettings.getRedirectUri() == null) {
                throw new IllegalArgumentException("Redirect URI is required for OAUTH type Authentication.");
            }

            boolean allOauthRequiredFieldsSet = toolSetAuthSettings.getClientId() != null && toolSetAuthSettings.getClientSecret() != null;

            if (!allOauthRequiredFieldsSet && !noneOfOauthRequiredFieldsSet) {
                throw new IllegalArgumentException("Define all client details fields or none for dynamic client registration.");
            }
        } else if (authenticationType.equals(AuthenticationType.API_KEY)) {
            if (!noneOfOauthRequiredFieldsSet || toolSetAuthSettings.getRedirectUri() != null) {
                throw new IllegalArgumentException("Do not provide OAUTH specific fields for API key type Authentication.");
            }

            if (toolSetAuthSettings.getApiKeyHeader() == null) {
                throw new IllegalArgumentException("ApiKeyHeader field is required for API key type Authentication.");
            }
        } else if (authenticationType.equals(AuthenticationType.NONE)
                && (!noneOfOauthRequiredFieldsSet || toolSetAuthSettings.getApiKeyHeader() != null)
        ) {
            throw new IllegalArgumentException("Do not provide OAUTH/API key specific fields for NONE type Authentication.");
        }
    }
}
