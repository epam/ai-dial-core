package com.epam.aidial.core.credentials.validation;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsChangeMode;
import org.apache.commons.lang3.StringUtils;

import java.util.EnumSet;
import java.util.List;
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

    /**
     * A blank string or an empty list carries no value: clients send them for fields they left empty, and a
     * required field filled that way is unusable downstream. Treating them as absent makes such a request fail
     * here with the required-field error instead of storing a broken configuration.
     */
    private Set<ResourceAuthSettingsField> extractFields(ResourceAuthSettings resourceAuthSettings) {
        Set<ResourceAuthSettingsField> fieldsWithValues = EnumSet.noneOf(ResourceAuthSettingsField.class);

        addIfHasValue(fieldsWithValues, ResourceAuthSettingsField.CLIENT_ID, resourceAuthSettings.getClientId());
        addIfHasValue(fieldsWithValues, ResourceAuthSettingsField.CLIENT_SECRET, resourceAuthSettings.getClientSecret());
        addIfHasValue(fieldsWithValues, ResourceAuthSettingsField.REDIRECT_URI, resourceAuthSettings.getRedirectUri());
        addIfHasValue(fieldsWithValues, ResourceAuthSettingsField.AUTHORIZATION_ENDPOINT, resourceAuthSettings.getAuthorizationEndpoint());
        addIfHasValue(fieldsWithValues, ResourceAuthSettingsField.TOKEN_ENDPOINT, resourceAuthSettings.getTokenEndpoint());
        addIfHasValue(fieldsWithValues, ResourceAuthSettingsField.CODE_CHALLENGE, resourceAuthSettings.getCodeChallenge());
        addIfHasValue(fieldsWithValues, ResourceAuthSettingsField.CODE_VERIFIER, resourceAuthSettings.getCodeVerifier());
        addIfHasValue(fieldsWithValues, ResourceAuthSettingsField.CODE_CHALLENGE_METHOD, resourceAuthSettings.getCodeChallengeMethod());
        addIfHasValue(fieldsWithValues, ResourceAuthSettingsField.API_KEY_HEADER, resourceAuthSettings.getApiKeyHeader());

        List<String> scopesSupported = resourceAuthSettings.getScopesSupported();
        if (scopesSupported != null && !scopesSupported.isEmpty()) {
            fieldsWithValues.add(ResourceAuthSettingsField.SCOPES_SUPPORTED);
        }

        return fieldsWithValues;
    }

    private void addIfHasValue(Set<ResourceAuthSettingsField> fieldsWithValues, ResourceAuthSettingsField field, String value) {
        if (StringUtils.isNotBlank(value)) {
            fieldsWithValues.add(field);
        }
    }

    protected abstract ResourceAuthSettingsValidationFields getValidationFields(ResourceAuthSettingsChangeMode resourceAuthSettingsChangeMode);

}