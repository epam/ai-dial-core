package com.epam.aidial.core.openapi;

import com.epam.aidial.core.openapi.annotations.ApiSchema;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;

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

    // ========== Helper Methods for ApiSchema ==========

    /**
     * Helper to create schema for a simple class reference.
     * Java array types are inlined as OpenAPI array schemas rather than component references.
     */
    private static Schema<?> forBody(Class<?> clazz, DtoSchemaGenerator gen) {
        // Inline primitive types
        if (OpenApiParameterBuilder.isInlinePrimitiveType(clazz)) {
            return OpenApiParameterBuilder.inlinePrimitiveSchema(clazz);
        }

        // Inline Java array types
        if (clazz.isArray()) {
            Class<?> componentType = clazz.getComponentType();
            ArraySchema arraySchema = new ArraySchema();
            arraySchema.setItems(forBody(componentType, gen));
            return arraySchema;
        }

        // Component reference for DTOs
        Schema<Object> refSchema = new Schema<>();
        refSchema.set$ref(COMPONENTS_SCHEMAS_PREFIX + gen.resolveTypeName(clazz));
        return refSchema;
    }

    /**
     * Helper to create binary string schema.
     */
    public static Schema<byte[]> binaryStringSchema() {
        Schema<byte[]> schema = new Schema<>();
        schema.setType("string");
        schema.setFormat("binary");
        return schema;
    }

    /**
     * Multipart binary file upload schema.
     */
    public static Schema<?> multipartBinaryFileUploadSchema() {
        Schema<Object> objectSchema = new Schema<>();
        objectSchema.setType("object");
        Map<String, Schema> properties = new LinkedHashMap<>();
        properties.put(MULTIPART_FILE_PROPERTY, binaryStringSchema());
        objectSchema.setProperties(properties);
        objectSchema.setRequired(List.of(MULTIPART_FILE_PROPERTY));
        return objectSchema;
    }

    // ========== ApiSchema Support ==========

    /**
     * Main entry point for ApiSchema resolution.
     * Validates schema and delegates to appropriate strategy.
     */
    public static Schema<?> forSchema(ApiSchema schema, DtoSchemaGenerator gen) {
        if (schema == null || isEmpty(schema)) {
            return null;  // Void / no body
        }

        // Validation first
        validateSchema(schema);

        // Strategy 1: Java DTO (with optional type arguments)
        if (schema.implementation() != Void.class) {
            return forImplementation(schema, gen);
        }

        // Strategy 2: External/project schema reference (top-level only)
        if (!schema.schemaRef().isEmpty() && !hasComposition(schema)) {
            return forSchemaRef(schema.schemaRef());
        }

        // Strategy 3: Composition (oneOf/allOf, schemaRef can be element)
        if (hasComposition(schema)) {
            return forComposition(schema, gen);
        }

        throw new IllegalArgumentException(
            "ApiSchema must specify implementation, schemaRef, or composition"
        );
    }

    /**
     * Validates schema configuration with depth tracking to prevent excessive nesting.
     */
    private static void validateSchema(ApiSchema schema) {
        validateSchema(schema, 0);
    }

    /**
     * Validates schema configuration.
     * Ensures mutually exclusive strategies, valid field combinations, and reasonable nesting depth.
     *
     * @param schema the schema to validate
     * @param depth current nesting depth (0 for top-level)
     * @throws IllegalArgumentException if schema is invalid
     */
    private static void validateSchema(ApiSchema schema, int depth) {
        final int maxDepth = 10;
        if (depth > maxDepth) {
            throw new IllegalArgumentException(
                "ApiSchema nesting depth exceeds maximum of " + maxDepth + ". "
                    + "This may indicate an overly complex schema definition."
            );
        }

        boolean hasImpl = schema.implementation() != Void.class;
        boolean hasRef = !schema.schemaRef().isEmpty();
        boolean hasOneOf = schema.oneOf().length > 0 || schema.oneOfSchemaRefs().length > 0;
        boolean hasAllOf = schema.allOf().length > 0 || schema.allOfSchemaRefs().length > 0;
        boolean hasComp = hasOneOf || hasAllOf;

        // Rule 1: Exactly one primary strategy
        int primaryStrategies = 0;
        if (hasImpl) {
            primaryStrategies++;
        }
        if (hasRef && !hasComp) {
            primaryStrategies++;
        }
        if (hasComp) {
            primaryStrategies++;
        }

        if (primaryStrategies > 1) {
            throw new IllegalArgumentException(
                "ApiSchema has conflicting primary strategies at depth " + depth + ". "
                    + "Exactly one of the following must be specified: "
                    + "implementation, schemaRef (standalone), or composition (oneOf/allOf). "
                    + "Note: schemaRef can appear as element within composition."
            );
        }

        if (primaryStrategies == 0 && depth == 0) {
            return; // Empty top-level schema OK (means Void)
        }

        // Rule 2: implementation-specific validation
        if (hasImpl) {
            if (schema.typeArguments().length > 0) {
                for (Class<?> typeArg : schema.typeArguments()) {
                    if (typeArg == Void.class) {
                        throw new IllegalArgumentException("typeArguments cannot contain Void.class");
                    }
                }
            }
            if (hasRef) {
                throw new IllegalArgumentException("Cannot combine implementation with schemaRef at same level");
            }
            if (hasComp) {
                throw new IllegalArgumentException(
                    "Cannot combine implementation with oneOf/allOf at same level. "
                        + "Use composition with implementation as element instead."
                );
            }
        }

        // Rule 3: typeArguments only valid with implementation
        if (schema.typeArguments().length > 0 && !hasImpl) {
            throw new IllegalArgumentException("typeArguments can only be used with implementation");
        }

        // Rule 4: Composition validation
        if (hasComp) {
            if (hasOneOf && hasAllOf) {
                throw new IllegalArgumentException(
                    "Cannot combine oneOf and allOf at same level. Use nested composition if you need both."
                );
            }

            int compositionSize = schema.oneOf().length + schema.oneOfSchemaRefs().length
                                + schema.allOf().length + schema.allOfSchemaRefs().length;
            if (compositionSize == 0) {
                throw new IllegalArgumentException("Composition (oneOf/allOf) must have at least one element");
            }
        }
    }

    /**
     * Handles Java DTO schemas with optional generic type arguments.
     */
    private static Schema<?> forImplementation(ApiSchema schema, DtoSchemaGenerator gen) {
        Class<?> implClass = schema.implementation();

        if (OpenApiParameterBuilder.isInlinePrimitiveType(implClass)) {
            return OpenApiParameterBuilder.inlinePrimitiveSchema(implClass);
        }

        Type type;
        if (schema.typeArguments().length > 0) {
            type = EndpointMetadata.paramType(implClass, schema.typeArguments());
        } else {
            type = implClass;
        }

        gen.processType(type);
        Schema<Object> refSchema = new Schema<>();
        refSchema.set$ref(COMPONENTS_SCHEMAS_PREFIX + gen.resolveTypeName(type));
        return refSchema;
    }

    /**
     * Handles external/project schema references.
     */
    private static Schema<?> forSchemaRef(String schemaRef) {
        Schema<Object> schema = new Schema<>();
        if (isTrulyExternalRef(schemaRef)) {
            schema.set$ref(schemaRef);
        } else {
            schema.set$ref(COMPONENTS_SCHEMAS_PREFIX + schemaRef);
        }
        return schema;
    }


    /**
     * Handles composition schemas (oneOf, allOf).
     */
    private static Schema<?> forComposition(ApiSchema schema, DtoSchemaGenerator gen) {
        ComposedSchema composedSchema = new ComposedSchema();

        // Add schemaRef elements first
        for (String schemaRef : schema.oneOfSchemaRefs()) {
            composedSchema.addOneOfItem(forSchemaRef(schemaRef));
        }

        // Add class elements
        for (Class<?> clazz : schema.oneOf()) {
            composedSchema.addOneOfItem(forBody(clazz, gen));
        }

        // Add allOf schemaRef elements first
        for (String schemaRef : schema.allOfSchemaRefs()) {
            composedSchema.addAllOfItem(forSchemaRef(schemaRef));
        }

        // Add allOf class elements
        for (Class<?> clazz : schema.allOf()) {
            composedSchema.addAllOfItem(forBody(clazz, gen));
        }

        applyMetadata(composedSchema, schema);
        return composedSchema;
    }

    /**
     * Content-type aware schema resolution.
     */
    public static Schema<?> forContentType(String contentType, ApiSchema schema, DtoSchemaGenerator gen) {
        if (TEXT_EVENT_STREAM.equals(contentType)) {
            Schema<?> baseSchema = forSchema(schema, gen);
            if (baseSchema == null) {
                return new StringSchema();
            }
            // For oneOf/allOf composition, wrap each element in array
            if (baseSchema instanceof ComposedSchema composedSchema) {
                ComposedSchema wrappedComposed = new ComposedSchema();
                if (composedSchema.getOneOf() != null) {
                    for (Schema<?> item : composedSchema.getOneOf()) {
                        ArraySchema arraySchema = new ArraySchema();
                        arraySchema.setItems(item);
                        wrappedComposed.addOneOfItem(arraySchema);
                    }
                }
                if (composedSchema.getAllOf() != null) {
                    for (Schema<?> item : composedSchema.getAllOf()) {
                        ArraySchema arraySchema = new ArraySchema();
                        arraySchema.setItems(item);
                        wrappedComposed.addAllOfItem(arraySchema);
                    }
                }
                applyMetadata(wrappedComposed, schema);
                return wrappedComposed;
            }
            // For schema refs (external schemas), wrap in array
            if (baseSchema.get$ref() != null) {
                ArraySchema arraySchema = new ArraySchema();
                arraySchema.setItems(baseSchema);
                return arraySchema;
            }
            // For primitive/inline schemas (like String), return as-is (no array wrapping)
            return baseSchema;
        }
        return forSchema(schema, gen);
    }

    /**
     * Special case: multipart binary detection.
     */
    public static boolean isMultipartBinaryUpload(ApiSchema schema, String contentType) {
        return MULTIPART_FORM_DATA.equals(contentType)
            && schema.implementation() == byte[].class;
    }

    /**
     * Register schemas for ApiSchema annotation.
     */
    public static void registerSchema(ApiSchema schema, DtoSchemaGenerator gen) {
        if (isEmpty(schema)) {
            return;
        }

        validateSchema(schema);

        if (schema.implementation() != Void.class) {
            Class<?> implClass = schema.implementation();
            if (!OpenApiParameterBuilder.isInlinePrimitiveType(implClass)) {
                Type type;
                if (schema.typeArguments().length > 0) {
                    type = EndpointMetadata.paramType(implClass, schema.typeArguments());
                } else {
                    type = implClass;
                }
                gen.processType(type);
            }
        }

        if (!schema.schemaRef().isEmpty() && !isTrulyExternalRef(schema.schemaRef())) {
            gen.registerExternalSchema(schema.schemaRef());
        }

        // Register oneOf classes
        for (Class<?> clazz : schema.oneOf()) {
            if (!OpenApiParameterBuilder.isInlinePrimitiveType(clazz)) {
                if (clazz.isArray()) {
                    // Register component type, not the array wrapper
                    gen.processType(clazz.getComponentType());
                } else {
                    gen.processType(clazz);
                }
            }
        }

        // Register oneOf schemaRefs
        for (String schemaRef : schema.oneOfSchemaRefs()) {
            if (!isTrulyExternalRef(schemaRef)) {
                gen.registerExternalSchema(schemaRef);
            }
        }

        // Register allOf classes
        for (Class<?> clazz : schema.allOf()) {
            if (!OpenApiParameterBuilder.isInlinePrimitiveType(clazz)) {
                if (clazz.isArray()) {
                    // Register component type, not the array wrapper
                    gen.processType(clazz.getComponentType());
                } else {
                    gen.processType(clazz);
                }
            }
        }

        // Register allOf schemaRefs
        for (String schemaRef : schema.allOfSchemaRefs()) {
            if (!isTrulyExternalRef(schemaRef)) {
                gen.registerExternalSchema(schemaRef);
            }
        }
    }

    private static void applyMetadata(Schema<?> schema, ApiSchema apiSchema) {
        if (!apiSchema.description().isEmpty()) {
            schema.setDescription(apiSchema.description());
        }
        if (apiSchema.nullable()) {
            schema.setNullable(true);
        }
    }

    static boolean isEmpty(ApiSchema schema) {
        return schema.implementation() == Void.class
            && schema.schemaRef().isEmpty()
            && schema.oneOf().length == 0
            && schema.oneOfSchemaRefs().length == 0
            && schema.allOf().length == 0
            && schema.allOfSchemaRefs().length == 0;
    }

    private static boolean hasComposition(ApiSchema schema) {
        return schema.oneOf().length > 0 || schema.oneOfSchemaRefs().length > 0
            || schema.allOf().length > 0 || schema.allOfSchemaRefs().length > 0;
    }
}