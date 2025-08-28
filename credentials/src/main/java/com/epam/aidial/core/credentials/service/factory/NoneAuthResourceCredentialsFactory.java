package com.epam.aidial.core.credentials.service.factory;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.ResourceSignInRequest;
import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;

public class NoneAuthResourceCredentialsFactory implements ResourceCredentialsFactory {

    @Override
    public ResourceCredentials createCredentials(String resourceId,
                                                 ResourceAuthSettings authSettings,
                                                 ResourceSignInRequest signInRequest) {
        if (signInRequest.getApiKey() != null || signInRequest.getCode() != null) {
            throw new IllegalArgumentException("Neither Api key nor Code is not required when auth type is None");
        }

        long currentTime = System.currentTimeMillis();
        return ResourceCredentials.builder()
            .resourceId(resourceId)
            .credentialsLevel(signInRequest.getCredentialsLevel())
            .authenticationType(signInRequest.getAuthenticationType())
            .createdAt(currentTime)
            .updatedAt(currentTime)
            .build();
    }
}
