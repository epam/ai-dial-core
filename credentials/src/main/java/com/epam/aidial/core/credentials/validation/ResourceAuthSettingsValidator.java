package com.epam.aidial.core.credentials.validation;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.ResourceAuthSettings;

public class ResourceAuthSettingsValidator {

    // TODO: add more validations
    public void validate(ResourceAuthSettings resourceAuthSettings) {
        AuthenticationType authenticationType = resourceAuthSettings.getAuthenticationType();

        boolean noneOfOauthRequiredFieldsSet = resourceAuthSettings.getClientId() == null && resourceAuthSettings.getClientSecret() == null;

        if (authenticationType.equals(AuthenticationType.OAUTH)) {
            if (resourceAuthSettings.getRedirectUri() == null) {
                throw new IllegalArgumentException("Redirect URI is required for OAUTH type Authentication.");
            }

            boolean allOauthRequiredFieldsSet = resourceAuthSettings.getClientId() != null && resourceAuthSettings.getClientSecret() != null;

            if (!allOauthRequiredFieldsSet && !noneOfOauthRequiredFieldsSet) {
                throw new IllegalArgumentException("Define all client details fields or none for dynamic client registration.");
            }
        } else if (authenticationType.equals(AuthenticationType.API_KEY)) {
            if (!noneOfOauthRequiredFieldsSet || resourceAuthSettings.getRedirectUri() != null) {
                throw new IllegalArgumentException("Do not provide OAUTH specific fields for API key type Authentication.");
            }

            if (resourceAuthSettings.getApiKeyHeader() == null) {
                throw new IllegalArgumentException("ApiKeyHeader field is required for API key type Authentication.");
            }
        } else if (authenticationType.equals(AuthenticationType.NONE)
                && (!noneOfOauthRequiredFieldsSet || resourceAuthSettings.getApiKeyHeader() != null)
        ) {
            throw new IllegalArgumentException("Do not provide OAUTH/API key specific fields for NONE type Authentication.");
        }
    }
}
