package com.epam.aidial.core.credentials.service.metadata;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

import java.net.http.HttpHeaders;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class HttpHeadersHandlerTest {

    private final HttpHeadersHandler handler = new HttpHeadersHandler();

    static Stream<Arguments> provideHeadersForExtractMetadataTests() {
        return Stream.of(
                Arguments.of(null, Optional.empty()),
                Arguments.of(Collections.emptyMap(), Optional.empty()),
                Arguments.of(
                        Map.of("Content-Type", "application/json"),
                        Optional.empty()
                ),
                Arguments.of(
                        Map.of("WWW-Authenticate", "Bearer realm=example"),
                        Optional.empty()
                ),
                Arguments.of(
                        Map.of("WWW-Authenticate", "Bearer realm=example, resource_metadata="),
                        Optional.empty()
                ),
                Arguments.of(
                        Map.of("WWW-Authenticate", "Bearer realm=example, resource_metadata= https://metadata-url.com"),
                        Optional.of("https://metadata-url.com")
                ),
                Arguments.of(
                        Map.of("WWW-Authenticate", "Bearer realm=example, resource_metadata=\"https://metadata-url.com\""),
                        Optional.of("https://metadata-url.com")
                ),
                Arguments.of(
                        Map.of("WWW-Authenticate", "Bearer realm=example, resource_metadata=    \"https://metadata-url.com\""),
                        Optional.of("https://metadata-url.com")
                ),
                Arguments.of(
                        Map.of("WWW-Authenticate", "Bearer realm=example, resource_metadata=\"\", token=\"abc\""),
                        Optional.empty()
                )
        );
    }

    @ParameterizedTest
    @MethodSource("provideHeadersForExtractMetadataTests")
    void testExtractMetadataUrl(Map<String, String> headers, Optional<String> expected) {
        // Given & When
        Optional<String> result = handler.extractMetadataUrl(headers);

        // Then
        if (expected.isEmpty()) {
            assertTrue(result.isEmpty(), "Expected metadata URL to be empty, but was: " + result);
        } else {
            assertTrue(result.isPresent(), "Expected metadata URL to be present, but was empty.");
            assertEquals(expected.get(), result.get(), "Extracted metadata URL does not match the expected URL.");
        }
    }

    static Stream<Arguments> provideHttpHeadersConversion() {
        return Stream.of(
                Arguments.of(Collections.emptyMap(), Collections.emptyMap()),
                Arguments.of(
                        Map.of(
                                "Content-Type", Collections.singletonList("application/json"),
                                "Authorization", Collections.singletonList("Bearer token")
                        ),
                        Map.of(
                                "Content-Type", "application/json",
                                "Authorization", "Bearer token"
                        )
                ),
                Arguments.of(
                        Map.of(
                                "Set-Cookie", List.of("cookie1=value1", "cookie2=value2")
                        ),
                        Map.of(
                                "Set-Cookie", "cookie1=value1,cookie2=value2"
                        )
                )
        );
    }

    @ParameterizedTest
    @MethodSource("provideHttpHeadersConversion")
    void testConvertHttpHeadersToMap(Map<String, List<String>> headersInput, Map<String, String> expectedOutput) {
        HttpHeaders httpHeaders = Mockito.mock(HttpHeaders.class);
        when(httpHeaders.map()).thenReturn(headersInput);

        Map<String, String> result = handler.convertHttpHeadersToMap(httpHeaders);
        assertEquals(expectedOutput, result, "Converted headers map does not match expected output");
    }
}
