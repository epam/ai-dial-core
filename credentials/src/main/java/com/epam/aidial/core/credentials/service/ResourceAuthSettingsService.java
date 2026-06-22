package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.ResourceAuthStatus;
import com.epam.aidial.core.config.SecuredResource;
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
     * Processes the resource authentication settings for a given `SecuredResource`.
     *
     * <p>This method validates the new `SecuredResource` authentication settings, optionally registers
     * clients dynamically or statically, and ensures proper handling of fields like `clientSecret`,
     * `codeChallenge`, and `codeVerifier` during updates.</p>
     *
     * @param updatedResource The new `SecuredResource` configuration being saved
     * @param existingResource The existing `SecuredResource` configuration, or null if creating a new `SecuredResource`
     * @throws IllegalArgumentException if `ResourceAuthSettings` is not defined
     */
    public void processResourceAuthSettings(SecuredResource updatedResource,
                                            SecuredResource existingResource) {
        ResourceAuthSettings resourceAuthSettings = updatedResource.getAuthSettings();

        if (resourceAuthSettings == null) {
            throw new IllegalArgumentException("ResourceAuthSettings is not defined for Resource: " + updatedResource.getName());
        }

        if (updatedResource.isForwardPerRequestKey() && resourceAuthSettings.getAuthenticationType() == AuthenticationType.API_KEY) {
            throw new IllegalArgumentException("Forward per request API key can't be along with authentication type %s"
                    .formatted(AuthenticationType.API_KEY.name()));
        }

        boolean requiresClientRegistration = requiresClientRegistration(updatedResource, existingResource);
        boolean requiresDynamicClientRegistration = requiresDynamicClientRegistration(resourceAuthSettings);

        ResourceAuthSettingsChangeMode resourceAuthSettingsChangeMode = getResourceAuthSettingsChangeMode(
                requiresClientRegistration, requiresDynamicClientRegistration);
        validateResourceAuthSettings(resourceAuthSettings, resourceAuthSettingsChangeMode);

        if (resourceAuthSettingsChangeMode == ResourceAuthSettingsChangeMode.CREATE_DYNAMIC_CLIENT
                || resourceAuthSettingsChangeMode == ResourceAuthSettingsChangeMode.CREATE_STATIC_CLIENT) {
            enrichResourceAuthSettings(updatedResource.getName(), updatedResource.getEndpoint(), resourceAuthSettings, requiresDynamicClientRegistration);
        } else if (AuthenticationType.OAUTH.equals(resourceAuthSettings.getAuthenticationType())
                && existingResource != null
                && existingResource.getAuthSettings() != null) {
            ResourceAuthSettings existingResourceAuthSettings = existingResource.getAuthSettings();

            // do not re-write clientSecret with null values
            if (resourceAuthSettings.getClientSecret() == null) {
                resourceAuthSettings.setClientSecret(existingResourceAuthSettings.getClientSecret());
            }

            // preserve tokenEndpointAuthMethod on updates that omit it
            if (resourceAuthSettings.getTokenEndpointAuthMethod() == null) {
                resourceAuthSettings.setTokenEndpointAuthMethod(existingResourceAuthSettings.getTokenEndpointAuthMethod());
            }

            resolveCodeChallengeSettings(resourceAuthSettings, existingResourceAuthSettings);
        }
    }

    /**
     * Preserves existing auto-generated codeChallenge/codeVerifier when codeChallengeMethod is unchanged (or omitted),
     * otherwise regenerates the pair under the new method.
     */
    private void resolveCodeChallengeSettings(ResourceAuthSettings resourceAuthSettings,
                                              ResourceAuthSettings existingResourceAuthSettings) {
        String newCodeChallengeMethod = resourceAuthSettings.getCodeChallengeMethod();
        String existingCodeChallengeMethod = existingResourceAuthSettings.getCodeChallengeMethod();
        if (newCodeChallengeMethod == null || newCodeChallengeMethod.equals(existingCodeChallengeMethod)) {
            resourceAuthSettings.setCodeChallengeMethod(existingCodeChallengeMethod);
            resourceAuthSettings.setCodeChallenge(existingResourceAuthSettings.getCodeChallenge());
            resourceAuthSettings.setCodeVerifier(existingResourceAuthSettings.getCodeVerifier());
        } else {
            setCodeChallengeProperties(resourceAuthSettings, newCodeChallengeMethod);
        }
    }

    /**
     * Validates the resource authentication settings using the appropriate validator.
     *
     * @param resourceAuthSettings The `ResourceAuthSettings` to validate
     * @param resourceAuthSettingsChangeMode The mode of authentication changes (e.g., CREATE_DYNAMIC_CLIENT)
     */
    private void validateResourceAuthSettings(ResourceAuthSettings resourceAuthSettings,
                                              ResourceAuthSettingsChangeMode resourceAuthSettingsChangeMode) {
        AuthSettingsValidator authSettingsValidator = validatorFactory.getValidator(resourceAuthSettings.getAuthenticationType());
        authSettingsValidator.validate(resourceAuthSettings, resourceAuthSettingsChangeMode);
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
        resourceAuthSettings.setTokenEndpointAuthMethod(clientRegistration.getTokenEndpointAuthMethod());
        setCodeChallengeProperties(resourceAuthSettings, clientRegistration.getCodeChallengeMethod());
    }

    public void setResourceAuthStatuses(CredentialsLocator credentialsLocator,
                                        ResourceAuthSettings resourceAuthSettings,
                                        String userId) {
        List<ResourceCredentials> allResourceCredentials = resourceCredentialsService.getAllResourceCredentials(credentialsLocator);
        setUserAuthStatus(resourceAuthSettings, allResourceCredentials, userId);
        setGlobalAuthStatus(resourceAuthSettings, allResourceCredentials);
    }

    /**
     * Enriches external-service auth settings with USER- and APPLICATION-level statuses
     * (external services use USER/APPLICATION levels, unlike toolsets which use USER/GLOBAL).
     */
    public void setExternalServiceAuthStatuses(CredentialsLocator credentialsLocator,
                                               ResourceAuthSettings resourceAuthSettings,
                                               String userId) {
        List<ResourceCredentials> all = resourceCredentialsService.getAllResourceCredentials(credentialsLocator);

        boolean userSignedIn = hasUnexpiredUserCredentials(all, userId);
        resourceAuthSettings.setUserLevelAuthStatus(userSignedIn ? ResourceAuthStatus.SIGNED_IN : ResourceAuthStatus.SIGNED_OUT);

        boolean appSignedIn = hasUnexpiredApplicationCredentials(all);
        resourceAuthSettings.setAppLevelAuthStatus(appSignedIn ? ResourceAuthStatus.SIGNED_IN : ResourceAuthStatus.SIGNED_OUT);
    }

    // USER-level credentials are scoped to a single signed-in user, so they are filtered by userId.
    private boolean hasUnexpiredUserCredentials(List<ResourceCredentials> all, String userId) {
        return all.stream().anyMatch(c -> c.getCredentialsLevel() == CredentialsLevel.USER
                && userId.equals(c.getUserId())
                && hasUnexpiredToken(c));
    }

    // APPLICATION-level credentials are shared by the app and not bound to any user, so no userId filter applies.
    private boolean hasUnexpiredApplicationCredentials(List<ResourceCredentials> all) {
        return all.stream().anyMatch(c -> c.getCredentialsLevel() == CredentialsLevel.APPLICATION
                && hasUnexpiredToken(c));
    }

    private boolean hasUnexpiredToken(ResourceCredentials credentials) {
        return tokenRefreshStrategyFactory.getTokenValidatorStrategy(credentials.getAuthenticationType())
                .hasUnexpiredToken(credentials);
    }

    private void setUserAuthStatus(ResourceAuthSettings resourceAuthSettings,
                                   List<ResourceCredentials> resourceCredentialsList,
                                   String userId) {
        Optional<ResourceCredentials> userResourceCredentials = resourceCredentialsList.stream()
                .filter(resourceCredentials -> resourceCredentials.getCredentialsLevel().equals(CredentialsLevel.USER)
                    && tokenRefreshStrategyFactory.getTokenValidatorStrategy(resourceCredentials.getAuthenticationType())
                        .hasUnexpiredToken(resourceCredentials)
                        && userId.equals(resourceCredentials.getUserId()))
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
     * Determines whether client registration is required for the given SecuredResource.
     *
     * <p>Client registration is required in the following cases:
     * <ul>
     *     <li>If the authentication type is `OAUTH` and no SecuredResource exists</li>
     *     <li>If the authentication type changes from non-OAUTH to OAUTH</li>
     * </ul>
     *
     * @param securedResource The new `SecuredResource` configuration being saved
     * @param existing The existing `SecuredResource` configuration, or null if creating a new SecuredResource
     * @return true if client registration is required, false otherwise
     */
    private boolean requiresClientRegistration(SecuredResource securedResource, SecuredResource existing) {
        ResourceAuthSettings newResourceAuthSettings = securedResource.getAuthSettings();

        // Do not register client for non-OAUTH auth types
        if (!AuthenticationType.OAUTH.equals(newResourceAuthSettings.getAuthenticationType())) {
            return false;
        }

        // Always register client when creating a new SecuredResource with OAUTH auth type
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
