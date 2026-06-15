package com.epam.aidial.core.openapi;

import com.epam.aidial.core.openapi.annotations.OpenApiDescriptions;
import com.epam.aidial.core.openapi.annotations.ResponseProfile;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;

/**
 * Builds standard error responses for OpenAPI operations based on ResponseProfile.
 *
 * <p>Profiles are now self-describing (declare their response codes inline), so this builder
 * simply iterates over the profile's codes and creates the appropriate responses.
 */
final class ResponseProfileBuilder {

    private ResponseProfileBuilder() {
    }

    /**
     * Adds all standard error responses defined by the profile to the given ApiResponses object.
     *
     * @param responses the ApiResponses object to populate
     * @param profile the ResponseProfile defining which error responses to add
     * @param schemaGenerator the schema generator for error body schemas
     */
    static void addProfileResponses(ApiResponses responses, ResponseProfile profile, DtoSchemaGenerator schemaGenerator) {
        for (String code : profile.getResponseCodes()) {
            ApiResponse response = createStandardResponse(code, schemaGenerator);
            responses.addApiResponse(code, response);
        }
    }

    /**
     * Registers ErrorData schema if the profile includes any error responses that use it.
     *
     * @param profile the ResponseProfile to check
     * @param schemaGenerator the schema generator to register ErrorData with
     */
    static void registerProfileSchemas(ResponseProfile profile, DtoSchemaGenerator schemaGenerator) {
        if (profile == ResponseProfile.NONE || profile.getResponseCodes().isEmpty()) {
            return;
        }
        // ErrorData is used by all error responses except 304, 405, and 412
        for (String code : profile.getResponseCodes()) {
            if (!"304".equals(code) && !"405".equals(code) && !"412".equals(code)) {
                ResponseSchemaFactory.registerResponseBody(
                        com.epam.aidial.core.server.data.ErrorData.class,
                        schemaGenerator
                );
                return; // Only need to register once
            }
        }
    }

    /**
     * Creates a standard ApiResponse for the given HTTP status code.
     *
     * @param code the HTTP status code (e.g., "401", "404", "500")
     * @param schemaGenerator the schema generator for error body schemas
     * @return the ApiResponse object with appropriate description and error body
     * @throws IllegalArgumentException if the code is not supported
     */
    private static ApiResponse createStandardResponse(String code, DtoSchemaGenerator schemaGenerator) {
        return switch (code) {
            case "304" -> StandardResponses.notModified();
            case "400" -> StandardResponses.badRequest(schemaGenerator);
            case "401" -> StandardResponses.unauthorized(schemaGenerator);
            case "403" -> StandardResponses.forbidden(schemaGenerator);
            case "404" -> StandardResponses.notFound(OpenApiDescriptions.RESPONSE_NOT_FOUND, schemaGenerator);
            case "405" -> StandardResponses.methodNotAllowed();
            case "409" -> StandardResponses.conflict(schemaGenerator);
            case "412" -> StandardResponses.preconditionFailed();
            case "413" -> StandardResponses.payloadTooLarge(schemaGenerator);
            case "415" -> StandardResponses.unsupportedMediaType(schemaGenerator);
            case "422" -> StandardResponses.unprocessableEntity(schemaGenerator);
            case "429" -> StandardResponses.rateLimited(schemaGenerator);
            case "500" -> StandardResponses.internalServerError(schemaGenerator);
            case "502" -> StandardResponses.upstreamError(schemaGenerator);
            case "503" -> StandardResponses.overloaded(schemaGenerator);
            default -> throw new IllegalArgumentException("Unsupported response code: " + code + ". Add support in StandardResponses and ResponseProfileBuilder.");
        };
    }
}
