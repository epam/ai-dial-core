package com.epam.aidial.core.credentials.validation;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsChangeMode;

import java.util.EnumSet;
import java.util.Set;

public abstract class BaseAuthSettingsValidator implements AuthSettingsValidator {

    public void validate(ResourceAuthSettings resourceAuthSettings,
                         ResourceAuthSettingsChangeMode resourceAuthSettingsChangeMode) {
        ResourceAuthSettingsValidationFields validationFields = getValidationFields(resourceAuthSettingsChangeMode);
        Set<ResourceAuthSettingsField> fieldsWithValues = extractFields(resourceAuthSettings);

        for (ResourceAuthSettingsField requiredField : validationFields.getRequiredFields()) {
            if (!fieldsWithValues.contains(requiredField)) {
                throw new IllegalArgumentException("Field '%s' is required for %s authentication."
                        .formatted(requiredField, resourceAuthSettings.getAuthenticationType()));
            }
        }

        for (ResourceAuthSettingsField forbiddenField : validationFields.getForbiddenFields()) {
            if (fieldsWithValues.contains(forbiddenField)) {
                throw new IllegalArgumentException("Field '%s' is forbidden for %s authentication."
                        .formatted(forbiddenField, resourceAuthSettings.getAuthenticationType()));
            }
        }
    }

    private Set<ResourceAuthSettingsField> extractFields(ResourceAuthSettings resourceAuthSettings) {
        Set<ResourceAuthSettingsField> fieldsWithValues = EnumSet.noneOf(ResourceAuthSettingsField.class);

        if (resourceAuthSettings.getClientId() != null) {
            fieldsWithValues.add(ResourceAuthSettingsField.CLIENT_ID);
        }
        if (resourceAuthSettings.getClientSecret() != null) {
            fieldsWithValues.add(ResourceAuthSettingsField.CLIENT_SECRET);
        }
        if (resourceAuthSettings.getRedirectUri() != null) {
            fieldsWithValues.add(ResourceAuthSettingsField.REDIRECT_URI);
        }
        if (resourceAuthSettings.getAuthorizationEndpoint() != null) {
            fieldsWithValues.add(ResourceAuthSettingsField.AUTHORIZATION_ENDPOINT);
        }
        if (resourceAuthSettings.getTokenEndpoint() != null) {
            fieldsWithValues.add(ResourceAuthSettingsField.TOKEN_ENDPOINT);
        }
        if (resourceAuthSettings.getCodeChallenge() != null) {
            fieldsWithValues.add(ResourceAuthSettingsField.CODE_CHALLENGE);
        }
        if (resourceAuthSettings.getCodeVerifier() != null) {
            fieldsWithValues.add(ResourceAuthSettingsField.CODE_VERIFIER);
        }
        if (resourceAuthSettings.getCodeChallengeMethod() != null) {
            fieldsWithValues.add(ResourceAuthSettingsField.CODE_CHALLENGE_METHOD);
        }
        if (resourceAuthSettings.getScopesSupported() != null) {
            fieldsWithValues.add(ResourceAuthSettingsField.SCOPES_SUPPORTED);
        }
        if (resourceAuthSettings.getApiKeyHeader() != null) {
            fieldsWithValues.add(ResourceAuthSettingsField.API_KEY_HEADER);
        }

        return fieldsWithValues;
    }

    protected abstract ResourceAuthSettingsValidationFields getValidationFields(ResourceAuthSettingsChangeMode resourceAuthSettingsChangeMode);

}