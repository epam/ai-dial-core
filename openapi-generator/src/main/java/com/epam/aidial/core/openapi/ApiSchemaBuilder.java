package com.epam.aidial.core.openapi;

import com.epam.aidial.core.openapi.annotations.ApiSchema;

import java.lang.annotation.Annotation;
import java.util.Objects;

/**
 * Utility to programmatically create ApiSchema annotation instances.
 */
public final class ApiSchemaBuilder {

    private ApiSchemaBuilder() {
    }

    public static ApiSchema forImplementation(Class<?> implementation) {
        return new ApiSchema() {
            @Override
            public Class<? extends Annotation> annotationType() {
                return ApiSchema.class;
            }

            @Override
            public Class<?> implementation() {
                return implementation;
            }

            @Override
            public Class<?>[] typeArguments() {
                return new Class<?>[0];
            }

            @Override
            public String schemaRef() {
                return "";
            }

            @Override
            public Class<?>[] oneOf() {
                return new Class<?>[0];
            }

            @Override
            public String[] oneOfSchemaRefs() {
                return new String[0];
            }

            @Override
            public Class<?>[] allOf() {
                return new Class<?>[0];
            }

            @Override
            public String[] allOfSchemaRefs() {
                return new String[0];
            }

            @Override
            public String description() {
                return "";
            }

            @Override
            public boolean nullable() {
                return false;
            }
        };
    }

    public static ApiSchema forSchemaRef(String schemaRef) {
        // Enforce strategy group invariant and validate incoming parameters
        Objects.requireNonNull(schemaRef, "Schema reference cannot be null");
        if (schemaRef.isBlank()) {
            throw new IllegalArgumentException("Invalid @ApiSchema strategy: 'schemaRef' cannot be empty or blank");
        }

        return new ApiSchema() {
            @Override
            public Class<? extends Annotation> annotationType() {
                return ApiSchema.class;
            }

            @Override
            public Class<?> implementation() {
                return Void.class;
            }

            @Override
            public Class<?>[] typeArguments() {
                return new Class<?>[0];
            }

            @Override
            public String schemaRef() {
                return schemaRef;
            }

            @Override
            public Class<?>[] oneOf() {
                return new Class<?>[0];
            }

            @Override
            public String[] oneOfSchemaRefs() {
                return new String[0];
            }

            @Override
            public Class<?>[] allOf() {
                return new Class<?>[0];
            }

            @Override
            public String[] allOfSchemaRefs() {
                return new String[0];
            }

            @Override
            public String description() {
                return "";
            }

            @Override
            public boolean nullable() {
                return false;
            }
        };
    }
}