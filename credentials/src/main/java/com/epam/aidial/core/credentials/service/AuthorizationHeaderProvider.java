package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.credentials.AuthorizationHeader;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class AuthorizationHeaderProvider {

    private final ResourceCredentialsService resourceCredentialsService;

    public AuthorizationHeader createAuthorizationHeader(CredentialsLocator credentialsLocator,
                                                         ResourceAuthSettings resourceAuthSettings,
                                                         String userSub) {
        ResourceCredentials resourceCredentials = getRefreshedResourceCredentials(credentialsLocator, resourceAuthSettings, userSub);
        if (resourceCredentials == null) {
            return null;
        }
        if (AuthenticationType.OAUTH.equals(resourceCredentials.getAuthenticationType())) {
            return AuthorizationHeader
                .builder()
                .headerName("Authorization")
                .headerValue("Bearer " + resourceCredentials.getAccessToken())
                .build();
        } else if (AuthenticationType.API_KEY.equals(resourceCredentials.getAuthenticationType())) {
            return AuthorizationHeader
                .builder()
                .headerName(resourceCredentials.getApiKeyHeader())
                .headerValue(resourceCredentials.getApiKey())
                .build();
        }
        return null;
    }

    public ResourceCredentials getRefreshedResourceCredentials(CredentialsLocator credentialsLocator,
                                                               ResourceAuthSettings authSettings,
                                                               String userSub) {
        if (authSettings.getAuthenticationType() == AuthenticationType.NONE) {
            return null;
        }

        try {
            ResourceCredentials userCredentials = resourceCredentialsService.getAndRefreshCredentials(
                    credentialsLocator.getCredentialsDescriptors().get(CredentialsLevel.USER),
                    authSettings
            );
            if (userCredentials != null
                    && userCredentials.getCredentialsLevel().equals(CredentialsLevel.USER)
                    && userCredentials.getUserSub().equals(userSub)) {
                return userCredentials;
            }
        } catch (ResourceNotFoundException e) {
            log.debug(e.getMessage(), e); // if User credentials are not found - let's look for Global one
        }

        ResourceCredentials globalCredentials = resourceCredentialsService.getAndRefreshCredentials(
                credentialsLocator.getCredentialsDescriptors().get(CredentialsLevel.GLOBAL),
                authSettings
        );
        if (globalCredentials != null
                && globalCredentials.getCredentialsLevel().equals(CredentialsLevel.GLOBAL)) {
            return globalCredentials;
        }

        throw new ResourceNotFoundException("Credentials (Global or Personal) for Resource %s not found".formatted(credentialsLocator.getResourceId()));
    }

}
