package com.epam.aidial.core.credentials.service.registration;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.registration.ClientRegistration;
import com.epam.aidial.core.credentials.service.ResourceAuthorizationClient;
import com.epam.aidial.core.credentials.service.metadata.AuthorizationServerMetadataService;
import com.epam.aidial.core.credentials.service.metadata.ProtectedResourceMetadataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class ResourceRegistrationService {

    private final AuthorizationServerMetadataService authorizationServerMetadataService;
    private final ResourceAuthorizationClient resourceAuthorizationClient;
    private final ProtectedResourceMetadataService protectedResourceMetadataService;

    public ClientRegistration register(String resourceId,
                                       String resourceEndpoint,
                                       ResourceAuthSettings resourceAuthSettings,
                                       boolean oauthDynamicClientRegistrationRequired) {
        ResourceRegistrationStrategy strategy = oauthDynamicClientRegistrationRequired
                ? new DynamicResourceRegistrationStrategy(
                        authorizationServerMetadataService, resourceAuthorizationClient, protectedResourceMetadataService)
                : new StaticResourceRegistrationStrategy(
                        authorizationServerMetadataService, protectedResourceMetadataService);

        return strategy.register(resourceId, resourceEndpoint, resourceAuthSettings);
    }


}
