package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.ResourceAuthStatus;
import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;
import com.epam.aidial.core.credentials.data.registration.ClientRegistration;
import com.epam.aidial.core.credentials.exception.CredentialsInternalException;
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
        try {
            if (resourceAuthSettings == null) {
                throw new IllegalArgumentException("ResourceAuthSettings is not defined for Resource: " + resourceId);
            }

            resourceAuthSettingsValidator.validate(resourceAuthSettings);

            if (resourceAuthSettings.getAuthenticationType() != AuthenticationType.OAUTH) {
                // do nothing
                return;
            }

            ClientRegistration clientRegistration = shouldRegisterResourceDynamically(resourceAuthSettings)
                                                    ? resourceRegistrationService.createDynamicResourceRegistration(
                                                        resourceId, resourceEndpoint, resourceAuthSettings.getRedirectUri())
                                                    : resourceRegistrationService.createStaticResourceRegistration(
                                                        resourceId, resourceEndpoint, resourceAuthSettings);

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
        } catch (Exception e) {
            log.error("Can't register client for Resource: {}", e.getMessage(), e);
            throw new CredentialsInternalException("Can't register client for Resource: %s.".formatted(resourceId), e);
        }
    }

    private boolean shouldRegisterResourceDynamically(ResourceAuthSettings resourceAuthSettings) {
        return AuthenticationType.OAUTH.equals(resourceAuthSettings.getAuthenticationType())
            && resourceAuthSettings.getClientId() == null
            && resourceAuthSettings.getClientSecret() == null;
    }

    public void setResourceAuthStatuses(String resourceId,
                                        ResourceAuthSettings resourceAuthSettings) {
        List<ResourceCredentials> allResourceCredentials = resourceCredentialsManager.getAllResourceCredentials(resourceId);
        setUserAuthStatus(resourceAuthSettings, allResourceCredentials);
        setGlobalAuthStatus(resourceAuthSettings, allResourceCredentials);
    }

    private void setUserAuthStatus(ResourceAuthSettings resourceAuthSettings,
                                   List<ResourceCredentials> resourceCredentialsList) {
        Optional<ResourceCredentials> userResourceCredentials = resourceCredentialsList.stream()
                .filter(resourceCredentials -> resourceCredentials.getCredentialsLevel().equals(CredentialsLevel.USER))
                .findFirst();
        if (userResourceCredentials.isPresent() && !userResourceCredentials.get().isTokenExpired()) {
            resourceAuthSettings.setUserLevelAuthStatus(ResourceAuthStatus.SIGNED_IN);
        } else {
            resourceAuthSettings.setUserLevelAuthStatus(ResourceAuthStatus.SIGNED_OUT);
        }
    }

    private void setGlobalAuthStatus(ResourceAuthSettings resourceAuthSettings,
                                     List<ResourceCredentials> resourceCredentialsList) {
        Optional<ResourceCredentials> globalResourceCredentials = resourceCredentialsList.stream()
                .filter(resourceCredentials -> resourceCredentials.getCredentialsLevel().equals(CredentialsLevel.GLOBAL))
                .findFirst();
        if (globalResourceCredentials.isPresent() && !globalResourceCredentials.get().isTokenExpired()) {
            resourceAuthSettings.setGlobalAuthStatus(ResourceAuthStatus.SIGNED_IN);
        } else {
            resourceAuthSettings.setGlobalAuthStatus(ResourceAuthStatus.SIGNED_OUT);
        }
    }
}
