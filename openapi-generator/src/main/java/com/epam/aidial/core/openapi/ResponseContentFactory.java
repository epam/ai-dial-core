package com.epam.aidial.core.openapi;

import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;

import java.lang.reflect.Type;

final class ResponseContentFactory {

    private ResponseContentFactory() {
    }

    static Content build(String[] contentTypes, String schemaRef, Type body, Class<?>[] responseOneOf,
                         Class<?>[] responseAllOf, Class<?> annotationBody, DtoSchemaGenerator schemaGenerator) {
        Content content = new Content();
        for (String contentType : contentTypes) {
            Schema<?> schema;

            // Priority: oneOf > allOf > regular body/schemaRef
            if (responseOneOf != null && responseOneOf.length > 0) {
                schema = ResponseSchemaFactory.oneOfForContentType(contentType, schemaRef, responseOneOf, schemaGenerator);
            } else if (responseAllOf != null && responseAllOf.length > 0) {
                // Use annotationBody for allOf since resolveResponseType returns null when allOf is present
                Class<?> bodyClass = (annotationBody != Void.class) ? annotationBody : null;
                schema = ResponseSchemaFactory.allOfForContentType(contentType, schemaRef, bodyClass, responseAllOf, schemaGenerator);
            } else {
                schema = ResponseSchemaFactory.forContentType(contentType, schemaRef, body, schemaGenerator);
            }

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