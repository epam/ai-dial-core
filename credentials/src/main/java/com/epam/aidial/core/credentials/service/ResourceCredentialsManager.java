package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.credentials.AuthorizationHeader;
import com.epam.aidial.core.credentials.data.credentials.CredentialsDescriptor;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;
import com.epam.aidial.core.credentials.data.credentials.ResourceSignInRequest;
import com.epam.aidial.core.credentials.data.credentials.ResourceSignOutRequest;
import com.epam.aidial.core.credentials.data.credentials.TokenResponse;
import com.epam.aidial.core.credentials.factory.ResourceCredentialsFactory;
import com.epam.aidial.core.credentials.factory.ResourceCredentialsFactoryProvider;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class ResourceCredentialsManager {

    private final ResourceCredentialsService resourceCredentialsService;
    private final TokenService tokenService;

    public ResourceCredentials createResourceCredentials(CredentialsDescriptor credentialsDescriptor,
                                                         ResourceAuthSettings resourceAuthSettings,
                                                         ResourceSignInRequest resourceSignInRequest,
                                                         String userSub) {
        ResourceCredentialsFactoryProvider resourceCredentialsFactoryProvider = new ResourceCredentialsFactoryProvider(tokenService);
        ResourceCredentialsFactory factory = resourceCredentialsFactoryProvider.getFactory(resourceSignInRequest.getAuthenticationType());

        ResourceCredentials resourceCredentials = factory.createCredentials(resourceSignInRequest.getUrl(), resourceAuthSettings, resourceSignInRequest);

        if (resourceSignInRequest.getCredentialsLevel().equals(CredentialsLevel.USER)) {
            resourceCredentials.setUserSub(userSub);
        }

        resourceCredentialsService.addResourceCredentials(credentialsDescriptor, resourceCredentials);
        log.info("Resource signIn done. {}", resourceSignInRequest.getUrl());
        return resourceCredentials;
    }

    public ResourceCredentials getResourceCredentials(CredentialsLocator credentialsLocator, ResourceAuthSettings authSettings, String userSub) {
        if (authSettings.getAuthenticationType() == AuthenticationType.NONE) {
            return null;
        }
        List<ResourceCredentials> resourceCredentialsList = resourceCredentialsService.getAllResourceCredentials(credentialsLocator);

        ResourceCredentials globalCredentials = null;

        String resourceId = credentialsLocator.getResourceId();

        for (ResourceCredentials credentials : resourceCredentialsList) {
            if (credentials.getCredentialsLevel() == CredentialsLevel.USER
                    && userSub != null
                    && userSub.equals(credentials.getUserSub())) {
                if (credentials.isTokenExpired()) {
                    updateExpiredResourceCredentials(credentials, resourceId, authSettings);
                    resourceCredentialsService.updateAllResourceCredentials(credentialsLocator, resourceCredentialsList);
                }
                return credentials;
            }

            if (credentials.getCredentialsLevel() == CredentialsLevel.GLOBAL) {
                if (credentials.isTokenExpired()) {
                    updateExpiredResourceCredentials(credentials, resourceId, authSettings);
                }
                globalCredentials = credentials;
            }
        }

        if (globalCredentials != null) {
            resourceCredentialsService.updateAllResourceCredentials(credentialsLocator, resourceCredentialsList);
            return globalCredentials;
        }

        // TODO: implement logic for APP level creds

        throw new ResourceNotFoundException("Credentials (Global or Personal) for Resource %s not found".formatted(resourceId));
    }

    public List<ResourceCredentials> getAllResourceCredentials(CredentialsLocator credentialsLocator) {
        return resourceCredentialsService.getAllResourceCredentials(credentialsLocator);
    }

    public boolean deleteResourceCredentials(CredentialsLocator credentialsLocator,
                                             ResourceSignOutRequest resourceSignOutRequest,
                                             String userSub) {
        List<ResourceCredentials> resourceCredentialsList = resourceCredentialsService.getAllResourceCredentials(credentialsLocator);

        if (resourceCredentialsList == null || resourceCredentialsList.isEmpty()) {
            return false;
        }

        boolean removed = false;

        if (resourceSignOutRequest.getCredentialsLevel().equals(CredentialsLevel.GLOBAL)) {
            removed = resourceCredentialsList.removeIf(
                    resourceCredentials -> resourceCredentials.getCredentialsLevel().equals(resourceSignOutRequest.getCredentialsLevel()));
        } else if (resourceSignOutRequest.getCredentialsLevel().equals(CredentialsLevel.USER)) {
            removed = resourceCredentialsList.removeIf(
                    resourceCredentials -> resourceCredentials.getCredentialsLevel().equals(resourceSignOutRequest.getCredentialsLevel())
                            && resourceCredentials.getUserSub().equals(userSub));
        }

        resourceCredentialsService.updateAllResourceCredentials(credentialsLocator, resourceCredentialsList);

        log.info("Resource signOut done. {}", credentialsLocator.getResourceId());
        return removed;
    }

    private void updateExpiredResourceCredentials(ResourceCredentials resourceCredentials,
                                                  String resourceId, ResourceAuthSettings authSettings) {
        log.debug("Start updating expired token for Resource: {}", resourceId);
        TokenResponse newAccessTokenResponse = tokenService.getToken(resourceId,
                authSettings, resourceCredentials.getRefreshToken());

        resourceCredentials.setExpiresIn(newAccessTokenResponse.getExpiresIn());
        resourceCredentials.setUpdatedAt(System.currentTimeMillis());
        resourceCredentials.setAccessToken(newAccessTokenResponse.getAccessToken());
        resourceCredentials.setRefreshToken(newAccessTokenResponse.getRefreshToken());
        log.debug("Finished updating expired token for Resource: {}", resourceId);
    }

    public AuthorizationHeader createAuthorizationHeader(CredentialsLocator credentialsLocator,
                                                         ResourceAuthSettings resourceAuthSettings,
                                                         String userSub) {
        ResourceCredentials resourceCredentials = getResourceCredentials(credentialsLocator, resourceAuthSettings, userSub);
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
