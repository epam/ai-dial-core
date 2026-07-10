package com.epam.aidial.core.openapi;

import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;

public final class OpenApiRequestBodyBuilder {

    private OpenApiRequestBodyBuilder() {
    }

    public static RequestBody build(EndpointMetadata.Endpoint endpoint, DtoSchemaGenerator schemaGenerator) {
        if (ResponseSchemaFactory.isEmpty(endpoint.requestBody())) {
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
            schema = ResponseSchemaFactory.forSchema(endpoint.requestBody(), schemaGenerator);
        }
        mediaType.setSchema(schema);
        content.addMediaType(endpoint.contentType(), mediaType);
        requestBody.setContent(content);
        return requestBody;
    }

    public static void registerRequestBodySchemas(EndpointMetadata.Endpoint endpoint, DtoSchemaGenerator schemaGenerator) {
        ResponseSchemaFactory.registerSchema(endpoint.requestBody(), schemaGenerator);
    }
}