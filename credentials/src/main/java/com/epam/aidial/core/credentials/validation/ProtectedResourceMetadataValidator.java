package com.epam.aidial.core.credentials.validation;

import com.epam.aidial.core.credentials.data.registration.AuthorizationServerProtectedResourceMetadata;
import com.epam.aidial.core.credentials.util.ResourceEndpointUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ProtectedResourceMetadataValidator {

    public void validate(AuthorizationServerProtectedResourceMetadata protectedResourceMetadata,
                         String resourceEndpoint) {
        String expectedBaseResourceEndpoint = ResourceEndpointUtil.buildBaseResourceEndpoint(resourceEndpoint);
        String protectedResourceMetadataResourceUrl = protectedResourceMetadata.getResource();
        String actualBaseResourceEndpoint = ResourceEndpointUtil.buildBaseResourceEndpoint(protectedResourceMetadataResourceUrl);
        if (!expectedBaseResourceEndpoint.equals(actualBaseResourceEndpoint)) {
            log.debug("Resource endpoint: {}, Resource from Protected Resource Metadata: {}",
                    resourceEndpoint, protectedResourceMetadataResourceUrl);
            throw new IllegalArgumentException("Protected resource metadata is not the same as Resource endpoint");
        }
    }
}
