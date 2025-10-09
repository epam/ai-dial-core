package com.epam.aidial.core.credentials.validation;

import com.epam.aidial.core.credentials.service.ResourceAuthSettingsChangeMode;

import java.util.Set;

public class OauthAuthSettingsValidator extends BaseAuthSettingsValidator {

    @Override
    protected ResourceAuthSettingsValidationFields getValidationRules(ResourceAuthSettingsChangeMode resourceAuthSettingsChangeMode) {
        return switch (resourceAuthSettingsChangeMode) {
            case CREATE_DYNAMIC_CLIENT -> getOauthWithDynamicRegistrationValidationRules();
            case CREATE_STATIC_CLIENT -> getOauthWithStaticRegistrationValidationRules();
            case NO_CLIENT_CHANGES -> getOauthWithNoAuthTypeChangeValidationRules();
        };
    }

    private ResourceAuthSettingsValidationFields getOauthWithStaticRegistrationValidationRules() {
        return ResourceAuthSettingsValidationFields.builder()
                .requiredFields(Set.of(
                        ResourceAuthSettingsField.REDIRECT_URI,
                        ResourceAuthSettingsField.CLIENT_ID,
                        ResourceAuthSettingsField.CLIENT_SECRET,
                        ResourceAuthSettingsField.AUTHORIZATION_ENDPOINT,
                        ResourceAuthSettingsField.TOKEN_ENDPOINT)
                )
                .forbiddenFields(Set.of(
                        ResourceAuthSettingsField.CODE_VERIFIER,
                        ResourceAuthSettingsField.API_KEY_HEADER)
                ).build();
    }

    private ResourceAuthSettingsValidationFields getOauthWithDynamicRegistrationValidationRules() {
        return ResourceAuthSettingsValidationFields.builder()
                .requiredFields(Set.of(ResourceAuthSettingsField.REDIRECT_URI))
                .forbiddenFields(Set.of(
                        ResourceAuthSettingsField.CLIENT_ID,
                        ResourceAuthSettingsField.CLIENT_SECRET,
                        ResourceAuthSettingsField.AUTHORIZATION_ENDPOINT,
                        ResourceAuthSettingsField.TOKEN_ENDPOINT,
                        ResourceAuthSettingsField.CODE_CHALLENGE,
                        ResourceAuthSettingsField.CODE_VERIFIER,
                        ResourceAuthSettingsField.CODE_CHALLENGE_METHOD,
                        ResourceAuthSettingsField.SCOPES_SUPPORTED,
                        ResourceAuthSettingsField.API_KEY_HEADER)
                ).build();
    }

    private ResourceAuthSettingsValidationFields getOauthWithNoAuthTypeChangeValidationRules() {
        return ResourceAuthSettingsValidationFields.builder()
                .requiredFields(Set.of(
                        ResourceAuthSettingsField.REDIRECT_URI,
                        ResourceAuthSettingsField.CLIENT_ID,
                        ResourceAuthSettingsField.AUTHORIZATION_ENDPOINT,
                        ResourceAuthSettingsField.TOKEN_ENDPOINT)
                )
                .forbiddenFields(Set.of(
                        ResourceAuthSettingsField.CODE_VERIFIER,
                        ResourceAuthSettingsField.API_KEY_HEADER)
                ).build();
    }
}
