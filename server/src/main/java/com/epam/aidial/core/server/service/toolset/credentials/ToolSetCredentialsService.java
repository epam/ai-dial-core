package com.epam.aidial.core.server.service.toolset.credentials;

import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.config.ToolSetSignInRequest;
import com.epam.aidial.core.config.ToolSetAuthSettings;
import com.epam.aidial.core.config.ToolSetSignOutRequest;
import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.ToolsetCredentialsStatus;
import com.epam.aidial.core.server.data.toolset.credentials.TokenRequest;
import com.epam.aidial.core.server.data.toolset.credentials.TokenResponse;
import com.epam.aidial.core.server.data.toolset.credentials.ToolSetCredentials;
import com.epam.aidial.core.server.service.ResourceNotFoundException;
import com.epam.aidial.core.server.service.ToolSetService;
import com.epam.aidial.core.server.service.toolset.registration.ToolsetAuthorizationServerClient;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class ToolSetCredentialsService {

    private final ToolSetService toolSetService;
    private final ToolsetAuthorizationServerClient toolsetAuthorizationServerClient;

    // TODO: replace with persistent storage
    private final Map<String, List<ToolSetCredentials>> toolSetCredentialsMap = new HashMap<>();

    public ToolSetCredentials createToolsetCredentials(ResourceDescriptor resource,
                                                       ToolSetSignInRequest toolSetSignInRequest) {
        ToolSet toolSet = toolSetService.getToolSet(resource).getValue();
        String toolSetName = toolSet.getName();
        ToolSetAuthSettings toolsetAuthSettings = toolSet.getAuthSettings();

        ToolSetCredentials toolSetCredentials;
        if (toolSetSignInRequest.getAuthenticationType() == AuthenticationType.API_KEY) {
            toolSetCredentials = createApiKeyToolSetCredentials(toolSetName, toolsetAuthSettings.getApiKeyHeader(), toolSetSignInRequest);
        }

        else if (toolSetSignInRequest.getAuthenticationType() == AuthenticationType.OAUTH) {
            toolSetCredentials = createOauthToolSetCredentials(toolSetName, toolsetAuthSettings, toolSetSignInRequest);
        }

        else if (toolSetSignInRequest.getAuthenticationType() == AuthenticationType.NONE) {
            toolSetCredentials = createNoneAuthToolSetCredentials(toolSetName, toolSetSignInRequest);
        }
        else {
            throw new IllegalArgumentException(String.format("Invalid ToolsetAuthenticationType: %s", toolSetSignInRequest.getAuthenticationType()));
        }
        toolSetCredentialsMap.computeIfAbsent(toolSetName, k -> new ArrayList<>()).add(toolSetCredentials);
        log.info("ToolSet signIn done. {}", toolSetName);
        return toolSetCredentials;
    }

    private ToolSetCredentials createApiKeyToolSetCredentials(String toolSetName,
                                                              String apiKeyHeader,
                                                              ToolSetSignInRequest toolSetSignInRequest) {
        if (toolSetSignInRequest.getCode() != null) {
            throw new IllegalArgumentException("Code is not required when auth type is API_KEY");
        }

        return ToolSetCredentials.builder()
            .toolSetName(toolSetName)
            .credentialsLevel(toolSetSignInRequest.getCredentialsLevel())
            .authenticationType(toolSetSignInRequest.getAuthenticationType())
            .apiKeyHeader(apiKeyHeader)
            .apiKey(toolSetSignInRequest.getApiKey())
            .createdAt(System.currentTimeMillis())
            .status(ToolsetCredentialsStatus.SIGNED_IN)
            .build();
    }

    private ToolSetCredentials createOauthToolSetCredentials(String toolSetName,
                                                             ToolSetAuthSettings toolsetAuthSettings,
                                                             ToolSetSignInRequest toolSetSignInRequest) {
        if (toolSetSignInRequest.getApiKey() != null) {
            throw new IllegalArgumentException("Api key is not required when auth type is OAUTH");
        }

        TokenResponse tokenResponse = getToken(toolSetName, toolsetAuthSettings, toolSetSignInRequest);

        return ToolSetCredentials.builder()
            .toolSetName(toolSetName)
            .credentialsLevel(toolSetSignInRequest.getCredentialsLevel())
            .authenticationType(toolSetSignInRequest.getAuthenticationType())
            .accessToken(tokenResponse.getAccessToken())
            .refreshToken(tokenResponse.getRefreshToken())
            .expiresIn(tokenResponse.getExpiresIn())
            .createdAt(System.currentTimeMillis())
            .status(ToolsetCredentialsStatus.SIGNED_IN)
            .build();
    }

    private TokenResponse getToken(String toolSetName,
                                   ToolSetAuthSettings toolsetAuthSettings,
                                   ToolSetSignInRequest toolSetSignInRequest) {
        log.debug("Start Toolset {} token retrieval", toolSetName);
        TokenRequest tokenRequest = TokenRequest.builder()
            .clientId(toolsetAuthSettings.getClientId())
            .clientSecret(toolsetAuthSettings.getClientSecret())
            .code(toolSetSignInRequest.getCode())
            // TODO: do we need to support different?
            .grantType("authorization_code")
            .redirectUri(toolsetAuthSettings.getRedirectUri())
            .build();

        TokenResponse tokenResponse = toolsetAuthorizationServerClient.executePost(
            toolsetAuthSettings.getTokenEndpoint(),
            tokenRequest.buildFormData(),
            "application/x-www-form-urlencoded",
            TokenResponse.class);
        log.debug("Finished Toolset {} token retrieval", toolSetName);
        return tokenResponse;
    }

    private ToolSetCredentials createNoneAuthToolSetCredentials(String toolSetName,
                                                                ToolSetSignInRequest toolSetSignInRequest) {
        if (toolSetSignInRequest.getApiKey() != null || toolSetSignInRequest.getCode() != null) {
            throw new IllegalArgumentException("Neither Api key nor Code is not required when auth type is None");
        }

        return ToolSetCredentials.builder()
            .toolSetName(toolSetName)
            .credentialsLevel(toolSetSignInRequest.getCredentialsLevel())
            .authenticationType(toolSetSignInRequest.getAuthenticationType())
            .createdAt(System.currentTimeMillis())
            .status(ToolsetCredentialsStatus.SIGNED_IN)
            .build();
    }

    public ToolSetCredentials getToolSetCredentials(String toolSetName) {
        if (!toolSetCredentialsMap.containsKey(toolSetName)) {
            throw new ResourceNotFoundException(String.format("Credentials for ToolSet %s not found", toolSetName));
        }
        // TODO: implement logic for choosing creds
        return toolSetCredentialsMap.get(toolSetName).get(0);
    }

    public boolean deleteToolSetCredentials(ToolSetSignOutRequest toolSetSignOutRequest) {
        String toolSetName = toolSetSignOutRequest.getUrl();

        if (!toolSetCredentialsMap.containsKey(toolSetName)) {
            throw new ResourceNotFoundException(String.format("Credentials for ToolSet %s not found", toolSetName));
        }

        List<ToolSetCredentials> toolSetCredentialsList = toolSetCredentialsMap.get(toolSetName);

        if (toolSetCredentialsList == null || toolSetCredentialsList.isEmpty()) {
            return false;
        }

        boolean removed = toolSetCredentialsList.removeIf(
            toolSetCredentials -> toolSetCredentials.getCredentialsLevel().equals(toolSetSignOutRequest.getCredentialsLevel()));

        if (toolSetCredentialsList.isEmpty()) {
            toolSetCredentialsMap.remove(toolSetName);
        }
        log.info("ToolSet signOut done. {}", toolSetName);
        return removed;
    }
}
