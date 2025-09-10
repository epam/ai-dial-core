package com.epam.aidial.core.credentials.service.registration;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.registration.ClientRegistration;
import com.epam.aidial.core.credentials.service.ResourceAuthorizationClient;
import com.epam.aidial.core.credentials.service.metadata.AuthorizationServerMetadataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class ResourceRegistrationService {

    private final AuthorizationServerMetadataService authorizationServerMetadataService;
    private final ResourceAuthorizationClient resourceAuthorizationClient;

    public ClientRegistration register(String resourceId,
                                       String resourceEndpoint,
                                       ResourceAuthSettings resourceAuthSettings) {
        ResourceRegistrationStrategy strategy = shouldRegisterResourceDynamically(resourceAuthSettings)
                ? new DynamicResourceRegistrationStrategy(authorizationServerMetadataService, resourceAuthorizationClient)
                : new StaticResourceRegistrationStrategy(authorizationServerMetadataService);

        return strategy.register(resourceId, resourceEndpoint, resourceAuthSettings);
    }

    private boolean shouldRegisterResourceDynamically(ResourceAuthSettings resourceAuthSettings) {
        return AuthenticationType.OAUTH.equals(resourceAuthSettings.getAuthenticationType())
                && resourceAuthSettings.getClientId() == null
                && resourceAuthSettings.getClientSecret() == null;
    }
}
