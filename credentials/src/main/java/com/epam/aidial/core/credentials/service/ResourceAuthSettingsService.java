package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.ResourceAuthStatus;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;
import com.epam.aidial.core.credentials.data.registration.ClientRegistration;
import com.epam.aidial.core.credentials.service.registration.ResourceRegistrationService;
import com.epam.aidial.core.credentials.service.token.TokenRefreshStrategyFactory;
import com.epam.aidial.core.credentials.validation.AuthSettingsValidator;
import com.epam.aidial.core.credentials.validation.AuthSettingsValidatorFactory;
import com.nimbusds.oauth2.sdk.pkce.CodeChallenge;
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod;
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class ResourceAuthSettingsService {

    private final ResourceRegistrationService resourceRegistrationService;
    private final ResourceCredentialsService resourceCredentialsService;
    private final TokenRefreshStrategyFactory tokenRefreshStrategyFactory;
    private final AuthSettingsValidatorFactory validatorFactory;

    /**
     * Processes the resource authentication settings for a given `ToolSet`.
     *
     * <p>This method validates the new `ToolSet` authentication settings, optionally registers
     * clients dynamically or statically, and ensures proper handling of fields like `clientSecret`,
     * `codeChallenge`, and `codeVerifier` during updates.</p>
     *
     * @param updatedToolSet The new `ToolSet` configuration being saved
     * @param existingToolSet The existing `ToolSet` configuration, or null if creating a new `ToolSet`
     * @throws IllegalArgumentException if `ResourceAuthSettings` is not defined
     */
    public void processResourceAuthSettings(ToolSet updatedToolSet,
                                            ToolSet existingToolSet) {
        ResourceAuthSettings toolSetAuthSettings = updatedToolSet.getAuthSettings();

        if (toolSetAuthSettings == null) {
            throw new IllegalArgumentException("ResourceAuthSettings is not defined for Resource: " + updatedToolSet.getName());
        }

        boolean requiresClientRegistration = requiresClientRegistration(updatedToolSet, existingToolSet);
        boolean requiresDynamicClientRegistration = requiresDynamicClientRegistration(toolSetAuthSettings);

        ResourceAuthSettingsChangeMode resourceAuthSettingsChangeMode = getResourceAuthSettingsChangeMode(
                requiresClientRegistration, requiresDynamicClientRegistration);
        validateResourceAuthSettings(toolSetAuthSettings, resourceAuthSettingsChangeMode);

        if (resourceAuthSettingsChangeMode == ResourceAuthSettingsChangeMode.CREATE_DYNAMIC_CLIENT
                || resourceAuthSettingsChangeMode == ResourceAuthSettingsChangeMode.CREATE_STATIC_CLIENT) {
            enrichResourceAuthSettings(updatedToolSet.getName(), updatedToolSet.getEndpoint(), toolSetAuthSettings, requiresDynamicClientRegistration);
        } else if (AuthenticationType.OAUTH.equals(toolSetAuthSettings.getAuthenticationType())
                && existingToolSet != null
                && existingToolSet.getAuthSettings() != null) {
            ResourceAuthSettings existingResourceAuthSettings = existingToolSet.getAuthSettings();

            // do not re-write clientSecret with null values
            if (toolSetAuthSettings.getClientSecret() == null) {
                toolSetAuthSettings.setClientSecret(existingResourceAuthSettings.getClientSecret());
            }

            // do not re-write auto-generated codeChallenge/codeVerifier fields
            toolSetAuthSettings.setCodeChallenge(existingResourceAuthSettings.getCodeChallenge());
            toolSetAuthSettings.setCodeVerifier(existingResourceAuthSettings.getCodeVerifier());
        }
    }

    /**
     * Validates the resource authentication settings using the appropriate validator.
     *
     * @param toolSetAuthSettings The `ResourceAuthSettings` to validate
     * @param resourceAuthSettingsChangeMode The mode of authentication changes (e.g., CREATE_DYNAMIC_CLIENT)
     */
    private void validateResourceAuthSettings(ResourceAuthSettings toolSetAuthSettings,
                                              ResourceAuthSettingsChangeMode resourceAuthSettingsChangeMode) {
        AuthSettingsValidator authSettingsValidator = validatorFactory.getValidator(toolSetAuthSettings.getAuthenticationType());
        authSettingsValidator.validate(toolSetAuthSettings, resourceAuthSettingsChangeMode);
    }

    /**
     * Enriches the resource authentication settings with metadata by registering clients dynamically or statically.
     *
     * @param resourceId The resource name
     * @param resourceEndpoint The resource endpoint (URL)
     * @param resourceAuthSettings The `ResourceAuthSettings` being enriched
     * @param oauthDynamicClientRegistrationRequired Whether dynamic client registration is needed
     */
    private void enrichResourceAuthSettings(String resourceId,
                                            String resourceEndpoint,
                                            ResourceAuthSettings resourceAuthSettings,
                                            boolean oauthDynamicClientRegistrationRequired) {
        ClientRegistration clientRegistration = resourceRegistrationService.register(
                resourceId, resourceEndpoint, resourceAuthSettings, oauthDynamicClientRegistrationRequired);

        resourceAuthSettings.setClientId(clientRegistration.getClientId());
        resourceAuthSettings.setClientSecret(clientRegistration.getClientSecret());
        resourceAuthSettings.setAuthorizationEndpoint(clientRegistration.getAuthorizationEndpoint());
        resourceAuthSettings.setTokenEndpoint(clientRegistration.getTokenEndpoint());
        resourceAuthSettings.setRedirectUri(clientRegistration.getRedirectUri());
        resourceAuthSettings.setScopesSupported(clientRegistration.getScopesSupported());
        setCodeChallengeProperties(resourceAuthSettings, clientRegistration.getCodeChallengeMethod());
    }

    public void setResourceAuthStatuses(CredentialsLocator credentialsLocator,
                                        ResourceAuthSettings resourceAuthSettings,
                                        String userSub) {
        List<ResourceCredentials> allResourceCredentials = resourceCredentialsService.getAllResourceCredentials(credentialsLocator);
        setUserAuthStatus(resourceAuthSettings, allResourceCredentials, userSub);
        setGlobalAuthStatus(resourceAuthSettings, allResourceCredentials);
    }

    private void setUserAuthStatus(ResourceAuthSettings resourceAuthSettings,
                                   List<ResourceCredentials> resourceCredentialsList,
                                   String userSub) {
        Optional<ResourceCredentials> userResourceCredentials = resourceCredentialsList.stream()
                .filter(resourceCredentials -> resourceCredentials.getCredentialsLevel().equals(CredentialsLevel.USER)
                    && tokenRefreshStrategyFactory.getTokenValidatorStrategy(resourceCredentials.getAuthenticationType())
                        .hasUnexpiredToken(resourceCredentials)
                        && userSub.equals(resourceCredentials.getUserSub()))
                .findFirst();
        if (userResourceCredentials.isPresent()) {
            resourceAuthSettings.setUserLevelAuthStatus(ResourceAuthStatus.SIGNED_IN);
        } else {
            resourceAuthSettings.setUserLevelAuthStatus(ResourceAuthStatus.SIGNED_OUT);
        }
    }

    private void setGlobalAuthStatus(ResourceAuthSettings resourceAuthSettings,
                                     List<ResourceCredentials> resourceCredentialsList) {
        Optional<ResourceCredentials> globalResourceCredentials = resourceCredentialsList.stream()
                .filter(resourceCredentials -> resourceCredentials.getCredentialsLevel().equals(CredentialsLevel.GLOBAL)
                    && tokenRefreshStrategyFactory.getTokenValidatorStrategy(resourceCredentials.getAuthenticationType())
                        .hasUnexpiredToken(resourceCredentials))
                .findFirst();
        if (globalResourceCredentials.isPresent()) {
            resourceAuthSettings.setGlobalAuthStatus(ResourceAuthStatus.SIGNED_IN);
        } else {
            resourceAuthSettings.setGlobalAuthStatus(ResourceAuthStatus.SIGNED_OUT);
        }
    }

    private void setCodeChallengeProperties(ResourceAuthSettings resourceAuthSettings,
                                            String codeChallengeMethod) {
        if (codeChallengeMethod != null) {
            CodeVerifier codeVerifier = new CodeVerifier();
            CodeChallengeMethod parsedCodeChallengeMethod = CodeChallengeMethod.parse(codeChallengeMethod);
            CodeChallenge codeChallenge = CodeChallenge.compute(parsedCodeChallengeMethod, codeVerifier);

            resourceAuthSettings.setCodeChallenge(codeChallenge.getValue());
            resourceAuthSettings.setCodeVerifier(codeVerifier.getValue());
            resourceAuthSettings.setCodeChallengeMethod(parsedCodeChallengeMethod.getValue());
        }
    }

    /**
     * Determines whether client registration is required for the given ToolSet.
     *
     * <p>Client registration is required in the following cases:
     * <ul>
     *     <li>If the authentication type is `OAUTH` and no ToolSet exists</li>
     *     <li>If the authentication type changes from non-OAUTH to OAUTH</li>
     * </ul>
     *
     * @param toolSet The new `ToolSet` configuration being saved
     * @param existing The existing `ToolSet` configuration, or null if creating a new ToolSet
     * @return true if client registration is required, false otherwise
     */
    private boolean requiresClientRegistration(ToolSet toolSet, ToolSet existing) {
        ResourceAuthSettings newResourceAuthSettings = toolSet.getAuthSettings();

        // Do not register client for non-OAUTH auth types
        if (!AuthenticationType.OAUTH.equals(newResourceAuthSettings.getAuthenticationType())) {
            return false;
        }

        // Always register client when creating a new ToolSet with OAUTH auth type
        if (existing == null) {
            return true;
        }

        ResourceAuthSettings existingResourceAuthSettings = existing.getAuthSettings();

        // Ensure client registration applies only if the auth type was changed to OAUTH
        return !AuthenticationType.OAUTH.equals(existingResourceAuthSettings.getAuthenticationType());
    }

    /**
     * Determines whether dynamic client registration is required for the given resource authentication settings.
     * Dynamic registration is required when the settings include OAUTH authentication and do not specify `clientId` or `clientSecret`.
     *
     * @param resourceAuthSettings The `ResourceAuthSettings` to validate
     * @return true if dynamic client registration is required, false otherwise
     */
    private boolean requiresDynamicClientRegistration(ResourceAuthSettings resourceAuthSettings) {
        return AuthenticationType.OAUTH.equals(resourceAuthSettings.getAuthenticationType())
                && resourceAuthSettings.getClientId() == null
                && resourceAuthSettings.getClientSecret() == null;
    }

    /**
     * Determines the change mode for resource authentication settings.
     *
     * @param requiresClientRegistration Whether client registration is required
     * @param requiresDynamicClientRegistration Whether dynamic client registration is required
     * @return The `ResourceAuthSettingsChangeMode` describing the type of required action
     */
    private ResourceAuthSettingsChangeMode getResourceAuthSettingsChangeMode(boolean requiresClientRegistration,
                                                                             boolean requiresDynamicClientRegistration) {
        if (!requiresClientRegistration) {
            return ResourceAuthSettingsChangeMode.NO_CLIENT_CHANGES;
        }
        return requiresDynamicClientRegistration
                ? ResourceAuthSettingsChangeMode.CREATE_DYNAMIC_CLIENT
                : ResourceAuthSettingsChangeMode.CREATE_STATIC_CLIENT;
    }
}
