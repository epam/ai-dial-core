package com.epam.aidial.core.credentials.validation;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsChangeMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NoneAuthSettingsValidatorTest {

    private final NoneAuthSettingsValidator validator = new NoneAuthSettingsValidator();

    @Test
    void testValidateCreate_ValidCases() {
        assertDoesNotThrow(() -> validator.validate(
                ResourceAuthSettings.builder()
                        .authenticationType(AuthenticationType.NONE)
                        .build(),
                ResourceAuthSettingsChangeMode.NO_CLIENT_CHANGES));
    }

    static Stream<Arguments> provideInvalidResourceAuthSettingsForUpdate() {
        return Stream.of(
                // Invalid NONE auth cases
                Arguments.of(ResourceAuthSettings.builder()
                                .authenticationType(AuthenticationType.NONE)
                                .apiKeyHeader("apiKeyHeader")
                                .build(),
                        "Field 'API_KEY_HEADER' is forbidden for NONE authentication."),

                Arguments.of(ResourceAuthSettings.builder()
                                .authenticationType(AuthenticationType.NONE)
                                .redirectUri("https://example.com/oauth")
                                .build(),
                        "Field 'REDIRECT_URI' is forbidden for NONE authentication."),

                Arguments.of(ResourceAuthSettings.builder()
                                .authenticationType(AuthenticationType.NONE)
                                .clientId("client123")
                                .build(),
                        "Field 'CLIENT_ID' is forbidden for NONE authentication."),

                Arguments.of(ResourceAuthSettings.builder()
                                .authenticationType(AuthenticationType.NONE)
                                .clientSecret("secret123")
                                .build(),
                        "Field 'CLIENT_SECRET' is forbidden for NONE authentication."),

                Arguments.of(ResourceAuthSettings.builder()
                                .authenticationType(AuthenticationType.NONE)
                                .authorizationEndpoint("authorizationEndpoint")
                                .build(),
                        "Field 'AUTHORIZATION_ENDPOINT' is forbidden for NONE authentication."),

                Arguments.of(ResourceAuthSettings.builder()
                                .authenticationType(AuthenticationType.NONE)
                                .tokenEndpoint("tokenEndpoint")
                                .build(),
                        "Field 'TOKEN_ENDPOINT' is forbidden for NONE authentication."),

                Arguments.of(ResourceAuthSettings.builder()
                                .authenticationType(AuthenticationType.NONE)
                                .codeChallenge("codeChallenge")
                                .build(),
                        "Field 'CODE_CHALLENGE' is forbidden for NONE authentication."),

                Arguments.of(ResourceAuthSettings.builder()
                                .authenticationType(AuthenticationType.NONE)
                                .codeVerifier("codeVerifier")
                                .build(),
                        "Field 'CODE_VERIFIER' is forbidden for NONE authentication."),

                Arguments.of(ResourceAuthSettings.builder()
                                .authenticationType(AuthenticationType.NONE)
                                .codeChallengeMethod("plain")
                                .build(),
                        "Field 'CODE_CHALLENGE_METHOD' is forbidden for NONE authentication."),

                Arguments.of(ResourceAuthSettings.builder()
                                .authenticationType(AuthenticationType.NONE)
                                .scopesSupported(List.of("read", "write"))
                                .build(),
                        "Field 'SCOPES_SUPPORTED' is forbidden for NONE authentication.")
        );
    }

    @ParameterizedTest
    @MethodSource("provideInvalidResourceAuthSettingsForUpdate")
    void testValidateUpdate_InvalidCustomValidation(ResourceAuthSettings resourceAuthSettings, String expectedMessage) {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(resourceAuthSettings, ResourceAuthSettingsChangeMode.NO_CLIENT_CHANGES)
        );
        assertEquals(expectedMessage, exception.getMessage());
    }
}