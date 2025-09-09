package com.epam.aidial.core.credentials.service.registration;

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

    public ClientRegistration registerResource(String resourceId,
                                               String resourceEndpoint,
                                               ResourceAuthSettings resourceAuthSettings,
                                               boolean isDynamic) {
        ResourceRegistrationStrategy strategy = isDynamic
                ? new DynamicResourceRegistrationStrategy(authorizationServerMetadataService, resourceAuthorizationClient)
                : new StaticResourceRegistrationStrategy(authorizationServerMetadataService);

        return strategy.register(resourceId, resourceEndpoint, resourceAuthSettings);
    }
}
