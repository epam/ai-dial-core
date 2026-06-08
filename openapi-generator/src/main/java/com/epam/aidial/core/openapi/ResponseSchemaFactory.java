package com.epam.aidial.core.openapi;

import com.epam.aidial.core.server.openapi.schema.OpenApiBinary;
import io.swagger.v3.oas.models.media.Schema;
import org.apache.commons.lang3.StringUtils;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ResponseSchemaFactory {

    private static final String COMPONENTS_SCHEMAS_PREFIX = "#/components/schemas/";
    private static final String TEXT_EVENT_STREAM = "text/event-stream";
    static final String MULTIPART_FORM_DATA = "multipart/form-data";
    static final String MULTIPART_FILE_PROPERTY = "file";

    private ResponseSchemaFactory() {
    }

    static Schema<?> forContentType(String contentType, String schemaRef, Type body, DtoSchemaGenerator schemaGenerator) {
        if (TEXT_EVENT_STREAM.equals(contentType)) {
            return streamArray(schemaRef, body, schemaGenerator);
        }
        return forBody(schemaRef, body, schemaGenerator);
    }

    static Schema<?> forBody(Type body, DtoSchemaGenerator schemaGenerator) {
        return forBody(null, body, schemaGenerator);
    }

    static Schema<?> forBody(String schemaRef, Type body, DtoSchemaGenerator schemaGenerator) {
        if (StringUtils.isNotBlank(schemaRef)) {
            Schema<Object> schema = new Schema<>();
            schema.set$ref(COMPONENTS_SCHEMAS_PREFIX + schemaRef);
            return schema;
        }
        if (!hasBodyType(body)) {
            return null;
        }
        if (body instanceof Class<?> clazz) {
            if (clazz == OpenApiBinary.class) {
                return binaryStringSchema();
            }
            if (OpenApiParameterBuilder.isInlinePrimitiveType(clazz)) {
                return OpenApiParameterBuilder.inlinePrimitiveSchema(clazz);
            }
        }
        Schema<Object> refSchema = new Schema<>();
        refSchema.set$ref(COMPONENTS_SCHEMAS_PREFIX + schemaGenerator.resolveTypeName(body));
        return refSchema;
    }

    static Schema<?> streamArray(String schemaRef, Type body, DtoSchemaGenerator schemaGenerator) {
        if (StringUtils.isNotBlank(schemaRef)) {
            Schema<Object> arraySchema = new Schema<>();
            arraySchema.setType("array");

            Schema<Object> itemRef = new Schema<>();
            itemRef.set$ref(COMPONENTS_SCHEMAS_PREFIX + schemaRef);

            arraySchema.setItems(itemRef);

            return arraySchema;
        }
        if (!(body instanceof Class<?> clazz) || clazz == Void.class) {
            return null;
        }
        if (clazz == OpenApiBinary.class) {
            return forBody(schemaRef, body, schemaGenerator);
        }
        Schema<Object> arraySchema = new Schema<>();
        arraySchema.setType("array");
        if (OpenApiParameterBuilder.isInlinePrimitiveType(clazz)) {
            arraySchema.setItems(OpenApiParameterBuilder.inlinePrimitiveSchema(clazz));
        } else {
            Schema<Object> itemRef = new Schema<>();
            itemRef.set$ref(COMPONENTS_SCHEMAS_PREFIX + schemaGenerator.resolveTypeName(clazz));
            arraySchema.setItems(itemRef);
        }
        return arraySchema;
    }

    static boolean hasBodyType(Type type) {
        return type != null && !(type instanceof Class<?> clazz && clazz == Void.class);
    }

    static Schema<byte[]> binaryStringSchema() {
        Schema<byte[]> schema = new Schema<>();
        schema.setType("string");
        schema.setFormat("binary");
        return schema;
    }

    static boolean isMultipartBinaryUpload(Type body, String contentType) {
        return MULTIPART_FORM_DATA.equals(contentType)
                && body instanceof Class<?> clazz
                && clazz == OpenApiBinary.class;
    }

    static Schema<?> multipartBinaryFileUploadSchema() {
        Schema<Object> objectSchema = new Schema<>();
        objectSchema.setType("object");
        Map<String, Schema> properties = new LinkedHashMap<>();
        properties.put(MULTIPART_FILE_PROPERTY, binaryStringSchema());
        objectSchema.setProperties(properties);
        objectSchema.setRequired(List.of(MULTIPART_FILE_PROPERTY));
        return objectSchema;
    }

    static void registerRequestBody(Type body, String contentType, DtoSchemaGenerator schemaGenerator) {
        if (!hasBodyType(body) || isMultipartBinaryUpload(body, contentType)) {
            return;
        }
        if (body instanceof Class<?> clazz
                && (clazz == OpenApiBinary.class || OpenApiParameterBuilder.isInlinePrimitiveType(clazz))) {
            return;
        }
        schemaGenerator.processType(body);
    }

    static void registerResponseBody(Type body, DtoSchemaGenerator schemaGenerator) {
        if (!hasBodyType(body) || body instanceof Class<?> clazz
                && (clazz == OpenApiBinary.class || OpenApiParameterBuilder.isInlinePrimitiveType(clazz))) {
            return;
        }
        schemaGenerator.processType(body);
    }
}