package com.epam.aidial.core.credentials.validation;

import com.epam.aidial.core.credentials.service.ResourceAuthSettingsChangeMode;

import java.util.Set;

/**
 * The declaration carries no credential material: the target is DIAL, so the caller acts as the user via that
 * user's offline credentials rather than anything configured here. Every OAuth and API-key field is rejected
 * so a misconfiguration fails at write time instead of looking like a connection that never works.
 */
public class DialNativeAuthSettingsValidator extends BaseAuthSettingsValidator {

    @Override
    protected ResourceAuthSettingsValidationFields getValidationFields(ResourceAuthSettingsChangeMode changeMode) {
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
