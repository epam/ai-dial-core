package com.epam.aidial.core.server.service.credentials.factory;

import com.epam.aidial.core.config.ToolSetAuthSettings;
import com.epam.aidial.core.config.ToolSetSignInRequest;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.toolset.credentials.ToolSetCredentials;

public class ApiKeyToolSetCredentialsFactory implements ToolSetCredentialsFactory {

    @Override
    public ToolSetCredentials createCredentials(String toolSetName,
                                                ToolSetAuthSettings authSettings,
                                                ToolSetSignInRequest signInRequest,
                                                ProxyContext context) {
        if (signInRequest.getCode() != null) {
            throw new IllegalArgumentException("Code is not required when auth type is API_KEY");
        }

        long currentTime = System.currentTimeMillis();
        return ToolSetCredentials.builder()
            .toolSetName(toolSetName)
            .credentialsLevel(signInRequest.getCredentialsLevel())
            .authenticationType(signInRequest.getAuthenticationType())
            .apiKeyHeader(authSettings.getApiKeyHeader())
            .apiKey(signInRequest.getApiKey())
            .createdAt(currentTime)
            .updatedAt(currentTime)
            .build();
    }
}
