package com.epam.aidial.core.credentials.validation;

import com.epam.aidial.core.credentials.service.ResourceAuthSettingsChangeMode;

import java.util.Set;

public class NoneAuthSettingsValidator extends BaseAuthSettingsValidator {

    @Override
    protected ResourceAuthSettingsValidationFields getValidationRules(ResourceAuthSettingsChangeMode resourceAuthSettingsChangeMode) {
        return new ResourceAuthSettingsValidationFields(
                Set.of(),
                Set.of(
                        ResourceAuthSettingsField.CLIENT_ID,
                        ResourceAuthSettingsField.CLIENT_SECRET,
                        ResourceAuthSettingsField.REDIRECT_URI,
                        ResourceAuthSettingsField.AUTHORIZATION_ENDPOINT,
                        ResourceAuthSettingsField.TOKEN_ENDPOINT,
                        ResourceAuthSettingsField.CODE_CHALLENGE,
                        ResourceAuthSettingsField.CODE_VERIFIER,
                        ResourceAuthSettingsField.CODE_CHALLENGE_METHOD,
                        ResourceAuthSettingsField.SCOPES_SUPPORTED,
                        ResourceAuthSettingsField.API_KEY_HEADER
                )
        );
    }
}
