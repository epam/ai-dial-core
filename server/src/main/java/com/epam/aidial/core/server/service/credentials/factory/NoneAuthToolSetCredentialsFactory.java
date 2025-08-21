package com.epam.aidial.core.server.service.credentials.factory;

import com.epam.aidial.core.config.ToolSetAuthSettings;
import com.epam.aidial.core.config.ToolSetSignInRequest;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.toolset.credentials.ToolSetCredentials;

public class NoneAuthToolSetCredentialsFactory implements ToolSetCredentialsFactory {

    @Override
    public ToolSetCredentials createCredentials(String toolSetName,
                                                ToolSetAuthSettings authSettings,
                                                ToolSetSignInRequest signInRequest,
                                                ProxyContext context) {
        if (signInRequest.getApiKey() != null || signInRequest.getCode() != null) {
            throw new IllegalArgumentException("Neither Api key nor Code is not required when auth type is None");
        }

        long currentTime = System.currentTimeMillis();
        return ToolSetCredentials.builder()
            .toolSetName(toolSetName)
            .credentialsLevel(signInRequest.getCredentialsLevel())
            .authenticationType(signInRequest.getAuthenticationType())
            .createdAt(currentTime)
            .updatedAt(currentTime)
            .build();
    }
}
