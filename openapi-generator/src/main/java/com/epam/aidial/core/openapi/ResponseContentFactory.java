package com.epam.aidial.core.openapi;

import com.epam.aidial.core.openapi.annotations.ApiSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;

final class ResponseContentFactory {

    private ResponseContentFactory() {
    }

    static Content build(String[] contentTypes, ApiSchema apiSchema, DtoSchemaGenerator schemaGenerator) {
        Content content = new Content();
        for (String contentType : contentTypes) {
            Schema<?> schema = ResponseSchemaFactory.forContentType(contentType, apiSchema, schemaGenerator);

            if (schema == null) {
                continue;
            }
            MediaType mediaType = new MediaType();
            mediaType.setSchema(schema);
            content.addMediaType(contentType, mediaType);
        }
        return content.isEmpty() ? null : content;
    }
}