package com.epam.aidial.core.openapi;

import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class OpenApiParameterBuilder {

    private OpenApiParameterBuilder() {
    }

    public static List<Parameter> buildParameters(EndpointMetadata.Endpoint endpoint) {
        List<Parameter> parameters = new ArrayList<>();
        Set<String> explicitPathParams = new HashSet<>();

        if (endpoint.parameters() != null) {
            for (ApiParameter apiParameter : endpoint.parameters()) {
                parameters.add(toParameter(apiParameter));
                if (apiParameter.in() == ParameterIn.PATH) {
                    explicitPathParams.add(apiParameter.name());
                }
            }
        }

        for (String pathParamName : RouteExtractor.extractPathParams(endpoint.path())) {
            if (!explicitPathParams.contains(pathParamName)) {
                parameters.add(defaultPathParameter(pathParamName));
            }
        }

        return parameters;
    }

    private static Parameter toParameter(ApiParameter apiParameter) {
        Parameter parameter = new Parameter();
        parameter.setName(apiParameter.name());
        parameter.setIn(apiParameter.in().openApiValue());
        parameter.setRequired(apiParameter.required());

        if (!apiParameter.description().isEmpty()) {
            parameter.setDescription(apiParameter.description());
        }

        if (!apiParameter.example().isEmpty()) {
            parameter.setExample(apiParameter.example());
        }

        Schema<?> schema = buildSchema(apiParameter);
        parameter.setSchema(schema);
        return parameter;
    }

    private static Schema<?> buildSchema(ApiParameter apiParameter) {
        Class<?> schemaClass = apiParameter.schema();
        if (isArraySchema(schemaClass)) {
            return buildArraySchema(apiParameter, schemaClass);
        }

        Schema<Object> schema = new Schema<>();
        schema.setType(mapSchemaType(schemaClass));
        applyEnum(schema, apiParameter.allowableValues());
        return schema;
    }

    private static Schema<?> buildArraySchema(ApiParameter apiParameter, Class<?> schemaClass) {
        Schema<Object> schema = new Schema<>();
        schema.setType("array");

        Class<?> itemClass = schemaClass.isArray() ? schemaClass.getComponentType() : String.class;
        Schema<Object> items = new Schema<>();
        items.setType(mapSchemaType(itemClass));
        applyEnum(items, apiParameter.allowableValues());
        schema.setItems(items);
        return schema;
    }

    private static void applyEnum(Schema<Object> schema, String[] allowableValues) {
        if (allowableValues.length == 0) {
            return;
        }
        schema.setEnum(List.of(allowableValues));
    }

    static boolean isArraySchema(Class<?> schemaClass) {
        return schemaClass.isArray() || schemaClass == List.class;
    }

    static boolean isInlinePrimitiveType(Class<?> schemaClass) {
        return schemaClass == boolean.class || schemaClass == Boolean.class
                || schemaClass == int.class || schemaClass == Integer.class
                || schemaClass == long.class || schemaClass == Long.class
                || schemaClass == float.class || schemaClass == Float.class
                || schemaClass == double.class || schemaClass == Double.class
                || schemaClass == String.class
                || schemaClass == byte[].class
                || schemaClass == UUID.class
                || schemaClass == Instant.class
                || schemaClass == LocalDate.class;
    }

    static Schema<Object> inlinePrimitiveSchema(Class<?> schemaClass) {
        Schema<Object> schema = new Schema<>();
        schema.setType(mapSchemaType(schemaClass));

        String format = mapSchemaFormat(schemaClass);
        if (format != null) {
            schema.setFormat(format);
        }

        return schema;
    }

    static String mapSchemaFormat(Class<?> schemaClass) {
        if (schemaClass == long.class || schemaClass == Long.class) {
            return "int64";
        }
        if (schemaClass == int.class || schemaClass == Integer.class) {
            return "int32";
        }
        if (schemaClass == float.class || schemaClass == Float.class) {
            return "float";
        }
        if (schemaClass == double.class || schemaClass == Double.class) {
            return "double";
        }
        if (schemaClass == byte[].class) {
            return "binary";
        }
        if (schemaClass == UUID.class) {
            return "uuid";
        }
        if (schemaClass == Instant.class) {
            return "date-time";
        }
        if (schemaClass == LocalDate.class) {
            return "date";
        }
        return null;
    }

    static String mapSchemaType(Class<?> schemaClass) {
        if (schemaClass == int.class || schemaClass == Integer.class
                || schemaClass == long.class || schemaClass == Long.class) {
            return "integer";
        }
        if (schemaClass == boolean.class || schemaClass == Boolean.class) {
            return "boolean";
        }
        if (schemaClass == double.class || schemaClass == Double.class
                || schemaClass == float.class || schemaClass == Float.class) {
            return "number";
        }
        // String, byte[], UUID, Instant, LocalDate all map to "string"
        return "string";
    }

    private static Parameter defaultPathParameter(String paramName) {
        Parameter param = new Parameter();
        param.setName(paramName);
        param.setIn(ParameterIn.PATH.openApiValue());
        param.setRequired(true);
        Schema<String> paramSchema = new Schema<>();
        paramSchema.setType("string");
        param.setSchema(paramSchema);
        return param;
    }
}