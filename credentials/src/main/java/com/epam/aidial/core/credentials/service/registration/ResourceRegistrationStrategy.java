package com.epam.aidial.core.credentials.service.registration;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.registration.AuthorizationServerMetadata;
import com.epam.aidial.core.credentials.data.registration.ClientRegistration;
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod;

import java.util.List;

public interface ResourceRegistrationStrategy {
    ClientRegistration register(String resourceId, String resourceEndpoint, ResourceAuthSettings resourceAuthSettings);

    default String getCodeChallengeMethod(AuthorizationServerMetadata metadata) {
        List<String> supportedMethods = metadata.getCodeChallengeMethodsSupported();
        return supportedMethods == null
                || supportedMethods.isEmpty()
                || supportedMethods.contains(CodeChallengeMethod.S256.getValue())
                ? CodeChallengeMethod.S256.getValue()
                : supportedMethods.getFirst();
    }
}
