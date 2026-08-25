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

class ApiKeyAuthSettingsValidatorTest {

    private final ApiKeyAuthSettingsValidator validator = new ApiKeyAuthSettingsValidator();

    @Test
    void testValidateCreate_ValidCases() {
        assertDoesNotThrow(() -> validator.validate(
                ResourceAuthSettings.builder()
                        .authenticationType(AuthenticationType.API_KEY)
                        .apiKeyHeader("Api-Key")
                        .build(),
                ResourceAuthSettingsChangeMode.NO_CLIENT_CHANGES));
    }

    @Test
    void testValidateCreate_BlankForbiddenFieldsAreTreatedAsNotProvided() {
        assertDoesNotThrow(() -> validator.validate(
                ResourceAuthSettings.builder()
                        .authenticationType(AuthenticationType.API_KEY)
                        .apiKeyHeader("Api-Key")
                        .clientId("")
                        .clientSecret("  ")
                        .redirectUri("")
                        .scopesSupported(List.of())
                        .build(),
                ResourceAuthSettingsChangeMode.NO_CLIENT_CHANGES));
    }

    static Stream<Arguments> provideInvalidResourceAuthSettingsForUpdate() {
        return Stream.of(
                // Invalid API_KEY cases
                Arguments.of(ResourceAuthSettings.builder()
                                .authenticationType(AuthenticationType.API_KEY)
                                .apiKeyHeader(null)
                                .build(),
                        "Field 'API_KEY_HEADER' is required for API_KEY authentication."),

                Arguments.of(ResourceAuthSettings.builder()
                                .authenticationType(AuthenticationType.API_KEY)
                                .apiKeyHeader("")
                                .build(),
                        "Field 'API_KEY_HEADER' is required for API_KEY authentication."),

                Arguments.of(ResourceAuthSettings.builder()
                                .authenticationType(AuthenticationType.API_KEY)
                                .apiKeyHeader("   ")
                                .build(),
                        "Field 'API_KEY_HEADER' is required for API_KEY authentication."),

                Arguments.of(ResourceAuthSettings.builder()
                                .authenticationType(AuthenticationType.API_KEY)
                                .redirectUri("https://example.com/oauth")
                                .clientId("client123")
                                .clientSecret("secret123")
                                .build(),
                        "Field 'API_KEY_HEADER' is required for API_KEY authentication."),

                Arguments.of(ResourceAuthSettings.builder()
                                .authenticationType(AuthenticationType.API_KEY)
                                .redirectUri("https://example.com/oauth")
                                .apiKeyHeader("apiKeyHeader")
                                .build(),
                        "Field 'REDIRECT_URI' is forbidden for API_KEY authentication."),

                Arguments.of(ResourceAuthSettings.builder()
                                .authenticationType(AuthenticationType.API_KEY)
                                .clientId("client123")
                                .apiKeyHeader("apiKeyHeader")
                                .build(),
                        "Field 'CLIENT_ID' is forbidden for API_KEY authentication."),

                Arguments.of(ResourceAuthSettings.builder()
                                .authenticationType(AuthenticationType.API_KEY)
                                .clientSecret("secret123")
                                .apiKeyHeader("apiKeyHeader")
                                .build(),
                        "Field 'CLIENT_SECRET' is forbidden for API_KEY authentication."),

                Arguments.of(ResourceAuthSettings.builder()
                                .authenticationType(AuthenticationType.API_KEY)
                                .authorizationEndpoint("authorizationEndpoint")
                                .apiKeyHeader("apiKeyHeader")
                                .build(),
                        "Field 'AUTHORIZATION_ENDPOINT' is forbidden for API_KEY authentication."),

                Arguments.of(ResourceAuthSettings.builder()
                                .authenticationType(AuthenticationType.API_KEY)
                                .tokenEndpoint("tokenEndpoint")
                                .apiKeyHeader("apiKeyHeader")
                                .build(),
                        "Field 'TOKEN_ENDPOINT' is forbidden for API_KEY authentication."),

                Arguments.of(ResourceAuthSettings.builder()
                                .authenticationType(AuthenticationType.API_KEY)
                                .codeChallenge("codeChallenge")
                                .apiKeyHeader("apiKeyHeader")
                                .build(),
                        "Field 'CODE_CHALLENGE' is forbidden for API_KEY authentication."),

                Arguments.of(ResourceAuthSettings.builder()
                                .authenticationType(AuthenticationType.API_KEY)
                                .codeVerifier("codeVerifier")
                                .apiKeyHeader("apiKeyHeader")
                                .build(),
                        "Field 'CODE_VERIFIER' is forbidden for API_KEY authentication."),

                Arguments.of(ResourceAuthSettings.builder()
                                .authenticationType(AuthenticationType.API_KEY)
                                .codeChallengeMethod("plain")
                                .apiKeyHeader("apiKeyHeader")
                                .build(),
                        "Field 'CODE_CHALLENGE_METHOD' is forbidden for API_KEY authentication."),

                Arguments.of(ResourceAuthSettings.builder()
                                .authenticationType(AuthenticationType.API_KEY)
                                .scopesSupported(List.of("read", "write"))
                                .apiKeyHeader("apiKeyHeader")
                                .build(),
                        "Field 'SCOPES_SUPPORTED' is forbidden for API_KEY authentication.")
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