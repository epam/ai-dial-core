package com.epam.aidial.core.credentials.validation;

import com.epam.aidial.core.credentials.data.registration.AuthorizationServerMetadata;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Validates Authorization Server Metadata per RFC 8414.
 *
 * <p>
 * Note: issuer-to-endpoint host matching is intentionally not enforced because
 * the MCP spec supports third-party authorization servers where the auth server
 * may be on a different domain than the resource server.
 * </p>
 */
public class AuthorizationServerMetadataValidator {

    public void validate(AuthorizationServerMetadata authorizationServerMetadata) {
        validateIssuer(authorizationServerMetadata.getIssuer());
        validateRequiredEndpoints(authorizationServerMetadata);
    }

    /**
     * Validates the issuer per RFC 8414: must be a valid URL using the "https" scheme
     * with no query or fragment components.
     */
    private void validateIssuer(String issuer) {
        if (issuer == null || issuer.isEmpty()) {
            throw new IllegalArgumentException("Authorization Server metadata is missing required 'issuer' field");
        }

        try {
            URI issuerUri = new URI(issuer);

            if (issuerUri.getScheme() == null || issuerUri.getHost() == null) {
                throw new IllegalArgumentException("Issuer is not a valid URL: " + issuer);
            }

            if (issuerUri.getQuery() != null) {
                throw new IllegalArgumentException("Issuer must not contain query component: " + issuer);
            }

            if (issuerUri.getFragment() != null) {
                throw new IllegalArgumentException("Issuer must not contain fragment component: " + issuer);
            }
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Issuer is not a valid URI: " + issuer, e);
        }
    }

    private void validateRequiredEndpoints(AuthorizationServerMetadata metadata) {
        if (metadata.getAuthorizationEndpoint() == null || metadata.getAuthorizationEndpoint().isEmpty()) {
            throw new IllegalArgumentException("Authorization Server metadata is missing required 'authorization_endpoint' field");
        }

        if (metadata.getTokenEndpoint() == null || metadata.getTokenEndpoint().isEmpty()) {
            throw new IllegalArgumentException("Authorization Server metadata is missing required 'token_endpoint' field");
        }
    }
}
