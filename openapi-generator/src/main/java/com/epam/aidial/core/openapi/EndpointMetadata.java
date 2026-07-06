package com.epam.aidial.core.openapi;

import com.epam.aidial.core.openapi.annotations.ApiExtension;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.ResponseProfile;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Objects;

public final class EndpointMetadata {

    public record Endpoint(
            String method,
            String path,
            String operationId,
            ApiSchema requestBody,
            String[] tags,
            String contentType,
            ApiParameter[] parameters,
            ApiResponse[] responses,
            ResponseProfile responseProfile,
            ApiExtension[] extensions
    ) {

        public Endpoint {
            // Null-checks for mandatory fields
            Objects.requireNonNull(method, "HTTP method cannot be null");
            Objects.requireNonNull(path, "API path cannot be null");
            Objects.requireNonNull(operationId, "Operation ID cannot be null");

            // Defensive copying of arrays to prevent leaking mutable internals
            tags = tags != null ? tags.clone() : new String[0];
            parameters = parameters != null ? parameters.clone() : new ApiParameter[0];
            responses = responses != null ? responses.clone() : new ApiResponse[0];
            extensions = extensions != null ? extensions.clone() : new ApiExtension[0];
        }

        // FIX I4: Override accessors to return cloned arrays, preventing post-creation modification
        @Override
        public String[] tags() {
            return tags.clone();
        }

        @Override
        public ApiParameter[] parameters() {
            return parameters.clone();
        }

        @Override
        public ApiResponse[] responses() {
            return responses.clone();
        }

        @Override
        public ApiExtension[] extensions() {
            return extensions.clone();
        }
    }

    private EndpointMetadata() {
    }

    static Type paramType(Class<?> rawType, Type... typeArgs) {
        return new ParameterizedType() {
            @Override
            public Type[] getActualTypeArguments() {
                return typeArgs.clone();
            }

            @Override
            public Type getRawType() {
                return rawType;
            }

            @Override
            public Type getOwnerType() {
                return null;
            }

            @Override
            public String toString() {
                StringBuilder sb = new StringBuilder();
                sb.append(rawType.getTypeName());
                sb.append('<');
                for (int ii = 0; ii < typeArgs.length; ii++) {
                    if (ii > 0) {
                        sb.append(", ");
                    }
                    sb.append(typeArgs[ii].getTypeName());
                }
                sb.append('>');
                return sb.toString();
            }
        };
    }
}