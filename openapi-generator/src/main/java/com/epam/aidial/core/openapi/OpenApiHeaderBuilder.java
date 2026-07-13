package com.epam.aidial.core.openapi;

import com.epam.aidial.core.openapi.annotations.ApiHeader;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Schema;

import java.util.LinkedHashMap;
import java.util.Map;

public final class OpenApiHeaderBuilder {

    private OpenApiHeaderBuilder() {
    }

    public static Map<String, Header> buildHeaders(ApiHeader[] apiHeaders) {
        if (apiHeaders == null || apiHeaders.length == 0) {
            return Map.of();
        }

        Map<String, Header> headers = new LinkedHashMap<>();
        for (ApiHeader apiHeader : apiHeaders) {
            headers.put(apiHeader.name(), toHeader(apiHeader));
        }
        return headers;
    }

    private static Header toHeader(ApiHeader apiHeader) {
        Header header = new Header();

        if (!apiHeader.description().isEmpty()) {
            header.setDescription(apiHeader.description());
        }

        header.setRequired(apiHeader.required());
        header.setSchema(buildSchema(apiHeader));

        return header;
    }

    private static Schema<?> buildSchema(ApiHeader apiHeader) {
        Class<?> schemaClass = apiHeader.schema();

        if (OpenApiParameterBuilder.isInlinePrimitiveType(schemaClass)) {
            return OpenApiParameterBuilder.inlinePrimitiveSchema(schemaClass);
        }

        // Fallback to string for unsupported types
        Schema<Object> schema = new Schema<>();
        schema.setType("string");
        return schema;
    }
}
