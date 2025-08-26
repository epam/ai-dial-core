package com.epam.aidial.core.server.service.credentials;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.config.ToolSetAuthSettings;
import com.epam.aidial.core.config.ToolSetSignInRequest;
import com.epam.aidial.core.config.ToolSetSignOutRequest;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.toolset.credentials.TokenResponse;
import com.epam.aidial.core.server.data.toolset.credentials.ToolSetCredentials;
import com.epam.aidial.core.server.service.ResourceNotFoundException;
import com.epam.aidial.core.server.service.credentials.factory.ToolSetCredentialsFactory;
import com.epam.aidial.core.server.service.credentials.factory.ToolSetCredentialsFactoryProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class ToolSetCredentialsManager {

    private final ToolSetCredentialsService toolSetCredentialsService;
    private final ToolSetTokenService toolSetTokenService;

    public ToolSetCredentials createToolsetCredentials(ToolSetAuthSettings toolSetAuthSettings,
                                                       ToolSetSignInRequest toolSetSignInRequest,
                                                       ProxyContext contex) {
        ToolSetCredentialsFactoryProvider toolSetCredentialsFactoryProvider = new ToolSetCredentialsFactoryProvider(toolSetTokenService);
        ToolSetCredentialsFactory factory = toolSetCredentialsFactoryProvider.getFactory(toolSetSignInRequest.getAuthenticationType());

        ToolSetCredentials toolSetCredentials = factory.createCredentials(toolSetSignInRequest.getUrl(), toolSetAuthSettings, toolSetSignInRequest, contex);

        if (toolSetSignInRequest.getCredentialsLevel().equals(CredentialsLevel.USER)) {
            toolSetCredentials.setUserSub(contex.getUserSub());
        }

        toolSetCredentialsService.addToolSetCredentials(toolSetCredentials);
        log.info("ToolSet signIn done. {}", toolSetSignInRequest.getUrl());
        return toolSetCredentials;
    }

    public ToolSetCredentials getToolSetCredentials(String toolsetId, ToolSetAuthSettings authSettings,
                                                    ProxyContext context) {
        if (authSettings.getAuthenticationType() == AuthenticationType.NONE) {
            return null;
        }
        List<ToolSetCredentials> toolSetCredentialsList = toolSetCredentialsService.getAllToolSetCredentials(toolsetId);
        String userSub = context.getUserSub();

        ToolSetCredentials globalCredentials = null;

        for (ToolSetCredentials credentials : toolSetCredentialsList) {
            if (credentials.getCredentialsLevel() == CredentialsLevel.USER
                    && userSub != null
                    && userSub.equals(credentials.getUserSub())) {
                if (credentials.isTokenExpired()) {
                    updateExpiredToolSetCredentials(credentials, toolsetId, authSettings);
                    toolSetCredentialsService.updateToolSetCredentials(toolsetId, toolSetCredentialsList);
                }
                return credentials;
            }

            if (credentials.getCredentialsLevel() == CredentialsLevel.GLOBAL) {
                if (credentials.isTokenExpired()) {
                    updateExpiredToolSetCredentials(credentials, toolsetId, authSettings);
                }
                globalCredentials = credentials;
            }
        }

        if (globalCredentials != null) {
            toolSetCredentialsService.updateToolSetCredentials(toolsetId, toolSetCredentialsList);
            return globalCredentials;
        }

        // TODO: implement logic for APP level creds

        throw new ResourceNotFoundException(String.format("Credentials (Global or Personal) for ToolSet %s not found", toolsetId));
    }

    public List<ToolSetCredentials> getAllToolSetCredentials(String toolSetName) {
        return toolSetCredentialsService.getAllToolSetCredentials(toolSetName);
    }

    public boolean deleteToolSetCredentials(ToolSetSignOutRequest toolSetSignOutRequest,
                                            ProxyContext contex) {
        String toolSetName = toolSetSignOutRequest.getUrl();
        List<ToolSetCredentials> toolSetCredentialsList = toolSetCredentialsService.getAllToolSetCredentials(toolSetName);

        if (toolSetCredentialsList == null || toolSetCredentialsList.isEmpty()) {
            return false;
        }

        boolean removed = false;

        if (toolSetSignOutRequest.getCredentialsLevel().equals(CredentialsLevel.GLOBAL)) {
            removed = toolSetCredentialsList.removeIf(
                toolSetCredentials -> toolSetCredentials.getCredentialsLevel().equals(toolSetSignOutRequest.getCredentialsLevel()));
        } else if (toolSetSignOutRequest.getCredentialsLevel().equals(CredentialsLevel.USER)) {
            removed = toolSetCredentialsList.removeIf(
                toolSetCredentials -> toolSetCredentials.getCredentialsLevel().equals(toolSetSignOutRequest.getCredentialsLevel())
                    && toolSetCredentials.getUserSub().equals(contex.getUserSub()));
        }

        toolSetCredentialsService.updateToolSetCredentials(toolSetName, toolSetCredentialsList);

        log.info("ToolSet signOut done. {}", toolSetName);
        return removed;
    }

    private void updateExpiredToolSetCredentials(ToolSetCredentials toolSetCredentials,
                                                 String toolsetId, ToolSetAuthSettings authSettings) {
        log.debug("Start updating expired token for ToolSet: {}", toolsetId);
        TokenResponse newAccessTokenResponse = toolSetTokenService.getToken(toolsetId,
                authSettings, toolSetCredentials.getRefreshToken());

        toolSetCredentials.setExpiresIn(newAccessTokenResponse.getExpiresIn());
        toolSetCredentials.setUpdatedAt(System.currentTimeMillis());
        toolSetCredentials.setAccessToken(newAccessTokenResponse.getAccessToken());
        toolSetCredentials.setRefreshToken(newAccessTokenResponse.getRefreshToken());
        log.debug("Finished updating expired token for ToolSet: {}", toolsetId);
    }
}
