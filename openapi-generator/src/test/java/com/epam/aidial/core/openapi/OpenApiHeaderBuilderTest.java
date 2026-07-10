package com.epam.aidial.core.openapi;

import com.epam.aidial.core.openapi.annotations.ApiHeader;
import io.swagger.v3.oas.models.headers.Header;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiHeaderBuilderTest {

    @Test
    void buildHeadersReturnsEmptyMapForNullInput() {
        Map<String, Header> headers = OpenApiHeaderBuilder.buildHeaders(null);
        assertTrue(headers.isEmpty());
    }

    @Test
    void buildHeadersReturnsEmptyMapForEmptyArray() {
        Map<String, Header> headers = OpenApiHeaderBuilder.buildHeaders(new ApiHeader[]{});
        assertTrue(headers.isEmpty());
    }

    @Test
    void buildSingleStringHeader() {
        ApiHeader header = mockHeader("X-Request-Id", "Request identifier", false, String.class);
        Map<String, Header> headers = OpenApiHeaderBuilder.buildHeaders(new ApiHeader[]{header});

        assertEquals(1, headers.size());
        Header result = headers.get("X-Request-Id");
        assertEquals("Request identifier", result.getDescription());
        assertFalse(result.getRequired());
        assertEquals("string", result.getSchema().getType());
    }

    @Test
    void buildRequiredIntegerHeader() {
        ApiHeader header = mockHeader("X-Rate-Limit", "Rate limit", true, Integer.class);
        Map<String, Header> headers = OpenApiHeaderBuilder.buildHeaders(new ApiHeader[]{header});

        Header result = headers.get("X-Rate-Limit");
        assertTrue(result.getRequired());
        assertEquals("integer", result.getSchema().getType());
    }

    @Test
    void buildMultipleHeadersPreservesOrder() {
        ApiHeader h1 = mockHeader("Header-A", "", false, String.class);
        ApiHeader h2 = mockHeader("Header-B", "", false, String.class);
        ApiHeader h3 = mockHeader("Header-C", "", false, String.class);

        Map<String, Header> headers = OpenApiHeaderBuilder.buildHeaders(new ApiHeader[]{h1, h2, h3});

        List<String> keyList = new ArrayList<>(headers.keySet());
        assertEquals(List.of("Header-A", "Header-B", "Header-C"), keyList);
    }

    @Test
    void buildHeadersSupportsAllPrimitiveTypes() {
        ApiHeader string = mockHeader("H-String", "", false, String.class);
        ApiHeader integer = mockHeader("H-Int", "", false, Integer.class);
        ApiHeader longType = mockHeader("H-Long", "", false, Long.class);
        ApiHeader bool = mockHeader("H-Bool", "", false, Boolean.class);
        ApiHeader doubleType = mockHeader("H-Double", "", false, Double.class);

        Map<String, Header> headers = OpenApiHeaderBuilder.buildHeaders(
                new ApiHeader[]{string, integer, longType, bool, doubleType});

        assertEquals("string", headers.get("H-String").getSchema().getType());
        assertEquals("integer", headers.get("H-Int").getSchema().getType());
        assertEquals("integer", headers.get("H-Long").getSchema().getType());
        assertEquals("boolean", headers.get("H-Bool").getSchema().getType());
        assertEquals("number", headers.get("H-Double").getSchema().getType());
    }

    @Test
    void buildHeaderWithEmptyDescriptionOmitsDescription() {
        ApiHeader header = mockHeader("X-Test", "", false, String.class);
        Map<String, Header> headers = OpenApiHeaderBuilder.buildHeaders(new ApiHeader[]{header});

        assertNull(headers.get("X-Test").getDescription());
    }

    @Test
    void buildHeaderWithUnsupportedTypeFallbacksToString() {
        ApiHeader header = mockHeader("X-Custom", "Custom", false, Object.class);
        Map<String, Header> headers = OpenApiHeaderBuilder.buildHeaders(new ApiHeader[]{header});

        assertEquals("string", headers.get("X-Custom").getSchema().getType());
    }

    private ApiHeader mockHeader(String name, String description, boolean required, Class<?> schema) {
        return new ApiHeader() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public boolean required() {
                return required;
            }

            @Override
            public Class<?> schema() {
                return schema;
            }

            @Override
            public Class<? extends java.lang.annotation.Annotation> annotationType() {
                return ApiHeader.class;
            }
        };
    }
}
