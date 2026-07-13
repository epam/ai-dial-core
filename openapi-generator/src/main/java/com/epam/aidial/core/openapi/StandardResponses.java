package com.epam.aidial.core.openapi;

import com.epam.aidial.core.openapi.annotations.OpenApiDescriptions;
import com.epam.aidial.core.server.data.ErrorData;
import io.swagger.v3.oas.models.responses.ApiResponse;

/**
 * Reusable OpenAPI response builders for fallback and composition in {@link OpenApiResponseBuilder}.
 */
public final class StandardResponses {

    private StandardResponses() {
    }

    public static ApiResponse byStatus(int code, DtoSchemaGenerator generator) {
        return switch (code) {
            case 200 -> responseWithDescription(OpenApiDescriptions.RESPONSE_SUCCESS);
            case 304 -> responseWithDescription(OpenApiDescriptions.RESPONSE_NOT_MODIFIED);
            case 400 -> withErrorBody(OpenApiDescriptions.RESPONSE_BAD_REQUEST, generator);
            case 401 -> withErrorBody(OpenApiDescriptions.RESPONSE_INVALID_AUTHENTICATION, generator);
            case 403 -> withErrorBody(OpenApiDescriptions.RESPONSE_FORBIDDEN, generator);
            case 404 -> withErrorBody(OpenApiDescriptions.RESPONSE_NOT_FOUND, generator);
            case 405 -> responseWithDescription(OpenApiDescriptions.RESPONSE_METHOD_NOT_ALLOWED);
            case 409 -> withErrorBody(OpenApiDescriptions.RESPONSE_CONFLICT, generator);
            case 412 -> responseWithDescription(OpenApiDescriptions.RESPONSE_PRECONDITION_FAILED);
            case 413 -> withErrorBody(OpenApiDescriptions.RESPONSE_PAYLOAD_TOO_LARGE, generator);
            case 415 -> withErrorBody(OpenApiDescriptions.RESPONSE_UNSUPPORTED_MEDIA_TYPE, generator);
            case 422 -> withErrorBody(OpenApiDescriptions.RESPONSE_UNPROCESSABLE_ENTITY, generator);
            case 424 -> withErrorBody(OpenApiDescriptions.RESPONSE_FAILED_DEPENDENCY, generator);
            case 429 -> withErrorBody(OpenApiDescriptions.RESPONSE_RATE_LIMIT, generator);
            case 500 -> withErrorBody(OpenApiDescriptions.RESPONSE_SERVER_ERROR, generator);
            case 502 -> withErrorBody(OpenApiDescriptions.RESPONSE_UPSTREAM_ERROR, generator);
            case 503 -> withErrorBody(OpenApiDescriptions.RESPONSE_OVERLOADED, generator);
            case 504 -> withErrorBody(OpenApiDescriptions.RESPONSE_GATEWAY_TIMEOUT, generator);
            default -> throw new IllegalArgumentException("Unsupported response code: " + code + ". Add support in StandardResponses.");
        };
    }

    private static ApiResponse withErrorBody(String description, DtoSchemaGenerator schemaGenerator) {
        ApiResponse response = new ApiResponse();
        response.setDescription(description);
        response.setContent(ResponseContentFactory.build(
                new String[]{"application/json"},
                ApiSchemaBuilder.forImplementation(ErrorData.class),
                schemaGenerator
        ));
        return response;
    }

    private static ApiResponse responseWithDescription(String description) {
        ApiResponse response = new ApiResponse();
        response.setDescription(description);
        return response;
    }
}