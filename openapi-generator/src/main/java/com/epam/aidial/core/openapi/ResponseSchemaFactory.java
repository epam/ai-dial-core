package com.epam.aidial.core.openapi;

import com.epam.aidial.core.openapi.schema.OpenApiBinary;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
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

    /**
     * Check if schemaRef is a truly external file path (outside the project resources).
     * Project-local schema files in resources should use schema names instead.
     *
     * @param schemaRef the schema reference string
     * @return true if this is a truly external reference that should be preserved as-is
     */
    private static boolean isTrulyExternalRef(String schemaRef) {
        // Paths starting with these patterns are truly external (outside project)
        // Examples: "../external/Schema.yaml", "/absolute/path/Schema.yaml", "http://..."
        return schemaRef.startsWith("../")
            || schemaRef.startsWith("/")
            || schemaRef.startsWith("http://")
            || schemaRef.startsWith("https://");
    }

    public static Schema<?> forContentType(String contentType, String schemaRef, Type body, DtoSchemaGenerator schemaGenerator) {
        if (TEXT_EVENT_STREAM.equals(contentType)) {
            return StringUtils.isBlank(schemaRef) && (body == null || body == Void.class || body == String.class)
                ? new StringSchema()
                : streamArray(schemaRef, body, schemaGenerator);
        }
        return forBody(schemaRef, body, schemaGenerator);
    }

    public static Schema<?> forBody(Type body, DtoSchemaGenerator schemaGenerator) {
        return forBody(null, body, schemaGenerator);
    }

    public static Schema<?> forBody(String schemaRef, Type body, DtoSchemaGenerator schemaGenerator) {
        if (StringUtils.isNotBlank(schemaRef)) {
            Schema<Object> schema = new Schema<>();
            // Only preserve file path for truly external references
            // Project schemas should use schema names that get loaded as components
            if (isTrulyExternalRef(schemaRef)) {
                schema.set$ref(schemaRef);
            } else {
                schema.set$ref(COMPONENTS_SCHEMAS_PREFIX + schemaRef);
            }
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
            if (clazz.isArray()) {
                ArraySchema schema = new ArraySchema();
                schema.setItems(forBody(null, clazz.getComponentType(), schemaGenerator));
                return schema;
            }
        }
        Schema<Object> refSchema = new Schema<>();
        refSchema.set$ref(COMPONENTS_SCHEMAS_PREFIX + schemaGenerator.resolveTypeName(body));
        return refSchema;
    }

    public static Schema<?> oneOf(Class<?>[] types, DtoSchemaGenerator schemaGenerator) {
        return oneOf(null, types, schemaGenerator);
    }

    /**
     * Creates a oneOf schema combining schemaRef (if present) with the provided types.
     *
     * @param schemaRef schema reference to include first, or null
     * @param types classes to include in oneOf
     * @param schemaGenerator schema generator for type resolution
     * @return ComposedSchema with oneOf items
     */
    public static Schema<?> oneOf(String schemaRef, Class<?>[] types, DtoSchemaGenerator schemaGenerator) {
        ComposedSchema schema = new ComposedSchema();

        // Include schemaRef as first element if specified
        if (StringUtils.isNotBlank(schemaRef)) {
            schema.addOneOfItem(forBody(schemaRef, null, schemaGenerator));
        }

        // Add all types
        for (Class<?> type : types) {
            schema.addOneOfItem(forBody(type, schemaGenerator));
        }

        return schema;
    }

    public static Schema<?> oneOfForContentType(String contentType, Class<?>[] types, DtoSchemaGenerator schemaGenerator) {
        return oneOfForContentType(contentType, null, types, schemaGenerator);
    }

    /**
     * Creates oneOf schema with content-type specific handling (e.g., SSE streams).
     *
     * @param contentType content type (e.g., text/event-stream)
     * @param schemaRef schema reference to include first, or null
     * @param types classes to include in oneOf
     * @param schemaGenerator schema generator for type resolution
     * @return Schema with oneOf composition
     */
    public static Schema<?> oneOfForContentType(String contentType, String schemaRef,
                                                 Class<?>[] types, DtoSchemaGenerator schemaGenerator) {
        if (TEXT_EVENT_STREAM.equals(contentType)) {
            ComposedSchema schema = new ComposedSchema();
            // Include schemaRef as first element if specified
            if (StringUtils.isNotBlank(schemaRef)) {
                schema.addOneOfItem(streamArray(schemaRef, null, schemaGenerator));
            }
            for (Class<?> type : types) {
                schema.addOneOfItem(streamArray(null, type, schemaGenerator));
            }
            return schema;
        }
        return oneOf(schemaRef, types, schemaGenerator);
    }

    /**
     * Creates an allOf schema combining the body type (if not Void) with additional types.
     * The body is included as the first element if present.
     *
     * @param bodyClass body class to include first, or null
     * @param additionalTypes additional classes to combine with allOf
     * @param schemaGenerator schema generator for type resolution
     * @return ComposedSchema with allOf items
     */
    public static Schema<?> allOf(Class<?> bodyClass, Class<?>[] additionalTypes, DtoSchemaGenerator schemaGenerator) {
        return allOf(null, bodyClass, additionalTypes, schemaGenerator);
    }

    /**
     * Creates an allOf schema combining schemaRef and/or body with additional types.
     *
     * @param schemaRef schema reference to include first, or null
     * @param bodyClass body class to include, or null
     * @param additionalTypes additional classes to combine with allOf
     * @param schemaGenerator schema generator for type resolution
     * @return ComposedSchema with allOf items
     */
    public static Schema<?> allOf(String schemaRef, Class<?> bodyClass, Class<?>[] additionalTypes,
                                   DtoSchemaGenerator schemaGenerator) {
        ComposedSchema schema = new ComposedSchema();

        // Include schemaRef as first element if specified
        if (StringUtils.isNotBlank(schemaRef)) {
            schema.addAllOfItem(forBody(schemaRef, null, schemaGenerator));
        }

        // Include body as element if specified
        if (bodyClass != null && bodyClass != Void.class) {
            schema.addAllOfItem(forBody(bodyClass, schemaGenerator));
        }

        // Add all additional types
        for (Class<?> type : additionalTypes) {
            schema.addAllOfItem(forBody(type, schemaGenerator));
        }

        return schema;
    }

    /**
     * Creates allOf schema with content-type specific handling (e.g., SSE streams).
     *
     * @param contentType content type (e.g., text/event-stream)
     * @param bodyClass body class to include first, or null
     * @param additionalTypes additional classes to combine with allOf
     * @param schemaGenerator schema generator for type resolution
     * @return Schema with allOf composition, wrapped in array for SSE
     */
    public static Schema<?> allOfForContentType(String contentType, Class<?> bodyClass,
                                                 Class<?>[] additionalTypes, DtoSchemaGenerator schemaGenerator) {
        return allOfForContentType(contentType, null, bodyClass, additionalTypes, schemaGenerator);
    }

    /**
     * Creates allOf schema with content-type specific handling (e.g., SSE streams).
     *
     * @param contentType content type (e.g., text/event-stream)
     * @param schemaRef schema reference to include first, or null
     * @param bodyClass body class to include, or null
     * @param additionalTypes additional classes to combine with allOf
     * @param schemaGenerator schema generator for type resolution
     * @return Schema with allOf composition, wrapped in array for SSE
     */
    public static Schema<?> allOfForContentType(String contentType, String schemaRef, Class<?> bodyClass,
                                                 Class<?>[] additionalTypes, DtoSchemaGenerator schemaGenerator) {
        if (TEXT_EVENT_STREAM.equals(contentType)) {
            // For SSE, wrap the allOf composition in an array
            ArraySchema arraySchema = new ArraySchema();
            arraySchema.setItems(allOf(schemaRef, bodyClass, additionalTypes, schemaGenerator));
            return arraySchema;
        }
        return allOf(schemaRef, bodyClass, additionalTypes, schemaGenerator);
    }

    public static Schema<?> streamArray(String schemaRef, Type body, DtoSchemaGenerator schemaGenerator) {
        if (StringUtils.isNotBlank(schemaRef)) {
            Schema<Object> arraySchema = new Schema<>();
            arraySchema.setType("array");

            Schema<Object> itemRef = new Schema<>();
            // Only preserve file path for truly external references
            if (isTrulyExternalRef(schemaRef)) {
                itemRef.set$ref(schemaRef);
            } else {
                itemRef.set$ref(COMPONENTS_SCHEMAS_PREFIX + schemaRef);
            }

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

    public static boolean hasBodyType(Type type) {
        return type != null && !(type instanceof Class<?> clazz && clazz == Void.class);
    }

    public static boolean hasRequestOneOf(EndpointMetadata.Endpoint endpoint) {
        return endpoint.requestOneOf() != null
            && endpoint.requestOneOf().length > 0;
    }

    public static Schema<byte[]> binaryStringSchema() {
        Schema<byte[]> schema = new Schema<>();
        schema.setType("string");
        schema.setFormat("binary");
        return schema;
    }

    public static boolean isMultipartBinaryUpload(Type body, String contentType) {
        return MULTIPART_FORM_DATA.equals(contentType)
                && body instanceof Class<?> clazz
                && clazz == OpenApiBinary.class;
    }

    public static Schema<?> multipartBinaryFileUploadSchema() {
        Schema<Object> objectSchema = new Schema<>();
        objectSchema.setType("object");
        Map<String, Schema> properties = new LinkedHashMap<>();
        properties.put(MULTIPART_FILE_PROPERTY, binaryStringSchema());
        objectSchema.setProperties(properties);
        objectSchema.setRequired(List.of(MULTIPART_FILE_PROPERTY));
        return objectSchema;
    }

    public static void registerRequestBody(Type body, String contentType, DtoSchemaGenerator schemaGenerator) {
        if (!hasBodyType(body) || isMultipartBinaryUpload(body, contentType)) {
            return;
        }
        if (body instanceof Class<?> clazz
                && (clazz == OpenApiBinary.class || OpenApiParameterBuilder.isInlinePrimitiveType(clazz))) {
            return;
        }
        schemaGenerator.processType(body);
    }

    public static void registerResponseBody(Type body, DtoSchemaGenerator schemaGenerator) {
        if (!hasBodyType(body) || body instanceof Class<?> clazz
                && (clazz == OpenApiBinary.class || OpenApiParameterBuilder.isInlinePrimitiveType(clazz))) {
            return;
        }
        schemaGenerator.processType(body);
    }
}