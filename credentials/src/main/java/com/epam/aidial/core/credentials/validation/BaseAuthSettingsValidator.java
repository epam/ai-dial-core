package com.epam.aidial.core.credentials.validation;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsChangeMode;

import java.util.EnumMap;
import java.util.Map;

public abstract class BaseAuthSettingsValidator implements AuthSettingsValidator {

    public void validate(ResourceAuthSettings resourceAuthSettings,
                         ResourceAuthSettingsChangeMode resourceAuthSettingsChangeMode) {
        ResourceAuthSettingsValidationFields validationFields = getValidationRules(resourceAuthSettingsChangeMode);
        Map<ResourceAuthSettingsField, Object> providedFields = extractFields(resourceAuthSettings);

        for (ResourceAuthSettingsField requiredField : validationFields.getRequiredFields()) {
            if (!providedFields.containsKey(requiredField) || providedFields.get(requiredField) == null) {
                throw new IllegalArgumentException("Field '%s' is required for %s authentication."
                        .formatted(requiredField, resourceAuthSettings.getAuthenticationType()));
            }
        }

        for (ResourceAuthSettingsField forbiddenField : validationFields.getForbiddenFields()) {
            if (providedFields.containsKey(forbiddenField) && providedFields.get(forbiddenField) != null) {
                throw new IllegalArgumentException("Field '%s' is forbidden for %s authentication."
                        .formatted(forbiddenField, resourceAuthSettings.getAuthenticationType()));
            }
        }
    }

    private Map<ResourceAuthSettingsField, Object> extractFields(ResourceAuthSettings resourceAuthSettings) {
        Map<ResourceAuthSettingsField, Object> fields = new EnumMap<>(ResourceAuthSettingsField.class);
        fields.put(ResourceAuthSettingsField.CLIENT_ID, resourceAuthSettings.getClientId());
        fields.put(ResourceAuthSettingsField.CLIENT_SECRET, resourceAuthSettings.getClientSecret());
        fields.put(ResourceAuthSettingsField.REDIRECT_URI, resourceAuthSettings.getRedirectUri());
        fields.put(ResourceAuthSettingsField.AUTHORIZATION_ENDPOINT, resourceAuthSettings.getAuthorizationEndpoint());
        fields.put(ResourceAuthSettingsField.TOKEN_ENDPOINT, resourceAuthSettings.getTokenEndpoint());
        fields.put(ResourceAuthSettingsField.CODE_CHALLENGE, resourceAuthSettings.getCodeChallenge());
        fields.put(ResourceAuthSettingsField.CODE_VERIFIER, resourceAuthSettings.getCodeVerifier());
        fields.put(ResourceAuthSettingsField.CODE_CHALLENGE_METHOD, resourceAuthSettings.getCodeChallengeMethod());
        fields.put(ResourceAuthSettingsField.SCOPES_SUPPORTED, resourceAuthSettings.getScopesSupported());
        fields.put(ResourceAuthSettingsField.API_KEY_HEADER, resourceAuthSettings.getApiKeyHeader());
        return fields;
    }

    protected abstract ResourceAuthSettingsValidationFields getValidationRules(ResourceAuthSettingsChangeMode resourceAuthSettingsChangeMode);

}