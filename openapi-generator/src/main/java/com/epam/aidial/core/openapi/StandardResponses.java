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

    public static ApiResponse badRequest(DtoSchemaGenerator schemaGenerator) {
        return withErrorBody(OpenApiDescriptions.RESPONSE_BAD_REQUEST, schemaGenerator);
    }

    public static ApiResponse unauthorized(DtoSchemaGenerator schemaGenerator) {
        return withErrorBody(OpenApiDescriptions.RESPONSE_INVALID_AUTHENTICATION, schemaGenerator);
    }

    public static ApiResponse forbidden(DtoSchemaGenerator schemaGenerator) {
        return withErrorBody(OpenApiDescriptions.RESPONSE_FORBIDDEN, schemaGenerator);
    }

    public static ApiResponse notFound(String description, DtoSchemaGenerator schemaGenerator) {
        return withErrorBody(description, schemaGenerator);
    }

    public static ApiResponse conflict(DtoSchemaGenerator schemaGenerator) {
        return withErrorBody(OpenApiDescriptions.RESPONSE_CONFLICT, schemaGenerator);
    }

    public static ApiResponse preconditionFailed() {
        ApiResponse response = new ApiResponse();
        response.setDescription(OpenApiDescriptions.RESPONSE_PRECONDITION_FAILED);
        return response;
    }

    public static ApiResponse rateLimited(DtoSchemaGenerator schemaGenerator) {
        return withErrorBody(OpenApiDescriptions.RESPONSE_RATE_LIMIT, schemaGenerator);
    }

    public static ApiResponse internalServerError(DtoSchemaGenerator schemaGenerator) {
        return withErrorBody(OpenApiDescriptions.RESPONSE_SERVER_ERROR, schemaGenerator);
    }

    public static ApiResponse upstreamError(DtoSchemaGenerator schemaGenerator) {
        return withErrorBody(OpenApiDescriptions.RESPONSE_UPSTREAM_ERROR, schemaGenerator);
    }

    public static ApiResponse overloaded(DtoSchemaGenerator schemaGenerator) {
        return withErrorBody(OpenApiDescriptions.RESPONSE_OVERLOADED, schemaGenerator);
    }

    public static ApiResponse notModified() {
        ApiResponse response = new ApiResponse();
        response.setDescription("Not Modified");
        return response;
    }

    public static ApiResponse methodNotAllowed() {
        ApiResponse response = new ApiResponse();
        response.setDescription("Method Not Allowed");
        return response;
    }

    public static ApiResponse unsupportedMediaType(DtoSchemaGenerator schemaGenerator) {
        return withErrorBody("Unsupported Media Type", schemaGenerator);
    }

    public static ApiResponse unprocessableEntity(DtoSchemaGenerator schemaGenerator) {
        return withErrorBody("Unprocessable Entity", schemaGenerator);
    }

    public static ApiResponse payloadTooLarge(DtoSchemaGenerator schemaGenerator) {
        return withErrorBody("Payload Too Large", schemaGenerator);
    }

    private static ApiResponse withErrorBody(String description, DtoSchemaGenerator schemaGenerator) {
        ApiResponse response = new ApiResponse();
        response.setDescription(description);
        response.setContent(ResponseContentFactory.build(
                new String[]{"application/json"},
                null,
                ErrorData.class,
                new Class<?>[0],
                schemaGenerator
        ));
        return response;
    }
}