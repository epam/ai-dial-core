package com.epam.aidial.core.credentials.validation;

import com.epam.aidial.core.credentials.data.registration.AuthorizationServerMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthorizationServerMetadataValidatorTest {

    private final AuthorizationServerMetadataValidator validator = new AuthorizationServerMetadataValidator();

    private static final String RESOURCE_ENDPOINT = "https://example.com/.well-known/oauth-authorization-server";

    @Test
    void testValidMetadata() {
        AuthorizationServerMetadata metadata = AuthorizationServerMetadata.builder()
                .issuer("https://example.com")
                .authorizationEndpoint("https://example.com/authorize")
                .tokenEndpoint("https://example.com/token")
                .build();

        assertDoesNotThrow(() -> validator.validate(metadata, RESOURCE_ENDPOINT));
    }

    @Test
    void testValidMetadataWithThirdPartyIssuer() {
        AuthorizationServerMetadata metadata = AuthorizationServerMetadata.builder()
                .issuer("https://auth.another-domain.com/auth/realms/plusx")
                .authorizationEndpoint("https://proxy.example.com/authorize")
                .tokenEndpoint("https://proxy.example.com/token")
                .build();

        assertDoesNotThrow(() -> validator.validate(metadata, RESOURCE_ENDPOINT));
    }

    @Test
    void testValidMetadataWithPathInIssuer() {
        AuthorizationServerMetadata metadata = AuthorizationServerMetadata.builder()
                .issuer("https://example.com/auth/realms/main")
                .authorizationEndpoint("https://example.com/authorize")
                .tokenEndpoint("https://example.com/token")
                .build();

        assertDoesNotThrow(() -> validator.validate(metadata, RESOURCE_ENDPOINT));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void testMissingIssuer(String issuer) {
        AuthorizationServerMetadata metadata = AuthorizationServerMetadata.builder()
                .issuer(issuer)
                .authorizationEndpoint("https://example.com/authorize")
                .tokenEndpoint("https://example.com/token")
                .build();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(metadata, RESOURCE_ENDPOINT));
        assertEquals("Authorization Server metadata is missing required 'issuer' field", exception.getMessage());
    }

    @Test
    void testIssuerWithQueryComponent() {
        AuthorizationServerMetadata metadata = AuthorizationServerMetadata.builder()
                .issuer("https://example.com?param=value")
                .authorizationEndpoint("https://example.com/authorize")
                .tokenEndpoint("https://example.com/token")
                .build();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(metadata, RESOURCE_ENDPOINT));
        assertEquals("Issuer must not contain query component: https://example.com?param=value", exception.getMessage());
    }

    @Test
    void testIssuerWithFragmentComponent() {
        AuthorizationServerMetadata metadata = AuthorizationServerMetadata.builder()
                .issuer("https://example.com#fragment")
                .authorizationEndpoint("https://example.com/authorize")
                .tokenEndpoint("https://example.com/token")
                .build();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(metadata, RESOURCE_ENDPOINT));
        assertEquals("Issuer must not contain fragment component: https://example.com#fragment", exception.getMessage());
    }

    @Test
    void testIssuerWithInvalidUrl() {
        AuthorizationServerMetadata metadata = AuthorizationServerMetadata.builder()
                .issuer("not-a-url")
                .authorizationEndpoint("https://example.com/authorize")
                .tokenEndpoint("https://example.com/token")
                .build();

        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(metadata, RESOURCE_ENDPOINT));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void testMissingAuthorizationEndpoint(String authorizationEndpoint) {
        AuthorizationServerMetadata metadata = AuthorizationServerMetadata.builder()
                .issuer("https://example.com")
                .authorizationEndpoint(authorizationEndpoint)
                .tokenEndpoint("https://example.com/token")
                .build();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(metadata, RESOURCE_ENDPOINT));
        assertEquals("Authorization Server metadata is missing required 'authorization_endpoint' field", exception.getMessage());
    }

    @ParameterizedTest
    @NullAndEmptySource
    void testMissingTokenEndpoint(String tokenEndpoint) {
        AuthorizationServerMetadata metadata = AuthorizationServerMetadata.builder()
                .issuer("https://example.com")
                .authorizationEndpoint("https://example.com/authorize")
                .tokenEndpoint(tokenEndpoint)
                .build();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(metadata, RESOURCE_ENDPOINT));
        assertEquals("Authorization Server metadata is missing required 'token_endpoint' field", exception.getMessage());
    }
}