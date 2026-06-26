package com.epam.aidial.core.openapi;

import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.openapi.annotations.ResponseProfile;
import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiParameterBuilderTest {

    @Test
    void buildParametersFromAnnotations() throws Exception {
        ApiParameter[] apiParameters = parametersFromMethod("chatCompletion");
        EndpointMetadata.Endpoint endpoint = endpointWithParameters(
                "/openai/deployments/{deployment_name}/chat/completions",
                apiParameters
        );

        List<Parameter> parameters = OpenApiParameterBuilder.buildParameters(endpoint);

        assertEquals(3, parameters.size());

        Parameter pathParam = parameters.get(0);
        assertEquals("deployment_name", pathParam.getName());
        assertEquals("path", pathParam.getIn());
        assertTrue(pathParam.getRequired());
        assertEquals("The name of the deployment.", pathParam.getDescription());
        assertEquals("string", pathParam.getSchema().getType());

        Parameter queryParam = parameters.get(1);
        assertEquals("api-version", queryParam.getName());
        assertEquals("query", queryParam.getIn());
        assertTrue(queryParam.getRequired());
        assertEquals("The API version to use for this request.", queryParam.getDescription());
        assertEquals("2024-10-21", queryParam.getExample());
        assertEquals("string", queryParam.getSchema().getType());

        Parameter headerParam = parameters.get(2);
        assertEquals("X-DIAL-CACHE-POLICY", headerParam.getName());
        assertEquals("header", headerParam.getIn());
        assertFalse(headerParam.getRequired());
        assertEquals("Cache selection policy.", headerParam.getDescription());
        assertNull(headerParam.getExample());
        assertEquals("string", headerParam.getSchema().getType());
        assertEquals(List.of("availability-priority", "cache-priority"), headerParam.getSchema().getEnum());
    }

    @Test
    void fallsBackToRoutePathParamsWhenNoExplicitPathAnnotation() {
        EndpointMetadata.Endpoint endpoint = endpointWithParameters(
                "/v1/consent/{deployment_id}",
                new ApiParameter[0]
        );

        List<Parameter> parameters = OpenApiParameterBuilder.buildParameters(endpoint);

        assertEquals(1, parameters.size());
        Parameter pathParam = parameters.get(0);
        assertEquals("deployment_id", pathParam.getName());
        assertEquals("path", pathParam.getIn());
        assertTrue(pathParam.getRequired());
        assertEquals("string", pathParam.getSchema().getType());
    }

    @Test
    void explicitPathParamReplacesRouteFallback() throws Exception {
        ApiParameter[] apiParameters = parametersFromMethod("withExplicitPathParam");
        EndpointMetadata.Endpoint endpoint = endpointWithParameters(
                "/v1/consent/{deployment_id}",
                apiParameters
        );

        List<Parameter> parameters = OpenApiParameterBuilder.buildParameters(endpoint);

        assertEquals(1, parameters.size());
        assertEquals("deployment_id", parameters.get(0).getName());
        assertEquals("Custom deployment id", parameters.get(0).getDescription());
    }

    @Test
    void buildArrayQueryParameterWithEnumItems() throws Exception {
        ApiParameter[] apiParameters = parametersFromMethod("listWithInterfaceType");
        EndpointMetadata.Endpoint endpoint = endpointWithParameters("/v1/deployments", apiParameters);

        List<Parameter> parameters = OpenApiParameterBuilder.buildParameters(endpoint);

        assertEquals(1, parameters.size());
        Parameter queryParam = parameters.get(0);
        assertEquals("interface_type", queryParam.getName());
        assertEquals("query", queryParam.getIn());
        assertFalse(queryParam.getRequired());
        assertEquals("Filter by interface type.", queryParam.getDescription());

        Schema<?> schema = queryParam.getSchema();
        assertEquals("array", schema.getType());
        assertNull(schema.getEnum());

        Schema<?> items = schema.getItems();
        assertEquals("string", items.getType());
        assertEquals(List.of("chat", "embeddings", "mcp", "custom_ui", "all"), items.getEnum());
    }

    @Test
    void arrayQueryParameterSerializesToExpectedYamlShape() throws Exception {
        ApiParameter[] apiParameters = parametersFromMethod("listWithInterfaceType");
        EndpointMetadata.Endpoint endpoint = endpointWithParameters("/v1/deployments", apiParameters);

        Operation operation = new Operation();
        operation.setParameters(OpenApiParameterBuilder.buildParameters(endpoint));
        String yaml = Yaml.pretty(operation);

        assertTrue(yaml.contains("interface_type"));
        assertTrue(yaml.contains("type: array"));
        assertTrue(yaml.contains("items:"));
        assertTrue(yaml.contains("embeddings"));
        assertTrue(yaml.contains("custom_ui"));
    }

    @Test
    void isArraySchemaDetectsListAndArrayTypes() {
        assertTrue(OpenApiParameterBuilder.isArraySchema(List.class));
        assertTrue(OpenApiParameterBuilder.isArraySchema(String[].class));
        assertFalse(OpenApiParameterBuilder.isArraySchema(String.class));
        assertFalse(OpenApiParameterBuilder.isArraySchema(Integer.class));
    }

    @Test
    void mapSchemaTypeSupportsPrimitiveAndWrapperTypes() {
        assertEquals("string", OpenApiParameterBuilder.mapSchemaType(String.class));
        assertEquals("integer", OpenApiParameterBuilder.mapSchemaType(int.class));
        assertEquals("integer", OpenApiParameterBuilder.mapSchemaType(Integer.class));
        assertEquals("integer", OpenApiParameterBuilder.mapSchemaType(long.class));
        assertEquals("integer", OpenApiParameterBuilder.mapSchemaType(Long.class));
        assertEquals("boolean", OpenApiParameterBuilder.mapSchemaType(boolean.class));
        assertEquals("boolean", OpenApiParameterBuilder.mapSchemaType(Boolean.class));
        assertEquals("number", OpenApiParameterBuilder.mapSchemaType(double.class));
        assertEquals("number", OpenApiParameterBuilder.mapSchemaType(Double.class));
        assertEquals("number", OpenApiParameterBuilder.mapSchemaType(float.class));
        assertEquals("number", OpenApiParameterBuilder.mapSchemaType(Float.class));
    }

    @Test
    void inlinePrimitiveSchemaUsesOpenApiType() {
        assertEquals("boolean", OpenApiParameterBuilder.inlinePrimitiveSchema(Boolean.class).getType());
        assertNull(OpenApiParameterBuilder.inlinePrimitiveSchema(Boolean.class).getFormat());
        assertEquals("string", OpenApiParameterBuilder.inlinePrimitiveSchema(String.class).getType());
        assertEquals("integer", OpenApiParameterBuilder.inlinePrimitiveSchema(Integer.class).getType());
        assertEquals("integer", OpenApiParameterBuilder.inlinePrimitiveSchema(Long.class).getType());
        assertEquals("number", OpenApiParameterBuilder.inlinePrimitiveSchema(Float.class).getType());
        assertEquals("number", OpenApiParameterBuilder.inlinePrimitiveSchema(Double.class).getType());
    }

    @Test
    void parametersSerializeToExpectedYamlShape() throws Exception {
        ApiParameter[] apiParameters = parametersFromMethod("chatCompletion");
        EndpointMetadata.Endpoint endpoint = endpointWithParameters(
                "/openai/deployments/{deployment_name}/chat/completions",
                apiParameters
        );

        Operation operation = new Operation();
        operation.setParameters(OpenApiParameterBuilder.buildParameters(endpoint));
        String yaml = Yaml.pretty(operation);

        assertTrue(yaml.contains("deployment_name"));
        assertTrue(yaml.contains("api-version"));
        assertTrue(yaml.contains("X-DIAL-CACHE-POLICY"));
        assertTrue(yaml.contains("availability-priority"));
        assertTrue(yaml.contains("2024-10-21"));
    }

    private static ApiParameter[] parametersFromMethod(String methodName) throws Exception {
        Method method = AnnotatedMethods.class.getDeclaredMethod(methodName);
        return method.getAnnotationsByType(ApiParameter.class);
    }

    private static EndpointMetadata.Endpoint endpointWithParameters(String path, ApiParameter[] parameters) {
        return new EndpointMetadata.Endpoint(
                "POST",
                path,
                "testOperation",
                null,  // requestBody (ApiSchema)
                new String[0],  // tags
                "application/json",  // contentType
                parameters,
                new com.epam.aidial.core.openapi.annotations.ApiResponse[0],
                ResponseProfile.NONE
        );
    }

    private static class AnnotatedMethods {

        @ApiParameter(
                name = "deployment_name",
                in = ParameterIn.PATH,
                required = true,
                description = "The name of the deployment."
        )
        @ApiParameter(
                name = "api-version",
                in = ParameterIn.QUERY,
                required = true,
                description = "The API version to use for this request.",
                example = "2024-10-21"
        )
        @ApiParameter(
                name = "X-DIAL-CACHE-POLICY",
                in = ParameterIn.HEADER,
                description = "Cache selection policy.",
                allowableValues = {
                        "availability-priority",
                        "cache-priority"
                }
        )
        void chatCompletion() {
        }

        @ApiParameter(
                name = "deployment_id",
                in = ParameterIn.PATH,
                required = true,
                description = "Custom deployment id"
        )
        void withExplicitPathParam() {
        }

        @ApiParameter(
                name = "interface_type",
                in = ParameterIn.QUERY,
                schema = List.class,
                description = "Filter by interface type.",
                allowableValues = {"chat", "embeddings", "mcp", "custom_ui", "all"}
        )
        void listWithInterfaceType() {
        }
    }
}