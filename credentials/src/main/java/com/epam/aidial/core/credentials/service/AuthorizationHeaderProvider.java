package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.credentials.AuthorizationHeader;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class AuthorizationHeaderProvider {

    private final ResourceCredentialsService resourceCredentialsService;

    public AuthorizationHeader createAuthorizationHeader(CredentialsLocator credentialsLocator,
                                                         ResourceAuthSettings resourceAuthSettings,
                                                         String userSub) {
        ResourceCredentials resourceCredentials = resourceCredentialsService.getRefreshedResourceCredentials(
                credentialsLocator, resourceAuthSettings, userSub);
        return createAuthorizationHeader(resourceCredentials);
    }

    public AuthorizationHeader createAuthorizationHeader(ResourceCredentials resourceCredentials) {
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

}
