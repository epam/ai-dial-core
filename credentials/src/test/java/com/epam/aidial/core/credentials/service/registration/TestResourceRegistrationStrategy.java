package com.epam.aidial.core.credentials.service.registration;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.registration.AuthorizationServerMetadata;
import com.epam.aidial.core.credentials.data.registration.AuthorizationServerProtectedResourceMetadata;
import com.epam.aidial.core.credentials.data.registration.ClientRegistration;

import java.util.List;
import java.util.Optional;

public class TestResourceRegistrationStrategy implements ResourceRegistrationStrategy {

    @Override
    public ClientRegistration register(String resourceId, String resourceEndpoint, ResourceAuthSettings resourceAuthSettings) {
        return null;
    }

    @Override
    public Optional<String> getCodeChallengeMethod(AuthorizationServerMetadata metadata) {
        return ResourceRegistrationStrategy.super.getCodeChallengeMethod(metadata);
    }

    @Override
    public List<String> collectSupportedScopes(AuthorizationServerProtectedResourceMetadata protectedResourceMetadata, AuthorizationServerMetadata metadata) {
        return ResourceRegistrationStrategy.super.collectSupportedScopes(protectedResourceMetadata, metadata);
    }
}
