package com.epam.aidial.core.credentials.validation;

import com.epam.aidial.core.credentials.data.registration.AuthorizationServerMetadata;
import com.epam.aidial.core.credentials.util.ResourceEndpointUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class AuthorizationServerMetadataValidator {

    public void validate(AuthorizationServerMetadata authorizationServerMetadata,
                         String resourceEndpoint) {
        String expectedBaseAuthorizationServerEndpoint = ResourceEndpointUtil.buildBaseResourceEndpoint(resourceEndpoint);
        String issuer = authorizationServerMetadata.getIssuer();
        String actualBaseResourceEndpoint = ResourceEndpointUtil.buildBaseResourceEndpoint(issuer);
        if (!actualBaseResourceEndpoint.equals(expectedBaseAuthorizationServerEndpoint)) {
            log.debug("Resource endpoint: {}, Issuer: {}", resourceEndpoint, issuer);
            throw new IllegalArgumentException("Issuer in Authorization Server metadata is not the same as Resource endpoint");
        }
    }
}
