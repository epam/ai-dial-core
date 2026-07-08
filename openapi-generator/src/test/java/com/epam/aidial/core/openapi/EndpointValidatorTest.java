package com.epam.aidial.core.openapi;

import com.epam.aidial.core.openapi.annotations.ApiExtension;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.ResponseProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndpointValidatorTest {

    @Test
    void validateAllEndpointsPassValidation() {
        // Collect real endpoints from annotated controllers
        List<EndpointMetadata.Endpoint> endpoints = AnnotationEndpointCollector.collect();

        // Should not throw - all real endpoints should be valid
        assertDoesNotThrow(() -> EndpointValidator.validate(endpoints));
    }

    @Test
    void validateDetectsDuplicateOperationIds() {
        // Create two endpoints with the same operationId but valid paths
        // Use proxy-handled paths that are excluded from route validation
        EndpointMetadata.Endpoint endpoint1 = createTestEndpoint(
                "GET", "/health", "duplicateId"
        );

        EndpointMetadata.Endpoint endpoint2 = createTestEndpoint(
                "GET", "/version", "duplicateId" // Same operationId
        );

        List<EndpointMetadata.Endpoint> endpoints = List.of(endpoint1, endpoint2);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> EndpointValidator.validate(endpoints)
        );

        String message = exception.getMessage();
        assertTrue(message.contains("Duplicate operationId"),
                "Error message should mention duplicate operationId");
        assertTrue(message.contains("duplicateId"),
                "Error message should include the duplicated ID");
        assertTrue(message.contains("GET /health"),
                "Error message should include first endpoint");
        assertTrue(message.contains("GET /version"),
                "Error message should include second endpoint");
    }

    @Test
    void validateDetectsUnmatchedPath() {
        // Create endpoint with path that doesn't match any route
        EndpointMetadata.Endpoint endpoint = createTestEndpoint(
                "GET",
                "/v1/nonexistent/path/that/does/not/match",
                "nonexistentOperation"
        );

        List<EndpointMetadata.Endpoint> endpoints = List.of(endpoint);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> EndpointValidator.validate(endpoints)
        );

        assertTrue(exception.getMessage().contains("do not match any registered route"),
                "Error message should mention route mismatch");
        assertTrue(exception.getMessage().contains("GET /v1/nonexistent/path/that/does/not/match"),
                "Error message should include the unmatched endpoint");
    }

    private EndpointMetadata.Endpoint createTestEndpoint(String method, String path, String operationId) {
        return new EndpointMetadata.Endpoint(
                method,
                path,
                operationId,
                createTestApiSchema(),
                new String[]{"Test"},
                "",
                new ApiParameter[0],
                new ApiResponse[0],
                ResponseProfile.AUTHENTICATED_OPERATION,
                new ApiExtension[0]
        );
    }

    private ApiSchema createTestApiSchema() {
        return new ApiSchema() {
            @Override
            public Class<?> implementation() {
                return Object.class;
            }

            @Override
            public Class<?>[] typeArguments() {
                return new Class[0];
            }

            @Override
            public String schemaRef() {
                return "";
            }

            @Override
            public Class<?>[] oneOf() {
                return new Class[0];
            }

            @Override
            public String[] oneOfSchemaRefs() {
                return new String[0];
            }

            @Override
            public Class<?>[] allOf() {
                return new Class[0];
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

            @Override
            public Class<? extends java.lang.annotation.Annotation> annotationType() {
                return ApiSchema.class;
            }
        };
    }
}
