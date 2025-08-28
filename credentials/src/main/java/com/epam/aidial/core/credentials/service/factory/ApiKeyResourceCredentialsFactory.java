package com.epam.aidial.core.credentials.service.factory;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.ResourceSignInRequest;
import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;

public class ApiKeyResourceCredentialsFactory implements ResourceCredentialsFactory {

    @Override
    public ResourceCredentials createCredentials(String resourceId,
                                                 ResourceAuthSettings authSettings,
                                                 ResourceSignInRequest signInRequest) {
        if (signInRequest.getCode() != null) {
            throw new IllegalArgumentException("Code is not required when auth type is API_KEY");
        }

        long currentTime = System.currentTimeMillis();
        return ResourceCredentials.builder()
            .resourceId(resourceId)
            .credentialsLevel(signInRequest.getCredentialsLevel())
            .authenticationType(signInRequest.getAuthenticationType())
            .apiKeyHeader(authSettings.getApiKeyHeader())
            .apiKey(signInRequest.getApiKey())
            .createdAt(currentTime)
            .updatedAt(currentTime)
            .build();
    }
}
