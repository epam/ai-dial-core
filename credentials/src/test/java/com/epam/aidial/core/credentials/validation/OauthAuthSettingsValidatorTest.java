package com.epam.aidial.core.credentials.validation;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsChangeMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OauthAuthSettingsValidatorTest {

    private final OauthAuthSettingsValidator validator = new OauthAuthSettingsValidator();

    static Stream<Arguments> provideValidResourceAuthSettingsForCreate() {
        return Stream.of(
                // Case 1: OAUTH authentication with static registration
                Arguments.of(ResourceAuthSettings.builder()
                                .authenticationType(AuthenticationType.OAUTH)
                                .clientId("client123")
                                .clientSecret("secret123")
                                .authorizationEndpoint("authorizationEndpoint")
                                .tokenEndpoint("tokenEndpoint")
                                .redirectUri("https://example.com/oauth")
                                .build(),
                        ResourceAuthSettingsChangeMode.CREATE_STATIC_CLIENT),

                // Case 2: OAUTH authentication with dynamic registration
                Arguments.of(ResourceAuthSettings.builder()
                                .authenticationType(AuthenticationType.OAUTH)
                                .redirectUri("https://example.com/oauth")
                                .build(),
                        ResourceAuthSettingsChangeMode.CREATE_DYNAMIC_CLIENT)
        );
    }

    @ParameterizedTest
    @MethodSource("provideValidResourceAuthSettingsForCreate")
    void testValidateCreate_ValidCases(ResourceAuthSettings resourceAuthSettings,
                                       ResourceAuthSettingsChangeMode resourceAuthSettingsChangeMode) {
        assertDoesNotThrow(() -> validator.validate(
                resourceAuthSettings, resourceAuthSettingsChangeMode));
    }

    static Stream<Arguments> provideValidResourceAuthSettingsForUpdate() {
        return Stream.of(
                // Case 1: OAUTH authentication update with clientSecret
                Arguments.of(ResourceAuthSettings.builder()
                        .authenticationType(AuthenticationType.OAUTH)
                        .clientId("client123")
                        .clientSecret("secret123")
                        .authorizationEndpoint("https://auth_server.com/authorize")
                        .tokenEndpoint("https://auth_server.com/token")
                        .redirectUri("https://example.com/oauth")
                        .build(),
                        ResourceAuthSettingsChangeMode.NO_CLIENT_CHANGES),

                // Case 2: OAUTH authentication update with no clientSecret
                Arguments.of(ResourceAuthSettings.builder()
                        .authenticationType(AuthenticationType.OAUTH)
                        .clientId("client123")
                        .redirectUri("https://example.com/oauth")
                        .authorizationEndpoint("authorizationEndpoint")
                        .tokenEndpoint("tokenEndpoint")
                        .codeChallengeMethod("S256")
                        .scopesSupported(List.of("scope"))
                        .build(),
                        ResourceAuthSettingsChangeMode.NO_CLIENT_CHANGES)
        );
    }

    @ParameterizedTest
    @MethodSource("provideValidResourceAuthSettingsForUpdate")
    void testValidateUpdate_ValidCases(ResourceAuthSettings resourceAuthSettings,
                                       ResourceAuthSettingsChangeMode resourceAuthSettingsChangeMode) {
        assertDoesNotThrow(() -> validator.validate(
                resourceAuthSettings, resourceAuthSettingsChangeMode));
    }

    static Stream<Arguments> provideInvalidResourceAuthSettingsForCreate() {
        return Stream.of(
                // Invalid OAuth cases
                Arguments.of(ResourceAuthSettings.builder()
                                .authenticationType(AuthenticationType.OAUTH)
                                .clientId("client123")
                                .authorizationEndpoint("authorizationEndpoint")
                                .tokenEndpoint("tokenEndpoint")
                                .redirectUri("https://example.com/oauth")
                                .build(),
                        ResourceAuthSettingsChangeMode.CREATE_STATIC_CLIENT,
                        "Field 'CLIENT_SECRET' is required for OAUTH authentication."),

                Arguments.of(ResourceAuthSettings.builder()
                                .authenticationType(AuthenticationType.OAUTH)
                                .clientSecret("secret123")
                                .authorizationEndpoint("authorizationEndpoint")
                                .tokenEndpoint("tokenEndpoint")
                                .redirectUri("https://example.com/oauth")
                                .build(),
                        ResourceAuthSettingsChangeMode.CREATE_STATIC_CLIENT,
                        "Field 'CLIENT_ID' is required for OAUTH authentication."),

                Arguments.of(ResourceAuthSettings.builder()
                                .authenticationType(AuthenticationType.OAUTH)
                                .clientId("client123")
                                .clientSecret("secret123")
                                .authorizationEndpoint("authorizationEndpoint")
                                .tokenEndpoint("tokenEndpoint")
                                .build(),
                        ResourceAuthSettingsChangeMode.CREATE_STATIC_CLIENT,
                        "Field 'REDIRECT_URI' is required for OAUTH authentication."),

                Arguments.of(ResourceAuthSettings.builder()
                                .authenticationType(AuthenticationType.OAUTH)
                                .clientId("client123")
                                .clientSecret("secret123")
                                .authorizationEndpoint("authorizationEndpoint")
                                .tokenEndpoint("tokenEndpoint")
                                .redirectUri("https://example.com/oauth")
                                .codeVerifier("verifier123")
                                .build(),
                        ResourceAuthSettingsChangeMode.CREATE_STATIC_CLIENT,
                        "Field 'CODE_VERIFIER' is forbidden for OAUTH authentication.")
        );
    }

    @ParameterizedTest
    @MethodSource("provideInvalidResourceAuthSettingsForCreate")
    void testValidateCreate_InvalidCustomValidation(ResourceAuthSettings resourceAuthSettings,
                                                    ResourceAuthSettingsChangeMode resourceAuthSettingsChangeMode,
                                                    String expectedMessage) {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(resourceAuthSettings, resourceAuthSettingsChangeMode)
        );
        assertEquals(expectedMessage, exception.getMessage());
    }
}