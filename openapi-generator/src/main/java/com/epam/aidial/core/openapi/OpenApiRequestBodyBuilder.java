package com.epam.aidial.core.openapi;

import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;

import java.util.Arrays;

public final class OpenApiRequestBodyBuilder {

    private OpenApiRequestBodyBuilder() {
    }

    public static RequestBody build(EndpointMetadata.Endpoint endpoint, DtoSchemaGenerator schemaGenerator) {
        if (!ResponseSchemaFactory.hasBodyType(endpoint.requestBody())
                && !ResponseSchemaFactory.hasRequestOneOf(endpoint)
                && (endpoint.requestBodySchemaRef() == null || endpoint.requestBodySchemaRef().isBlank())) {
            return null;
        }

        RequestBody requestBody = new RequestBody();
        requestBody.setRequired(true);

        Content content = new Content();
        MediaType mediaType = new MediaType();
        Schema<?> schema;
        if (ResponseSchemaFactory.isMultipartBinaryUpload(endpoint.requestBody(), endpoint.contentType())) {
            schema = ResponseSchemaFactory.multipartBinaryFileUploadSchema();
        } else {
            if (endpoint.requestOneOf() != null && endpoint.requestOneOf().length > 0) {
                schema = ResponseSchemaFactory.oneOf(endpoint.requestOneOf(), schemaGenerator);
            } else {
                schema = ResponseSchemaFactory.forBody(endpoint.requestBodySchemaRef(), endpoint.requestBody(), schemaGenerator);
            }
        }
        mediaType.setSchema(schema);
        content.addMediaType(endpoint.contentType(), mediaType);
        requestBody.setContent(content);
        return requestBody;
    }

    public static void registerRequestBodySchemas(EndpointMetadata.Endpoint endpoint, DtoSchemaGenerator schemaGenerator) {
        if (endpoint.requestBodySchemaRef() != null && !endpoint.requestBodySchemaRef().isBlank()) {
            schemaGenerator.registerExternalSchema(endpoint.requestBodySchemaRef());
        }
        if (endpoint.requestOneOf() != null && endpoint.requestOneOf().length > 0) {
            for (Class<?> type : endpoint.requestOneOf()) {
                if (type.isArray()) {
                    schemaGenerator.processType(type.getComponentType());
                } else {
                    schemaGenerator.processType(type);
                }
            }
            return;
        }
        ResponseSchemaFactory.registerRequestBody(
                endpoint.requestBody(),
                endpoint.contentType(),
                schemaGenerator
        );
    }
}