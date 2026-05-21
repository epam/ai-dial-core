package com.epam.aidial.core.credentials.validation;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.credentials.TokenEndpointAuthMethod;
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

    @Override
    public void validate(ResourceAuthSettings resourceAuthSettings,
                         ResourceAuthSettingsChangeMode resourceAuthSettingsChangeMode) {
        super.validate(resourceAuthSettings, resourceAuthSettingsChangeMode);
        // Reject unsupported token_endpoint_auth_method at save time so the failure surfaces
        // here rather than during the first sign-in attempt deep inside TokenService.
        // Dynamic registration ignores the user-supplied value (DCR response wins), so skip
        // the check there to avoid rejecting harmless input.
        if (resourceAuthSettingsChangeMode != ResourceAuthSettingsChangeMode.CREATE_DYNAMIC_CLIENT
                && resourceAuthSettings.getTokenEndpointAuthMethod() != null) {
            TokenEndpointAuthMethod.parse(resourceAuthSettings.getTokenEndpointAuthMethod());
        }
    }

    /**
     * Determines validation fields for `ResourceAuthSettings` based on the mode of authentication configuration change.
     *
     * <p>This method dynamically selects the validation fields depending on the change mode:
     * <ul>
     *     <li>`CREATE_DYNAMIC_CLIENT`: For dynamic client registration.</li>
     *     <li>`CREATE_STATIC_CLIENT`: For static client registration.</li>
     *     <li>`NO_CLIENT_CHANGES`: For cases where no authentication type changes are made during update.</li>
     * </ul>
     * </p>
     *
     * @param resourceAuthSettingsChangeMode The mode indicating whether new or updated OAuth settings are being processed.
     * @return Validation fields encapsulated as {@link ResourceAuthSettingsValidationFields}.
     *         These fields define required and forbidden fields based on the given change mode.
     */
    @Override
    protected ResourceAuthSettingsValidationFields getValidationFields(ResourceAuthSettingsChangeMode resourceAuthSettingsChangeMode) {
        return switch (resourceAuthSettingsChangeMode) {
            case CREATE_DYNAMIC_CLIENT -> getOauthWithDynamicRegistrationValidationFields();
            case CREATE_STATIC_CLIENT -> getOauthWithStaticRegistrationValidationFields();
            case NO_CLIENT_CHANGES -> getOauthWithNoAuthTypeChangeValidationFields();
        };
    }

    /**
     * Defines validation fields for static client registration with OAuth settings.
     *
     * <p>Static registration requires the client explicitly provide details like `CLIENT_ID`
     * and `CLIENT_SECRET` as well as endpoints (authorization, token).</p>
     *
     * <p><strong>Use Cases:</strong></p>
     * <ul>
     *     <li>Creating a new secured resource with static client registration.</li>
     *     <li>Updating authentication type from `NONE` or `API_KEY` to `OAUTH` with explicit static client settings.</li>
     * </ul>
     *
     * @return Validation fields for static client registration, including required and forbidden fields.
     */
    private ResourceAuthSettingsValidationFields getOauthWithStaticRegistrationValidationFields() {
        return ResourceAuthSettingsValidationFields.builder()
                .requiredFields(Set.of(
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
     * Defines validation fields for dynamic client registration with OAuth settings.
     *
     * <p>Dynamic registration simplifies the process of client registration, requiring only the `REDIRECT_URI` field.
     * All other fields, like `CLIENT_ID` and `CLIENT_SECRET`, are forbidden as they will be generated dynamically.</p>
     *
     * <p><strong>Use Cases:</strong></p>
     * <ul>
     *     <li>Creating a new secured resource with dynamic client registration.</li>
     *     <li>Updating authentication type from `NONE` or `API_KEY` to `OAUTH` with dynamic client registration.</li>
     * </ul>
     *
     * @return Validation fields for dynamic client registration, including required and forbidden fields.
     */
    private ResourceAuthSettingsValidationFields getOauthWithDynamicRegistrationValidationFields() {
        return ResourceAuthSettingsValidationFields.builder()
                .requiredFields(Set.of())
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
     * Defines validation fields for secured resource updates where authentication type remains unchanged as `OAUTH`.
     *
     * <p>In this scenario, required fields like `CLIENT_ID`, `AUTHORIZATION_ENDPOINT`, and `TOKEN_ENDPOINT`
     * must still be provided, but forbidden fields (like `API_KEY_HEADER`) must not be included.</p>
     *
     * <p><strong>Use Cases:</strong></p>
     * <ul>
     *     <li>Ensures consistency for updates where OAuth-specific settings (endpoints) are validated.</li>
     * </ul>
     *
     * @return Validation fields ensuring no changes to the authentication type (`OAUTH` remains unchanged).
     */
    private ResourceAuthSettingsValidationFields getOauthWithNoAuthTypeChangeValidationFields() {
        return ResourceAuthSettingsValidationFields.builder()
                .requiredFields(Set.of(
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
