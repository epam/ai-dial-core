package com.epam.aidial.core.credentials.service.registration;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.registration.AuthorizationServerMetadata;
import com.epam.aidial.core.credentials.data.registration.AuthorizationServerProtectedResourceMetadata;
import com.epam.aidial.core.credentials.data.registration.ClientRegistration;
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface ResourceRegistrationStrategy {
    ClientRegistration register(String resourceId, String resourceEndpoint, ResourceAuthSettings resourceAuthSettings);

    default Optional<String> getCodeChallengeMethod(AuthorizationServerMetadata metadata) {
        return Optional.ofNullable(metadata.getCodeChallengeMethodsSupported())
                .filter(codeChallengeMethodsSupported -> !codeChallengeMethodsSupported.isEmpty())
                .map(codeChallengeMethodsSupported -> codeChallengeMethodsSupported.contains(CodeChallengeMethod.S256.getValue())
                        ? CodeChallengeMethod.S256.getValue()
                        : CodeChallengeMethod.PLAIN.getValue());
    }

    default List<String> collectSupportedScopes(AuthorizationServerProtectedResourceMetadata protectedResourceMetadata,
                                                AuthorizationServerMetadata metadata) {
        Set<String> supportedScopes = new HashSet<>();

        Optional.ofNullable(protectedResourceMetadata)
                .map(AuthorizationServerProtectedResourceMetadata::getScopesSupported)
                .filter(scopes -> !scopes.isEmpty())
                .ifPresent(supportedScopes::addAll);

        // Using AuthorizationServerMetadata as fallback
        if (supportedScopes.isEmpty()) {
            Optional.ofNullable(metadata)
                    .map(AuthorizationServerMetadata::getScopesSupported)
                    .ifPresent(supportedScopes::addAll);
        }

        return new ArrayList<>(supportedScopes);
    }
}
