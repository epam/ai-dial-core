package com.epam.aidial.core.server.validation;

import com.epam.aidial.core.config.ToolSetAuthSettings;
import com.epam.aidial.core.config.ToolsetAuthenticationType;

public class ToolSetAuthSettingsValidator {

    // TODO: add more validations?
    public void validate(ToolSetAuthSettings toolSetAuthSettings) {
        ToolsetAuthenticationType toolsetAuthenticationType = toolSetAuthSettings.getToolsetAuthenticationType();

        boolean noneOfOauthRequiredFieldsSet = toolSetAuthSettings.getClientId() == null && toolSetAuthSettings.getClientSecret() == null;

        if (toolsetAuthenticationType.equals(ToolsetAuthenticationType.OAUTH)) {
            if (toolSetAuthSettings.getRedirectUri() == null) {
                throw new IllegalArgumentException("Redirect URI is required for OAUTH type Authentication.");
            }

            boolean allOauthRequiredFieldsSet = toolSetAuthSettings.getClientId() != null && toolSetAuthSettings.getClientSecret() != null;

            if (!allOauthRequiredFieldsSet && !noneOfOauthRequiredFieldsSet) {
                throw new IllegalArgumentException("Define all client details fields or none for dynamic client registration.");
            }
        } else if (toolsetAuthenticationType.equals(ToolsetAuthenticationType.API_KEY)) {
            if (!noneOfOauthRequiredFieldsSet || toolSetAuthSettings.getRedirectUri() != null) {
                throw new IllegalArgumentException("Do not provide OAUTH specific fields for API key type Authentication.");
            }

            if (toolSetAuthSettings.getApiKeyHeader() == null) {
                throw new IllegalArgumentException("ApiKeyHeader field is required for API key type Authentication.");
            }
        } else if (toolsetAuthenticationType.equals(ToolsetAuthenticationType.NONE)
            && (!noneOfOauthRequiredFieldsSet || toolSetAuthSettings.getApiKeyHeader() != null)
        ) {
            throw new IllegalArgumentException("Do not provide OAUTH/API key specific fields for NONE type Authentication.");
        }
    }
}
