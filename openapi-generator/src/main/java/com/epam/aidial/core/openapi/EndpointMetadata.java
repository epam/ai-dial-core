package com.epam.aidial.core.openapi;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

public final class EndpointMetadata {

    public record Endpoint(
            String method,
            String path,
            String operationId,
            Type requestBody,
            Type responseBody,
            String[] tags,
            String contentType
    ) {
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
