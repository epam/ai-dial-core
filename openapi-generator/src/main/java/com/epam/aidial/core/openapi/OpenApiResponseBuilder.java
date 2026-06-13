package com.epam.aidial.core.openapi;

import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ResponseProfile;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.responses.ApiResponses;

import java.lang.reflect.Type;
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
            Content content = ResponseContentFactory.build(annotation.contentTypes(), annotation.schemaRef(),
                    resolveResponseType(annotation), annotation.responseOneOf(), schemaGenerator);
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

    private static Type resolveResponseType(ApiResponse response) {
        if (response.responseOneOf() != null && response.responseOneOf().length > 0) {
            return null;
        }
        if (response.body() == Void.class) {
            return null;
        }
        if (response.wrapper() != Void.class) {
            return EndpointMetadata.paramType(response.wrapper(), response.body());
        }
        return response.body();
    }

    public static void registerResponseSchemas(EndpointMetadata.Endpoint endpoint, DtoSchemaGenerator schemaGenerator) {
        if (endpoint.responses() == null) {
            return;
        }
        for (ApiResponse response : endpoint.responses()) {
            if (!response.schemaRef().isBlank()) {
                schemaGenerator.registerExternalSchema(response.schemaRef());
            }
            if (response.responseOneOf() != null && response.responseOneOf().length > 0) {
                for (Class<?> type : response.responseOneOf()) {
                    ResponseSchemaFactory.registerResponseBody(type, schemaGenerator);
                }
            } else {
                ResponseSchemaFactory.registerResponseBody(resolveResponseType(response), schemaGenerator);
            }
        }
    }

}