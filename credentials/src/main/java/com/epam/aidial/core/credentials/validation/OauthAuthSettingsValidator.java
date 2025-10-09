package com.epam.aidial.core.credentials.validation;

import com.epam.aidial.core.credentials.service.ResourceAuthSettingsChangeMode;

import java.util.Set;

/**
 * Validator responsible for validating OAuth-specific authentication settings.
 *
 * <p>Ensures all required fields are provided and forbidden fields are excluded
 * based on different modes of authentication (dynamic or static client registration)
 * and changes (creation vs update).</p>
 *
 * <p>This class extends {@link BaseAuthSettingsValidator} and provides OAuth-specific
 * validation rules for different `ResourceAuthSettingsChangeMode`s:</p>
 * <ul>
 *     <li>`CREATE_DYNAMIC_CLIENT` for dynamic client registration.</li>
 *     <li>`CREATE_STATIC_CLIENT` for static client registration.</li>
 *     <li>`NO_CLIENT_CHANGES` for cases where the authentication type remains OAuth with no changes.</li>
 * </ul>
 */
public class OauthAuthSettingsValidator extends BaseAuthSettingsValidator {

    /**
     * Determines validation rules for `ResourceAuthSettings` based on the mode of authentication configuration change.
     *
     * <p>This method dynamically selects the validation rules depending on the change mode:
     * <ul>
     *     <li>`CREATE_DYNAMIC_CLIENT`: For dynamic client registration.</li>
     *     <li>`CREATE_STATIC_CLIENT`: For static client registration.</li>
     *     <li>`NO_CLIENT_CHANGES`: For cases where no authentication type changes are made during update.</li>
     * </ul>
     * </p>
     *
     * @param resourceAuthSettingsChangeMode The mode indicating whether new or updated OAuth settings are being processed.
     * @return Validation rules encapsulated as {@link ResourceAuthSettingsValidationFields}.
     *         These rules define required and forbidden fields based on the given change mode.
     */
    @Override
    protected ResourceAuthSettingsValidationFields getValidationRules(ResourceAuthSettingsChangeMode resourceAuthSettingsChangeMode) {
        return switch (resourceAuthSettingsChangeMode) {
            case CREATE_DYNAMIC_CLIENT -> getOauthWithDynamicRegistrationValidationRules();
            case CREATE_STATIC_CLIENT -> getOauthWithStaticRegistrationValidationRules();
            case NO_CLIENT_CHANGES -> getOauthWithNoAuthTypeChangeValidationRules();
        };
    }

    /**
     * Defines validation rules for static client registration with OAuth settings.
     *
     * <p>Static registration requires the client explicitly provide details like `CLIENT_ID`
     * and `CLIENT_SECRET` as well as endpoints (authorization, token).</p>
     *
     * <p><strong>Use Cases:</strong></p>
     * <ul>
     *     <li>Creating a new ToolSet with static client registration.</li>
     *     <li>Updating authentication type from `NONE` or `API_KEY` to `OAUTH` with explicit static client settings.</li>
     * </ul>
     *
     * @return Validation rules for static client registration, including required and forbidden fields.
     */
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

    /**
     * Defines validation rules for dynamic client registration with OAuth settings.
     *
     * <p>Dynamic registration simplifies the process of client registration, requiring only the `REDIRECT_URI` field.
     * All other fields, like `CLIENT_ID` and `CLIENT_SECRET`, are forbidden as they will be generated dynamically.</p>
     *
     * <p><strong>Use Cases:</strong></p>
     * <ul>
     *     <li>Creating a new ToolSet with dynamic client registration.</li>
     *     <li>Updating authentication type from `NONE` or `API_KEY` to `OAUTH` with dynamic client registration.</li>
     * </ul>
     *
     * @return Validation rules for dynamic client registration, including required and forbidden fields.
     */
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

    /**
     * Defines validation rules for ToolSet updates where authentication type remains unchanged as `OAUTH`.
     *
     * <p>In this scenario, required fields like `CLIENT_ID`, `AUTHORIZATION_ENDPOINT`, and `TOKEN_ENDPOINT`
     * must still be provided, but forbidden fields (like `API_KEY_HEADER`) must not be included.</p>
     *
     * <p><strong>Use Cases:</strong></p>
     * <ul>
     *     <li>Ensures consistency for updates where OAuth-specific settings (endpoints) are validated.</li>
     * </ul>
     *
     * @return Validation rules ensuring no changes to the authentication type (`OAUTH` remains unchanged).
     */
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
