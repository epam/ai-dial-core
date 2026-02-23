package com.epam.aidial.core.credentials.util;

import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class FallbackHandlerTest {

    @Test
    @SuppressWarnings("unchecked")
    void testFirstEndpointSucceeds() {
        Set<String> endpoints = new LinkedHashSet<>();
        endpoints.add("endpoint1");
        endpoints.add("endpoint2");

        Function<String, String> fetchFunction = endpoint -> "data";
        BiConsumer<String, String> validationFunction = mock(BiConsumer.class);

        String result = FallbackHandler.executeWithFallback(endpoints, fetchFunction, validationFunction);

        assertEquals("data", result);
        verify(validationFunction).accept("endpoint1", "data");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testFirstEndpointFailsSecondSucceeds() {
        Set<String> endpoints = new LinkedHashSet<>();
        endpoints.add("endpoint1");
        endpoints.add("endpoint2");

        Function<String, String> fetchFunction = endpoint -> {
            if (endpoint.equals("endpoint1")) {
                throw new HttpException(HttpStatus.NOT_FOUND, "Not found");
            }
            return "data2";
        };
        BiConsumer<String, String> validationFunction = mock(BiConsumer.class);

        String result = FallbackHandler.executeWithFallback(endpoints, fetchFunction, validationFunction);

        assertEquals("data2", result);
        verify(validationFunction).accept("endpoint2", "data2");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testAllEndpointsFail() {
        Set<String> endpoints = new LinkedHashSet<>();
        endpoints.add("endpoint1");
        endpoints.add("endpoint2");

        Function<String, String> fetchFunction = endpoint -> {
            throw new HttpException(HttpStatus.INTERNAL_SERVER_ERROR, "Server error");
        };
        BiConsumer<String, String> validationFunction = mock(BiConsumer.class);

        String result = FallbackHandler.executeWithFallback(endpoints, fetchFunction, validationFunction);

        assertNull(result);
        verify(validationFunction, never()).accept(anyString(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testValidationFailsFallsThrough() {
        Set<String> endpoints = new LinkedHashSet<>();
        endpoints.add("endpoint1");
        endpoints.add("endpoint2");

        Function<String, String> fetchFunction = endpoint -> "data";
        BiConsumer<String, String> validationFunction = (endpoint, result) -> {
            if (endpoint.equals("endpoint1")) {
                throw new IllegalArgumentException("Validation failed");
            }
        };

        String result = FallbackHandler.executeWithFallback(endpoints, fetchFunction, validationFunction);

        assertEquals("data", result);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testNullResultFromFetchFunction() {
        Set<String> endpoints = new LinkedHashSet<>();
        endpoints.add("endpoint1");
        endpoints.add("endpoint2");

        Function<String, String> fetchFunction = endpoint -> null;
        BiConsumer<String, String> validationFunction = mock(BiConsumer.class);

        String result = FallbackHandler.executeWithFallback(endpoints, fetchFunction, validationFunction);

        assertNull(result);
        verify(validationFunction, never()).accept(anyString(), anyString());
    }
}