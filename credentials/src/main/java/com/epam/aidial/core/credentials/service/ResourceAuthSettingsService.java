package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.ResourceAuthStatus;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;
import com.epam.aidial.core.credentials.data.registration.ClientRegistration;
import com.epam.aidial.core.credentials.service.registration.ResourceRegistrationService;
import com.epam.aidial.core.credentials.validation.ResourceAuthSettingsValidator;
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
    private final ResourceAuthSettingsValidator resourceAuthSettingsValidator;
    private final ResourceCredentialsManager resourceCredentialsManager;

    public void enrichResourceAuthSettings(String resourceId,
                                           String resourceEndpoint,
                                           ResourceAuthSettings resourceAuthSettings) {
        if (resourceAuthSettings == null) {
            throw new IllegalArgumentException("ResourceAuthSettings is not defined for Resource: " + resourceId);
        }

        resourceAuthSettingsValidator.validate(resourceAuthSettings);

        if (resourceAuthSettings.getAuthenticationType() != AuthenticationType.OAUTH) {
            // do nothing
            return;
        }

        ClientRegistration clientRegistration = resourceRegistrationService.registerResource(resourceId,
                resourceEndpoint, resourceAuthSettings, shouldRegisterResourceDynamically(resourceAuthSettings));

        CodeVerifier codeVerifier = new CodeVerifier();
        CodeChallengeMethod codeChallengeMethod = CodeChallengeMethod.parse(clientRegistration.getCodeChallengeMethod());
        CodeChallenge codeChallenge = CodeChallenge.compute(codeChallengeMethod, codeVerifier);

        resourceAuthSettings.setClientId(clientRegistration.getClientId());
        resourceAuthSettings.setClientSecret(clientRegistration.getClientSecret());
        resourceAuthSettings.setAuthorizationEndpoint(clientRegistration.getAuthorizationEndpoint());
        resourceAuthSettings.setTokenEndpoint(clientRegistration.getTokenEndpoint());
        resourceAuthSettings.setRedirectUri(clientRegistration.getRedirectUri());
        resourceAuthSettings.setCodeChallenge(codeChallenge.getValue());
        resourceAuthSettings.setCodeVerifier(codeVerifier.getValue());
        resourceAuthSettings.setCodeChallengeMethod(codeChallengeMethod.getValue());
    }

    private boolean shouldRegisterResourceDynamically(ResourceAuthSettings resourceAuthSettings) {
        return AuthenticationType.OAUTH.equals(resourceAuthSettings.getAuthenticationType())
                && resourceAuthSettings.getClientId() == null
                && resourceAuthSettings.getClientSecret() == null;
    }

    public void setResourceAuthStatuses(CredentialsLocator credentialsLocator,
                                        ResourceAuthSettings resourceAuthSettings,
                                        String userSub) {
        List<ResourceCredentials> allResourceCredentials = resourceCredentialsManager.getAllResourceCredentials(credentialsLocator);
        setUserAuthStatus(resourceAuthSettings, allResourceCredentials, userSub);
        setGlobalAuthStatus(resourceAuthSettings, allResourceCredentials);
    }

    private void setUserAuthStatus(ResourceAuthSettings resourceAuthSettings,
                                   List<ResourceCredentials> resourceCredentialsList,
                                   String userSub) {
        Optional<ResourceCredentials> userResourceCredentials = resourceCredentialsList.stream()
                .filter(resourceCredentials -> resourceCredentials.getCredentialsLevel().equals(CredentialsLevel.USER)
                    && resourceCredentials.getAuthenticationType().equals(AuthenticationType.OAUTH)
                    && userSub.equals(resourceCredentials.getUserSub())
                    && resourceCredentials.hasUnexpiredToken())
                .findFirst();
        // TODO: implement logic for API_KEY auth type
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
                    && resourceCredentials.getAuthenticationType().equals(AuthenticationType.OAUTH)
                    && resourceCredentials.hasUnexpiredToken()
                )
                .findFirst();
        // TODO: implement logic for API_KEY auth type
        if (globalResourceCredentials.isPresent()) {
            resourceAuthSettings.setGlobalAuthStatus(ResourceAuthStatus.SIGNED_IN);
        } else {
            resourceAuthSettings.setGlobalAuthStatus(ResourceAuthStatus.SIGNED_OUT);
        }
    }
}
