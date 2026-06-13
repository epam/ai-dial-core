package com.epam.aidial.core.openapi;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.openapi.annotations.ApiHeader;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ResponseProfile;
import com.epam.aidial.core.openapi.schema.OpenApiBinary;
import com.epam.aidial.core.server.data.ErrorData;
import com.epam.aidial.core.server.data.ResourceLink;
import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiResponseBuilderTest {

    @Test
    void buildResponsesFromAnnotations() throws Exception {
        ApiResponse[] responses = responsesFromMethod("annotatedResponses");
        EndpointMetadata.Endpoint endpoint = endpointWithResponses(responses);
        DtoSchemaGenerator schemaGenerator = new DtoSchemaGenerator();
        OpenApiResponseBuilder.registerResponseSchemas(endpoint, schemaGenerator);

        ApiResponses apiResponses = OpenApiResponseBuilder.buildResponses(endpoint, schemaGenerator);

        assertEquals(2, apiResponses.size());
        assertTrue(apiResponses.containsKey("404"));
        assertTrue(apiResponses.containsKey("200"));

        var notFound = apiResponses.get("404");
        assertEquals("Deployment not found", notFound.getDescription());
        assertNotNull(notFound.getContent().get("application/json"));
        assertEquals("#/components/schemas/ErrorData",
                notFound.getContent().get("application/json").getSchema().get$ref());

        var success = apiResponses.get("200");
        assertEquals("Streaming response", success.getDescription());
        assertNotNull(success.getContent().get("application/json"));
        assertNotNull(success.getContent().get("text/event-stream"));
        assertEquals("#/components/schemas/CreateChatCompletionResponse",
                success.getContent().get("application/json").getSchema().get$ref());

        var streamSchema = success.getContent().get("text/event-stream").getSchema();
        assertEquals("array", streamSchema.getType());
        assertNull(streamSchema.get$ref(), "Stream schema must not combine $ref with type/items");
        assertEquals("#/components/schemas/CreateChatCompletionStreamResponse",
                streamSchema.getItems().get$ref());
    }

    @Test
    void llmProxyPresetUsesStreamArraySchemaForEventStream() throws Exception {
        ApiResponse[] responses = responsesFromMethod("llmProxyResponses");
        EndpointMetadata.Endpoint endpoint = new EndpointMetadata.Endpoint(
                "POST",
                "/openai/deployments/{deployment_name}/chat/completions",
                "createChatCompletion",
                null,
                null,
                null,
                new String[]{"LLM"},
                "application/json",
                new ApiParameter[0],
                responses,
                ResponseProfile.LLM_PROXY
        );
        DtoSchemaGenerator schemaGenerator = new DtoSchemaGenerator();
        OpenApiResponseBuilder.registerResponseSchemas(endpoint, schemaGenerator);

        ApiResponses apiResponses = OpenApiResponseBuilder.buildResponses(endpoint, schemaGenerator);
        var streamSchema = apiResponses.get("200").getContent().get("text/event-stream").getSchema();

        assertEquals("array", streamSchema.getType());
        assertNull(streamSchema.get$ref());
        assertEquals("#/components/schemas/CreateChatCompletionStreamResponse",
                streamSchema.getItems().get$ref());
        assertEquals("#/components/schemas/CreateChatCompletionResponse",
                apiResponses.get("200").getContent().get("application/json").getSchema().get$ref());
    }

    @Test
    void noContentResponseOmitsContentSection() throws Exception {
        ApiResponse[] responses = responsesFromMethod("noContentResponse");
        EndpointMetadata.Endpoint endpoint = endpointWithResponses(responses);
        DtoSchemaGenerator schemaGenerator = new DtoSchemaGenerator();

        ApiResponses apiResponses = OpenApiResponseBuilder.buildResponses(endpoint, schemaGenerator);

        var deleted = apiResponses.get("204");
        assertEquals("Deleted successfully", deleted.getDescription());
        assertNull(deleted.getContent());
    }

    @Test
    void binaryResponseUsesStringBinarySchema() throws Exception {
        ApiResponse[] responses = responsesFromMethod("binaryResponse");
        EndpointMetadata.Endpoint endpoint = endpointWithResponses(responses);
        DtoSchemaGenerator schemaGenerator = new DtoSchemaGenerator();

        ApiResponses apiResponses = OpenApiResponseBuilder.buildResponses(endpoint, schemaGenerator);

        var success = apiResponses.get("200");
        var mediaType = success.getContent().get("application/octet-stream");
        assertEquals("string", mediaType.getSchema().getType());
        assertEquals("binary", mediaType.getSchema().getFormat());
    }

    @Test
    void responsesAreSortedNumerically() throws Exception {
        ApiResponse[] responses = responsesFromMethod("unorderedResponses");
        EndpointMetadata.Endpoint endpoint = endpointWithResponses(responses);
        DtoSchemaGenerator schemaGenerator = new DtoSchemaGenerator();
        OpenApiResponseBuilder.registerResponseSchemas(endpoint, schemaGenerator);

        ApiResponses apiResponses = OpenApiResponseBuilder.buildResponses(endpoint, schemaGenerator);

        assertEquals(List.of("200", "404", "500"), List.copyOf(apiResponses.keySet()));
    }

    @Test
    void responseWithOneOfCreatesComposedSchema() throws Exception {
        ApiResponse[] responses = responsesFromMethod("oneOfResponse");
        EndpointMetadata.Endpoint endpoint = endpointWithResponses(responses);
        DtoSchemaGenerator schemaGenerator = new DtoSchemaGenerator();
        OpenApiResponseBuilder.registerResponseSchemas(endpoint, schemaGenerator);

        ApiResponses apiResponses = OpenApiResponseBuilder.buildResponses(endpoint, schemaGenerator);

        var success = apiResponses.get("200");
        assertEquals("Multiple response types", success.getDescription());
        var jsonSchema = success.getContent().get("application/json").getSchema();

        assertNotNull(jsonSchema.getOneOf(), "Schema should have oneOf");
        assertEquals(2, jsonSchema.getOneOf().size(), "Should have two oneOf items");

        assertEquals("#/components/schemas/ErrorData",
                ((Schema<?>) jsonSchema.getOneOf().get(0)).get$ref());
        assertEquals("#/components/schemas/ResourceLink",
                ((Schema<?>) jsonSchema.getOneOf().get(1)).get$ref());

        assertTrue(schemaGenerator.getSchemas().containsKey("ErrorData"));
        assertTrue(schemaGenerator.getSchemas().containsKey("ResourceLink"));
    }

    @Test
    void responseWithOneOfForEventStreamCreatesArrayComposedSchema() throws Exception {
        ApiResponse[] responses = responsesFromMethod("oneOfStreamResponse");
        EndpointMetadata.Endpoint endpoint = endpointWithResponses(responses);
        DtoSchemaGenerator schemaGenerator = new DtoSchemaGenerator();
        OpenApiResponseBuilder.registerResponseSchemas(endpoint, schemaGenerator);

        ApiResponses apiResponses = OpenApiResponseBuilder.buildResponses(endpoint, schemaGenerator);

        var success = apiResponses.get("200");
        var streamSchema = success.getContent().get("text/event-stream").getSchema();

        assertNotNull(streamSchema.getOneOf(), "Stream schema should have oneOf");
        assertEquals(2, streamSchema.getOneOf().size());

        Schema<?> firstItem = (Schema<?>) streamSchema.getOneOf().get(0);
        assertEquals("array", firstItem.getType());
        assertNotNull(firstItem.getItems());

        Schema<?> secondItem = (Schema<?>) streamSchema.getOneOf().get(1);
        assertEquals("array", secondItem.getType());
        assertNotNull(secondItem.getItems());
    }

    @Test
    void responseOneOfAndBodyAreMutuallyExclusive() throws Exception {
        ApiResponse[] responses = responsesFromMethod("invalidBothBodyAndOneOf");
        EndpointMetadata.Endpoint endpoint = endpointWithResponses(responses);
        DtoSchemaGenerator schemaGenerator = new DtoSchemaGenerator();

        ApiResponses apiResponses = OpenApiResponseBuilder.buildResponses(endpoint, schemaGenerator);
        var success = apiResponses.get("200");
        assertNotNull(success.getContent().get("application/json").getSchema().getOneOf(),
                "When both body and responseOneOf are specified, responseOneOf should take precedence");
    }

    @Test
    void explicitResponsesAddedToResponseProfile() throws Exception {
        ApiResponse[] responses = responsesFromMethod("annotatedResponses");
        EndpointMetadata.Endpoint endpoint = new EndpointMetadata.Endpoint(
                "POST",
                "/openai/deployments/{deployment_name}/chat/completions",
                "createChatCompletion",
                null,
                null,
                null,
                new String[]{"LLM"},
                "application/json",
                new ApiParameter[0],
                responses,
                ResponseProfile.LLM_PROXY
        );

        ApiResponses apiResponses = OpenApiResponseBuilder.buildResponses(endpoint, new DtoSchemaGenerator());
        assertEquals(7, apiResponses.size());
    }

    @Test
    void singleExplicitResponseReplacesFallback() throws Exception {
        ApiResponse[] responses = responsesFromMethod("singleResponse");
        EndpointMetadata.Endpoint endpoint = endpointWithResponses(responses);
        ApiResponses apiResponses = OpenApiResponseBuilder.buildResponses(endpoint, new DtoSchemaGenerator());

        assertEquals(1, apiResponses.size());
        assertTrue(apiResponses.containsKey("204"));
        assertFalse(apiResponses.containsKey("200"));
        assertFalse(apiResponses.containsKey("401"));
        assertFalse(apiResponses.containsKey("403"));
        assertFalse(apiResponses.containsKey("500"));
    }

    @Test
    void multipleExplicitResponsesReplaceFallback() throws Exception {
        ApiResponse[] responses = responsesFromMethod("multipleResponses");
        EndpointMetadata.Endpoint endpoint = endpointWithResponses(responses);
        ApiResponses apiResponses = OpenApiResponseBuilder.buildResponses(endpoint, new DtoSchemaGenerator());

        assertEquals(List.of("200", "401", "404", "429", "500", "502", "503"), List.copyOf(apiResponses.keySet()));
        assertFalse(apiResponses.containsKey("403"));
    }

    @Test
    void standardResponseSetExpandsMissingResponseCodes() {
        EndpointMetadata.Endpoint endpoint = new EndpointMetadata.Endpoint(
                "POST",
                "/openai/deployments/{deployment_name}/completions",
                "createCompletion",
                null,
                null,
                null,
                new String[]{"LLM"},
                "application/json",
                new ApiParameter[0],
                new ApiResponse[0],
                ResponseProfile.LLM_PROXY
        );
        DtoSchemaGenerator schemaGenerator = new DtoSchemaGenerator();
        OpenApiResponseBuilder.registerResponseSchemas(endpoint, schemaGenerator);

        ApiResponses apiResponses = OpenApiResponseBuilder.buildResponses(endpoint, schemaGenerator);

        assertTrue(apiResponses.containsKey("404"));
        assertFalse(apiResponses.containsKey("412"));
        assertTrue(apiResponses.containsKey("429"));
        assertTrue(apiResponses.containsKey("502"));
        assertTrue(apiResponses.containsKey("503"));
        assertFalse(apiResponses.containsKey("403"));
    }

    @Test
    void schemaReadPresetIncludes401() {
        EndpointMetadata.Endpoint endpoint = new EndpointMetadata.Endpoint(
                "GET",
                "/v1/application_type_schemas/schema",
                "getCustomApplicationSchema",
                null,
                null,
                null,
                new String[]{"Applications"},
                "application/json",
                new ApiParameter[0],
                new ApiResponse[0],
                ResponseProfile.AUTHENTICATED_OPERATION
        );

        ApiResponses apiResponses = OpenApiResponseBuilder.buildResponses(endpoint, new DtoSchemaGenerator());

        assertTrue(apiResponses.containsKey("400"));
        assertTrue(apiResponses.containsKey("401"));
        assertTrue(apiResponses.containsKey("500"));
    }

    @Test
    void applicationOpsPresetIncludesAllRuntimeErrorCodes() throws Exception {
        ApiResponse[] responses = responsesFromMethod("applicationLogsResponses");
        EndpointMetadata.Endpoint endpoint = new EndpointMetadata.Endpoint(
                "POST",
                "/v1/ops/application/deploy",
                "deployApplication",
                ResourceLink.class,
                null,
                null,
                new String[]{"Applications"},
                "application/json",
                new ApiParameter[0],
                responses,
                ResponseProfile.APPLICATION_OPS
        );
        DtoSchemaGenerator schemaGenerator = new DtoSchemaGenerator();
        OpenApiResponseBuilder.registerResponseSchemas(endpoint, schemaGenerator);

        ApiResponses apiResponses = OpenApiResponseBuilder.buildResponses(endpoint, schemaGenerator);

        assertEquals(List.of("200", "400", "401", "403", "404", "409", "500"), List.copyOf(apiResponses.keySet()));
        assertEquals("#/components/schemas/ApplicationLogs",
                apiResponses.get("200").getContent().get("application/json").getSchema().get$ref());
    }

    @Test
    void applicationLogsPresetIncludesLogsResponseBody() throws Exception {
        ApiResponse[] responses = responsesFromMethod("applicationLogsResponses");
        EndpointMetadata.Endpoint endpoint = new EndpointMetadata.Endpoint(
                "POST",
                "/v1/ops/application/logs",
                "getApplicationLogs",
                ResourceLink.class,
                null,
                null,
                new String[]{"Applications"},
                "application/json",
                new ApiParameter[0],
                responses,
                ResponseProfile.APPLICATION_OPS
        );
        DtoSchemaGenerator schemaGenerator = new DtoSchemaGenerator();
        OpenApiResponseBuilder.registerResponseSchemas(endpoint, schemaGenerator);

        ApiResponses apiResponses = OpenApiResponseBuilder.buildResponses(endpoint, schemaGenerator);

        assertEquals("#/components/schemas/ApplicationLogs",
                apiResponses.get("200").getContent().get("application/json").getSchema().get$ref());
    }

    @Test
    void primitiveResponseBodyUsesInlineSchemaWithoutComponent() throws Exception {
        ApiResponse[] responses = responsesFromMethod("primitiveBooleanResponses");
        EndpointMetadata.Endpoint endpoint = new EndpointMetadata.Endpoint(
                "POST",
                "/v1/ops/toolset/signin",
                "toolsetSignin",
                null,
                null,
                null,
                new String[]{"Toolsets"},
                "application/json",
                new ApiParameter[0],
                responses,
                ResponseProfile.AUTHORIZED_OPERATION
        );
        DtoSchemaGenerator schemaGenerator = new DtoSchemaGenerator();
        OpenApiResponseBuilder.registerResponseSchemas(endpoint, schemaGenerator);

        ApiResponses apiResponses = OpenApiResponseBuilder.buildResponses(endpoint, schemaGenerator);
        Schema<?> schema = apiResponses.get("200").getContent().get("application/json").getSchema();

        assertEquals("boolean", schema.getType());
        assertNull(schema.get$ref());
        assertFalse(schemaGenerator.getSchemas().containsKey("Boolean"));
    }

    @Test
    void conditionalWritePresetIncludes412() {
        EndpointMetadata.Endpoint endpoint = new EndpointMetadata.Endpoint(
                "PUT",
                "/v1/files/{bucket}/{file_path}",
                "uploadFile",
                null,
                null,
                null,
                new String[]{"Files"},
                "application/json",
                new ApiParameter[0],
                new ApiResponse[0],
                ResponseProfile.CONDITIONAL_WRITE
        );

        ApiResponses apiResponses = OpenApiResponseBuilder.buildResponses(endpoint, new DtoSchemaGenerator());

        assertTrue(apiResponses.containsKey("412"));
        assertTrue(apiResponses.containsKey("401"));
    }

    @Test
    void responsesSerializeToYaml() throws Exception {
        ApiResponse[] responses = responsesFromMethod("annotatedResponses");
        EndpointMetadata.Endpoint endpoint = endpointWithResponses(responses);
        DtoSchemaGenerator schemaGenerator = new DtoSchemaGenerator();
        OpenApiResponseBuilder.registerResponseSchemas(endpoint, schemaGenerator);

        Operation operation = new Operation();
        operation.setResponses(OpenApiResponseBuilder.buildResponses(endpoint, schemaGenerator));
        String yaml = Yaml.pretty(operation);

        assertTrue(yaml.contains("text/event-stream"));
        assertTrue(yaml.contains("Deployment not found"));
        assertTrue(yaml.contains("CreateChatCompletionResponse"));
    }

    @Test
    void responseWithSingleHeader() throws Exception {
        ApiResponse[] responses = responsesFromMethod("responseWithSingleHeader");
        EndpointMetadata.Endpoint endpoint = endpointWithResponses(responses);
        DtoSchemaGenerator schemaGenerator = new DtoSchemaGenerator();

        ApiResponses apiResponses = OpenApiResponseBuilder.buildResponses(endpoint, schemaGenerator);

        io.swagger.v3.oas.models.responses.ApiResponse response = apiResponses.get("200");
        assertNotNull(response.getHeaders());
        assertEquals(1, response.getHeaders().size());

        Header header = response.getHeaders().get("ETag");
        assertNotNull(header);
        assertEquals("Entity tag", header.getDescription());
        assertTrue(header.getRequired());
        assertEquals("string", header.getSchema().getType());
    }

    @Test
    void responseWithMultipleHeaders() throws Exception {
        ApiResponse[] responses = responsesFromMethod("responseWithMultipleHeaders");
        EndpointMetadata.Endpoint endpoint = endpointWithResponses(responses);
        DtoSchemaGenerator schemaGenerator = new DtoSchemaGenerator();

        ApiResponses apiResponses = OpenApiResponseBuilder.buildResponses(endpoint, schemaGenerator);

        io.swagger.v3.oas.models.responses.ApiResponse response = apiResponses.get("200");
        assertEquals(3, response.getHeaders().size());

        assertEquals("string", response.getHeaders().get("ETag").getSchema().getType());
        assertEquals("integer", response.getHeaders().get("X-Rate-Limit-Remaining").getSchema().getType());
        assertEquals("boolean", response.getHeaders().get("X-Has-More").getSchema().getType());
    }

    @Test
    void responseWithoutHeadersHasNullHeaders() throws Exception {
        ApiResponse[] responses = responsesFromMethod("simpleResponse");
        EndpointMetadata.Endpoint endpoint = endpointWithResponses(responses);
        DtoSchemaGenerator schemaGenerator = new DtoSchemaGenerator();

        ApiResponses apiResponses = OpenApiResponseBuilder.buildResponses(endpoint, schemaGenerator);

        // Backward compatibility: responses without headers have null headers
        io.swagger.v3.oas.models.responses.ApiResponse response = apiResponses.get("200");
        assertNull(response.getHeaders());
    }

    @Test
    void headersSerializeToYaml() throws Exception {
        ApiResponse[] responses = responsesFromMethod("responseWithSingleHeader");
        EndpointMetadata.Endpoint endpoint = endpointWithResponses(responses);
        DtoSchemaGenerator schemaGenerator = new DtoSchemaGenerator();

        Operation operation = new Operation();
        operation.setResponses(OpenApiResponseBuilder.buildResponses(endpoint, schemaGenerator));
        String yaml = Yaml.pretty(operation);

        assertTrue(yaml.contains("ETag:"));
        assertTrue(yaml.contains("description: Entity tag"));
        assertTrue(yaml.contains("required: true"));
        assertTrue(yaml.contains("type: string"));
    }

    @Test
    void trulyExternalSchemaRefInResponseIsPreservedAsIs() throws Exception {
        ApiResponse[] responses = responsesFromMethod("trulyExternalSchemaResponse");
        EndpointMetadata.Endpoint endpoint = endpointWithResponses(responses);
        DtoSchemaGenerator schemaGenerator = new DtoSchemaGenerator();
        OpenApiResponseBuilder.registerResponseSchemas(endpoint, schemaGenerator);

        ApiResponses apiResponses = OpenApiResponseBuilder.buildResponses(endpoint, schemaGenerator);

        var success = apiResponses.get("200");
        var schema = success.getContent().get("application/json").getSchema();
        assertEquals("../external/ExternalResponse.yaml", schema.get$ref());
        assertFalse(schemaGenerator.getSchemas().containsKey("../external/ExternalResponse.yaml"),
                "Truly external schema ref should not be registered in components");
    }

    @Test
    void schemaNameInResponseIsConvertedToComponentRef() throws Exception {
        ApiResponse[] responses = responsesFromMethod("schemaNameResponse");
        EndpointMetadata.Endpoint endpoint = endpointWithResponses(responses);
        DtoSchemaGenerator schemaGenerator = new DtoSchemaGenerator();
        OpenApiResponseBuilder.registerResponseSchemas(endpoint, schemaGenerator);

        ApiResponses apiResponses = OpenApiResponseBuilder.buildResponses(endpoint, schemaGenerator);

        var success = apiResponses.get("200");
        var schema = success.getContent().get("application/json").getSchema();
        assertEquals("#/components/schemas/MyResponseSchema", schema.get$ref());
    }

    private static ApiResponse[] responsesFromMethod(String methodName) throws Exception {
        Method method = AnnotatedMethods.class.getDeclaredMethod(methodName);
        return method.getAnnotationsByType(ApiResponse.class);
    }

    private static EndpointMetadata.Endpoint endpointWithResponses(ApiResponse[] responses) {
        return new EndpointMetadata.Endpoint(
                "POST",
                "/openai/deployments/{deployment_name}/chat/completions",
                "createChatCompletion",
                null,
                null,
                null,
                new String[]{"LLM"},
                "application/json",
                new com.epam.aidial.core.openapi.annotations.ApiParameter[0],
                responses,
                ResponseProfile.NONE
        );
    }

    private static class AnnotatedMethods {

        @ApiResponse(code = 200, description = "Streaming response", schemaRef = "CreateChatCompletionResponse",
                contentTypes = {"application/json"}
        )
        @ApiResponse(code = 200, description = "Streaming response", schemaRef = "CreateChatCompletionStreamResponse",
                contentTypes = {"text/event-stream"}
        )
        @ApiResponse(code = 404, description = "Deployment not found", body = ErrorData.class)
        void annotatedResponses() {
        }

        @ApiResponse(code = 204, description = "Deleted successfully")
        void singleResponse() {
        }

        @ApiResponse(code = 204, description = "Deleted successfully")
        void noContentResponse() {
        }

        @ApiResponse(code = 200, description = "Success", schemaRef = "CreateChatCompletionResponse")
        @ApiResponse(code = 401, description = "Unauthorized", body = ErrorData.class)
        @ApiResponse(code = 404, description = "Not found", body = ErrorData.class)
        @ApiResponse(code = 429, description = "Rate limit", body = ErrorData.class)
        @ApiResponse(code = 500, description = "Server error", body = ErrorData.class)
        @ApiResponse(code = 502, description = "Upstream error", body = ErrorData.class)
        @ApiResponse(code = 503, description = "Overloaded", body = ErrorData.class)
        void multipleResponses() {
        }

        @ApiResponse(
                code = 200,
                description = "Success",
                body = OpenApiBinary.class,
                contentTypes = {"application/octet-stream"}
        )
        void binaryResponse() {
        }

        @ApiResponse(code = 500, description = "Server error", body = ErrorData.class)
        @ApiResponse(code = 200, description = "Success", body = ErrorData.class)
        @ApiResponse(code = 404, description = "Not found", body = ErrorData.class)
        void unorderedResponses() {
        }

        @ApiResponse(code = 200, description = "Streaming response", schemaRef = "CreateChatCompletionResponse",
                contentTypes = {"application/json"})
        @ApiResponse(code = 200, description = "Streaming response", schemaRef = "CreateChatCompletionStreamResponse",
                contentTypes = {"text/event-stream"})
        void llmProxyResponses() {
        }

        @ApiResponse(code = 200, description = "", body = Application.Logs.class)
        void applicationLogsResponses() {
        }

        @ApiResponse(code = 200, description = "", body = Boolean.class)
        void primitiveBooleanResponses() {
        }

        @ApiResponse(
                code = 200,
                description = "Multiple response types",
                responseOneOf = {ErrorData.class, ResourceLink.class}
        )
        void oneOfResponse() {
        }

        @ApiResponse(
                code = 200,
                description = "Stream with multiple types",
                responseOneOf = {ErrorData.class, ResourceLink.class},
                contentTypes = {"text/event-stream"}
        )
        void oneOfStreamResponse() {
        }

        @ApiResponse(
                code = 200,
                description = "Invalid: both body and responseOneOf",
                body = ErrorData.class,
                responseOneOf = {ErrorData.class, ResourceLink.class}
        )
        void invalidBothBodyAndOneOf() {
        }

        @ApiResponse(
                code = 200,
                description = "Success",
                headers = {
                        @ApiHeader(name = "ETag", description = "Entity tag", required = true)
                }
        )
        void responseWithSingleHeader() {
        }

        @ApiResponse(
                code = 200,
                description = "Success",
                headers = {
                        @ApiHeader(name = "ETag", description = "Entity tag", required = true, schema = String.class),
                        @ApiHeader(name = "X-Rate-Limit-Remaining", description = "Requests remaining", schema = Integer.class),
                        @ApiHeader(name = "X-Has-More", description = "More data available", schema = Boolean.class)
                }
        )
        void responseWithMultipleHeaders() {
        }

        @ApiResponse(code = 200, description = "Success")
        void simpleResponse() {
        }

        @ApiResponse(code = 200, description = "Truly external schema", schemaRef = "../external/ExternalResponse.yaml")
        void trulyExternalSchemaResponse() {
        }

        @ApiResponse(code = 200, description = "Schema by name", schemaRef = "MyResponseSchema")
        void schemaNameResponse() {
        }
    }
}