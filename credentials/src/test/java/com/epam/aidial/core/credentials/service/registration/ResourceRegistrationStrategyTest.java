package com.epam.aidial.core.credentials.service.registration;

import com.epam.aidial.core.credentials.data.registration.AuthorizationServerMetadata;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResourceRegistrationStrategyTest {

    private final TestResourceRegistrationStrategy resourceRegistrationStrategy = new TestResourceRegistrationStrategy();

    static Stream<TestCase> provideTestCases() {
        return Stream.of(
                new TestCase(List.of("S256", "plain"), Optional.of("S256"), null),
                new TestCase(List.of("plain"), Optional.of("plain"), null),
                new TestCase(List.of(), Optional.empty(), null),
                new TestCase(null, Optional.empty(), null),
                new TestCase(List.of("INVALID_METHOD"), null, "Unsupported code challenge methods detected: [INVALID_METHOD]"),
                new TestCase(List.of("plain", "INVALID_METHOD"), Optional.of("plain"), null),
                new TestCase(List.of("S256", "INVALID_METHOD"), Optional.of("S256"), null),
                new TestCase(List.of("INVALID_METHOD", "ANOTHER_INVALID"), null, "Unsupported code challenge methods detected: [INVALID_METHOD, ANOTHER_INVALID]")
        );
    }

    @ParameterizedTest
    @MethodSource("provideTestCases")
    void testGetCodeChallengeMethod(TestCase testCase) {
        AuthorizationServerMetadata metadata = mock(AuthorizationServerMetadata.class);

        when(metadata.getCodeChallengeMethodsSupported()).thenReturn(testCase.codeChallengeMethodsSupported);

        if (testCase.exceptionMessage != null) {
            IllegalArgumentException exception = Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> resourceRegistrationStrategy.getCodeChallengeMethod(metadata)
            );
            Assertions.assertEquals(testCase.exceptionMessage, exception.getMessage());
        } else {
            Optional<String> result = resourceRegistrationStrategy.getCodeChallengeMethod(metadata);
            Assertions.assertEquals(testCase.expectedResult, result);
        }
    }

    /**
     * @noinspection OptionalUsedAsFieldOrParameterType*/
    private static class TestCase {
        List<String> codeChallengeMethodsSupported;
        Optional<String> expectedResult;
        String exceptionMessage;

        TestCase(List<String> codeChallengeMethodsSupported, Optional<String> expectedResult, String exceptionMessage) {
            this.codeChallengeMethodsSupported = codeChallengeMethodsSupported;
            this.expectedResult = expectedResult;
            this.exceptionMessage = exceptionMessage;
        }
    }
}
