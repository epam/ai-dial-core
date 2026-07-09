package com.epam.aidial.core.credentials.service.registration;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.registration.AuthorizationServerMetadata;
import com.epam.aidial.core.credentials.data.registration.AuthorizationServerProtectedResourceMetadata;
import com.epam.aidial.core.credentials.data.registration.ClientRegistration;
import com.epam.aidial.core.credentials.service.ResourceAuthorizationClient;
import com.epam.aidial.core.credentials.service.metadata.AuthorizationServerMetadataService;
import com.epam.aidial.core.credentials.service.metadata.ProtectedResourceMetadataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class ResourceRegistrationService {

    private final AuthorizationServerMetadataService authorizationServerMetadataService;
    private final ResourceAuthorizationClient resourceAuthorizationClient;
    private final ProtectedResourceMetadataService protectedResourceMetadataService;
    private final List<String> allowedRedirectUris;

    /**
     * Discovers AS metadata for a resource endpoint without performing client registration.
     * Used by the repair path to re-validate endpoints cheaply before deciding whether to re-register.
     */
    public AuthorizationServerMetadata discoverMetadata(String resourceId, String resourceEndpoint) {
        AuthorizationServerProtectedResourceMetadata prm =
                protectedResourceMetadataService.getProtectedResourceMetadata(resourceId, resourceEndpoint);
        return authorizationServerMetadataService.getAuthorizationServerMetadata(resourceId, resourceEndpoint, prm, false);
    }

    public ClientRegistration register(String resourceId,
                                       String resourceEndpoint,
                                       ResourceAuthSettings resourceAuthSettings,
                                       boolean oauthDynamicClientRegistrationRequired) {
        ResourceRegistrationStrategy strategy = oauthDynamicClientRegistrationRequired
                ? new DynamicResourceRegistrationStrategy(
                        authorizationServerMetadataService, resourceAuthorizationClient, protectedResourceMetadataService, allowedRedirectUris)
                : new StaticResourceRegistrationStrategy(
                        authorizationServerMetadataService, protectedResourceMetadataService);

        return strategy.register(resourceId, resourceEndpoint, resourceAuthSettings);
    }


}
