package com.epam.aidial.core.openapi;

import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ResponseProfile;
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
        if (endpoint.responseProfile() != ResponseProfile.NONE) {
            ResponseProfileBuilder.addProfileResponses(responses, endpoint.responseProfile(), schemaGenerator);
        }
        return responses;
    }

    private static ApiResponses buildExplicitResponses(EndpointMetadata.Endpoint endpoint, DtoSchemaGenerator schemaGenerator) {
        ApiResponses responses = new ApiResponses();
        if (endpoint.responses() == null) {
            return responses;
        }
        TreeMap<Integer, io.swagger.v3.oas.models.responses.ApiResponse> sorted = new TreeMap<>();
        for (ApiResponse annotation : endpoint.responses()) {
            io.swagger.v3.oas.models.responses.ApiResponse response = sorted.computeIfAbsent(annotation.code(), code -> {
                io.swagger.v3.oas.models.responses.ApiResponse r = new io.swagger.v3.oas.models.responses.ApiResponse();
                r.setDescription(annotation.description());

                // Process headers
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
        // Register schemas from ResponseProfile (e.g., ErrorData for error responses)
        if (endpoint.responseProfile() != ResponseProfile.NONE) {
            ResponseProfileBuilder.registerProfileSchemas(endpoint.responseProfile(), schemaGenerator);
        }
    }

}