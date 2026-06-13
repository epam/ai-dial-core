package com.epam.aidial.core.openapi;

import com.epam.aidial.core.openapi.annotations.OpenApiDescriptions;
import com.epam.aidial.core.openapi.annotations.ResponseProfile;
import io.swagger.v3.oas.models.responses.ApiResponses;

final class ResponseProfileBuilder {

    private ResponseProfileBuilder() {
    }

    static void addProfileResponses(ApiResponses responses, ResponseProfile profile, DtoSchemaGenerator schemaGenerator) {
        switch (profile) {
            case AUTHENTICATED_READ -> {
                responses.addApiResponse("401", StandardResponses.unauthorized(schemaGenerator));
            }
            case AUTHENTICATED_READ_WITH_NOT_FOUND -> {
                responses.addApiResponse("401", StandardResponses.unauthorized(schemaGenerator));
                responses.addApiResponse("404", StandardResponses.notFound(
                        OpenApiDescriptions.RESPONSE_NOT_FOUND, schemaGenerator));
                responses.addApiResponse("500", StandardResponses.internalServerError(schemaGenerator));
            }
            case AUTHENTICATED_READ_WITH_SERVER_ERROR -> {
                responses.addApiResponse("401", StandardResponses.unauthorized(schemaGenerator));
                responses.addApiResponse("500", StandardResponses.internalServerError(schemaGenerator));
            }
            case CONDITIONAL_WRITE, CONDITIONAL_DELETE -> {
                responses.addApiResponse("401", StandardResponses.unauthorized(schemaGenerator));
                responses.addApiResponse("412", StandardResponses.preconditionFailed());
            }
            case OPS_WITH_BAD_REQUEST -> {
                responses.addApiResponse("400", StandardResponses.badRequest(schemaGenerator));
                responses.addApiResponse("401", StandardResponses.unauthorized(schemaGenerator));
            }
            case APPLICATION_OPS -> {
                responses.addApiResponse("400", StandardResponses.badRequest(schemaGenerator));
                responses.addApiResponse("401", StandardResponses.unauthorized(schemaGenerator));
                responses.addApiResponse("403", StandardResponses.forbidden(schemaGenerator));
                responses.addApiResponse("404", StandardResponses.notFound(
                        OpenApiDescriptions.RESPONSE_NOT_FOUND, schemaGenerator));
                responses.addApiResponse("409", StandardResponses.conflict(schemaGenerator));
                responses.addApiResponse("500", StandardResponses.internalServerError(schemaGenerator));
            }
            case AUTHORIZED_OPERATION -> {
                responses.addApiResponse("400", StandardResponses.badRequest(schemaGenerator));
                responses.addApiResponse("401", StandardResponses.unauthorized(schemaGenerator));
                responses.addApiResponse("403", StandardResponses.forbidden(schemaGenerator));
                responses.addApiResponse("500", StandardResponses.internalServerError(schemaGenerator));
            }
            case AUTHENTICATED_OPERATION -> {
                responses.addApiResponse("400", StandardResponses.badRequest(schemaGenerator));
                responses.addApiResponse("401", StandardResponses.unauthorized(schemaGenerator));
                responses.addApiResponse("500", StandardResponses.internalServerError(schemaGenerator));
            }
            case LLM_PROXY -> {
                responses.addApiResponse("401", StandardResponses.unauthorized(schemaGenerator));
                responses.addApiResponse("404", StandardResponses.notFound(
                        OpenApiDescriptions.RESPONSE_DEPLOYMENT_NOT_FOUND, schemaGenerator));
                responses.addApiResponse("429", StandardResponses.rateLimited(schemaGenerator));
                responses.addApiResponse("500", StandardResponses.internalServerError(schemaGenerator));
                responses.addApiResponse("502", StandardResponses.upstreamError(schemaGenerator));
                responses.addApiResponse("503", StandardResponses.overloaded(schemaGenerator));
            }
            case LLM_EMBEDDING -> {
                responses.addApiResponse("401", StandardResponses.unauthorized(schemaGenerator));
                responses.addApiResponse("404", StandardResponses.notFound(
                        OpenApiDescriptions.RESPONSE_DEPLOYMENT_NOT_FOUND, schemaGenerator));
                responses.addApiResponse("429", StandardResponses.rateLimited(schemaGenerator));
                responses.addApiResponse("500", StandardResponses.internalServerError(schemaGenerator));
                responses.addApiResponse("503", StandardResponses.overloaded(schemaGenerator));
            }
            case CODE_INTERPRETER -> {
                responses.addApiResponse("400", StandardResponses.badRequest(schemaGenerator));
                responses.addApiResponse("401", StandardResponses.unauthorized(schemaGenerator));
                responses.addApiResponse("403", StandardResponses.forbidden(schemaGenerator));
                responses.addApiResponse("404", StandardResponses.notFound(
                        OpenApiDescriptions.RESPONSE_SESSION_NOT_FOUND, schemaGenerator));
                responses.addApiResponse("500", StandardResponses.internalServerError(schemaGenerator));
            }
            case LIMIT_WITH_NOT_FOUND -> {
                responses.addApiResponse("401", StandardResponses.unauthorized(schemaGenerator));
                responses.addApiResponse("404", StandardResponses.notFound(
                        OpenApiDescriptions.RESPONSE_LIMIT_NOT_FOUND, schemaGenerator));
            }
            case TOOLSET_TOOLS -> {
                responses.addApiResponse("401",
                        StandardResponses.unauthorized(schemaGenerator));

                responses.addApiResponse("403",
                        StandardResponses.forbidden(schemaGenerator));

                responses.addApiResponse("404",
                        StandardResponses.notFound(
                        OpenApiDescriptions.RESPONSE_NOT_FOUND,
                        schemaGenerator));

                responses.addApiResponse("500",
                        StandardResponses.internalServerError(schemaGenerator));

                responses.addApiResponse("502",
                        StandardResponses.upstreamError(schemaGenerator));
            }
            case NONE -> {
                // no-op
            }
            default -> throw new IllegalStateException("Unexpected profile: " + profile);
        }
    }
}