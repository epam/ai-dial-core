package com.epam.aidial.core.credentials.util;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResourceEndpointUtilTest {

    static Stream<TestCaseBuildBaseResourceEndpoint> provideTestCasesForBaseResourceEndpoint() {
        return Stream.of(
                new TestCaseBuildBaseResourceEndpoint("https://example.com/resource", "https://example.com", null),
                new TestCaseBuildBaseResourceEndpoint("http://localhost:8080/path", "http://localhost:8080", null),
                new TestCaseBuildBaseResourceEndpoint("https://example.com:8443/path", "https://example.com:8443", null),
                new TestCaseBuildBaseResourceEndpoint("http://example.org/", "http://example.org", null),

                new TestCaseBuildBaseResourceEndpoint(null, null, "URL cannot be null or empty."),
                new TestCaseBuildBaseResourceEndpoint("", null, "URL cannot be null or empty."),
                new TestCaseBuildBaseResourceEndpoint("not-a-valid-url", null, "Invalid URL: Missing scheme or host."),
                new TestCaseBuildBaseResourceEndpoint("://missing-scheme.com", null, "Invalid URL format: ://missing-scheme.com"),
                new TestCaseBuildBaseResourceEndpoint("http://", null, "Invalid URL format: http://")
        );
    }

    @ParameterizedTest
    @MethodSource("provideTestCasesForBaseResourceEndpoint")
    void testBuildBaseResourceEndpoint(TestCaseBuildBaseResourceEndpoint testCase) {
        if (testCase.expectedExceptionMessage != null) {
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> ResourceEndpointUtil.buildBaseResourceEndpoint(testCase.inputUrl),
                    "Exception was expected but not thrown"
            );
            assertEquals(testCase.expectedExceptionMessage, exception.getMessage());
        } else {
            String result = ResourceEndpointUtil.buildBaseResourceEndpoint(testCase.inputUrl);
            assertEquals(testCase.expectedOutput, result);
        }
    }

    static Stream<TestCaseBuildMetadataEndpoint> provideTestCasesForMetadataEndpoint() {
        return Stream.of(
                new TestCaseBuildMetadataEndpoint(
                        "https://example.com/resource",
                        "openid-configuration",
                        false,
                        "https://example.com/.well-known/openid-configuration/resource",
                        null
                ),
                new TestCaseBuildMetadataEndpoint(
                        "http://localhost:8080/path/",
                        "metadata",
                        true,
                        "http://localhost:8080/.well-known/metadata",
                        null
                ),
                new TestCaseBuildMetadataEndpoint(
                        "https://example.org:8443/",
                        "discovery",
                        true,
                        "https://example.org:8443/.well-known/discovery",
                        null
                ),

                new TestCaseBuildMetadataEndpoint(
                        "invalid-url",
                        "openid-configuration",
                        false,
                        null,
                        "Invalid URL: Missing scheme or host."
                ),
                new TestCaseBuildMetadataEndpoint(
                        null,
                        "metadata",
                        true,
                        null,
                        "URL cannot be null or empty."
                ),
                new TestCaseBuildMetadataEndpoint(
                        "",
                        "metadata",
                        true,
                        null,
                        "URL cannot be null or empty."
                )
        );
    }

    @ParameterizedTest
    @MethodSource("provideTestCasesForMetadataEndpoint")
    void testBuildMetadataEndpoint(TestCaseBuildMetadataEndpoint testCase) {
        if (testCase.expectedExceptionMessage != null) {
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> ResourceEndpointUtil.buildMetadataEndpoint(
                            testCase.resourceEndpoint,
                            testCase.wellKnownSuffix,
                            testCase.ignorePathSuffix
                    )
            );
            assertEquals(testCase.expectedExceptionMessage, exception.getMessage());
        } else {
            String result = ResourceEndpointUtil.buildMetadataEndpoint(
                    testCase.resourceEndpoint,
                    testCase.wellKnownSuffix,
                    testCase.ignorePathSuffix
            );
            assertEquals(testCase.expectedOutput, result);
        }
    }

    static Stream<TestCaseBuildAppendedMetadataEndpoint> provideTestCasesForAppendedMetadataEndpoint() {
        return Stream.of(
                new TestCaseBuildAppendedMetadataEndpoint(
                        "https://keycloak.example.com/realms/dial",
                        "openid-configuration",
                        "https://keycloak.example.com/realms/dial/.well-known/openid-configuration",
                        null
                ),
                new TestCaseBuildAppendedMetadataEndpoint(
                        "https://keycloak.example.com/realms/dial/",
                        "openid-configuration",
                        "https://keycloak.example.com/realms/dial/.well-known/openid-configuration",
                        null
                ),
                new TestCaseBuildAppendedMetadataEndpoint(
                        "http://localhost:8080/realms/dial",
                        "openid-configuration",
                        "http://localhost:8080/realms/dial/.well-known/openid-configuration",
                        null
                ),
                new TestCaseBuildAppendedMetadataEndpoint(
                        "https://example.com",
                        "openid-configuration",
                        "https://example.com/.well-known/openid-configuration",
                        null
                ),

                new TestCaseBuildAppendedMetadataEndpoint(
                        "invalid-url",
                        "openid-configuration",
                        null,
                        "Invalid URL: Missing scheme or host."
                ),
                new TestCaseBuildAppendedMetadataEndpoint(
                        null,
                        "openid-configuration",
                        null,
                        "URL cannot be null or empty."
                ),
                new TestCaseBuildAppendedMetadataEndpoint(
                        "",
                        "openid-configuration",
                        null,
                        "URL cannot be null or empty."
                )
        );
    }

    @ParameterizedTest
    @MethodSource("provideTestCasesForAppendedMetadataEndpoint")
    void testBuildAppendedMetadataEndpoint(TestCaseBuildAppendedMetadataEndpoint testCase) {
        if (testCase.expectedExceptionMessage != null) {
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> ResourceEndpointUtil.buildAppendedMetadataEndpoint(
                            testCase.resourceEndpoint,
                            testCase.wellKnownSuffix
                    )
            );
            assertEquals(testCase.expectedExceptionMessage, exception.getMessage());
        } else {
            String result = ResourceEndpointUtil.buildAppendedMetadataEndpoint(
                    testCase.resourceEndpoint,
                    testCase.wellKnownSuffix
            );
            assertEquals(testCase.expectedOutput, result);
        }
    }

    private static class TestCaseBuildBaseResourceEndpoint {
        String inputUrl;
        String expectedOutput;
        String expectedExceptionMessage;

        TestCaseBuildBaseResourceEndpoint(String inputUrl, String expectedOutput, String expectedExceptionMessage) {
            this.inputUrl = inputUrl;
            this.expectedOutput = expectedOutput;
            this.expectedExceptionMessage = expectedExceptionMessage;
        }
    }

    private static class TestCaseBuildMetadataEndpoint {
        String resourceEndpoint;
        String wellKnownSuffix;
        boolean ignorePathSuffix;
        String expectedOutput;
        String expectedExceptionMessage;

        TestCaseBuildMetadataEndpoint(String resourceEndpoint, String wellKnownSuffix, boolean ignorePathSuffix, String expectedOutput, String expectedExceptionMessage) {
            this.resourceEndpoint = resourceEndpoint;
            this.wellKnownSuffix = wellKnownSuffix;
            this.ignorePathSuffix = ignorePathSuffix;
            this.expectedOutput = expectedOutput;
            this.expectedExceptionMessage = expectedExceptionMessage;
        }
    }

    private static class TestCaseBuildAppendedMetadataEndpoint {
        String resourceEndpoint;
        String wellKnownSuffix;
        String expectedOutput;
        String expectedExceptionMessage;

        TestCaseBuildAppendedMetadataEndpoint(String resourceEndpoint, String wellKnownSuffix, String expectedOutput, String expectedExceptionMessage) {
            this.resourceEndpoint = resourceEndpoint;
            this.wellKnownSuffix = wellKnownSuffix;
            this.expectedOutput = expectedOutput;
            this.expectedExceptionMessage = expectedExceptionMessage;
        }
    }
}
