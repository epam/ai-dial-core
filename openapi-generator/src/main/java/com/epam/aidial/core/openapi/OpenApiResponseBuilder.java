package com.epam.aidial.core.openapi;

import com.epam.aidial.core.openapi.annotations.ApiResponse;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.responses.ApiResponses;

import java.util.Map;
import java.util.TreeMap;

public final class OpenApiResponseBuilder {

    private OpenApiResponseBuilder() {
    }

    public static ApiResponses buildResponses(EndpointMetadata.Endpoint endpoint, DtoSchemaGenerator schemaGenerator) {
        ApiResponses responses = buildExplicitResponses(endpoint, schemaGenerator);
        return sortResponses(responses);
    }

    /**
     * Sorts responses by status code to ensure deterministic ordering.
     * Numeric codes are sorted ascending, "default" appears last.
     */
    private static ApiResponses sortResponses(ApiResponses responses) {
        if (responses == null || responses.isEmpty()) {
            return responses;
        }

        ApiResponses sorted = new ApiResponses();
        responses.keySet().stream()
                .sorted((code1, code2) -> {
                    // "default" always comes last
                    if ("default".equals(code1)) {
                        return 1;
                    }
                    if ("default".equals(code2)) {
                        return -1;
                    }

                    // Numeric codes sort numerically
                    try {
                        return Integer.compare(Integer.parseInt(code1), Integer.parseInt(code2));
                    } catch (NumberFormatException e) {
                        // Fallback to string comparison for non-numeric codes
                        return code1.compareTo(code2);
                    }
                })
                .forEach(code -> sorted.addApiResponse(code, responses.get(code)));

        return sorted;
    }

    private static ApiResponses buildExplicitResponses(EndpointMetadata.Endpoint endpoint, DtoSchemaGenerator schemaGenerator) {
        ApiResponses responses = new ApiResponses();
        if (endpoint.responses() == null) {
            return responses;
        }
        TreeMap<Integer, io.swagger.v3.oas.models.responses.ApiResponse> sorted = new TreeMap<>();
        for (ApiResponse annotation : endpoint.responses()) {
            io.swagger.v3.oas.models.responses.ApiResponse response = sorted.computeIfAbsent(annotation.code(), code -> {
                io.swagger.v3.oas.models.responses.ApiResponse r;
                if (annotation.description().isBlank()) {
                    r = StandardResponses.byStatus(code, schemaGenerator);

                    if (r == null) {
                        r = new io.swagger.v3.oas.models.responses.ApiResponse();
                    }
                } else {
                    r = new io.swagger.v3.oas.models.responses.ApiResponse();
                    r.setDescription(annotation.description());
                }

                Map<String, Header> headers = OpenApiHeaderBuilder.buildHeaders(annotation.headers());
                if (!headers.isEmpty()) {
                    headers.forEach(r::addHeaderObject);
                }

                return r;
            });
            Content content = ResponseContentFactory.build(annotation.contentTypes(), annotation.body(), schemaGenerator);
            if (content == null) {
                continue;
            }
            if (response.getContent() == null) {
                response.setContent(content);
            } else {
                content.forEach(response.getContent()::addMediaType);
            }
        }
        sorted.forEach((code, response) -> responses.addApiResponse(String.valueOf(code), response));
        return responses;
    }

    public static void registerResponseSchemas(EndpointMetadata.Endpoint endpoint, DtoSchemaGenerator schemaGenerator) {
        // Register schemas from explicit @ApiResponse annotations
        if (endpoint.responses() != null) {
            for (ApiResponse response : endpoint.responses()) {
                ResponseSchemaFactory.registerSchema(response.body(), schemaGenerator);
            }
        }
    }

}