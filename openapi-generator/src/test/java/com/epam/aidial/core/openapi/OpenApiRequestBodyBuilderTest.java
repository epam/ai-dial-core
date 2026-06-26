package com.epam.aidial.core.openapi;

import com.epam.aidial.core.openapi.annotations.ApiExtension;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ResponseProfile;
import com.epam.aidial.core.server.data.ResourceLink;
import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiRequestBodyBuilderTest {

    @Test
    void multipartBinaryUploadUsesObjectSchemaWithFileProperty() {
        EndpointMetadata.Endpoint endpoint = new EndpointMetadata.Endpoint(
                "PUT",
                "/v1/files/{bucket}/{file_path}",
                "uploadFile",
                ApiSchemaBuilder.forImplementation(byte[].class),  // requestBody
                new String[]{"Files"},  // tags
                "multipart/form-data",  // contentType
                new ApiParameter[0],
                new ApiResponse[0],
                ResponseProfile.CONDITIONAL_WRITE,
                new ApiExtension[0]
        );
        DtoSchemaGenerator schemaGenerator = new DtoSchemaGenerator();
        OpenApiRequestBodyBuilder.registerRequestBodySchemas(endpoint, schemaGenerator);

        RequestBody requestBody = OpenApiRequestBodyBuilder.build(endpoint, schemaGenerator);

        assertNotNull(requestBody);
        assertTrue(requestBody.getRequired());
        var mediaType = requestBody.getContent().get("multipart/form-data");
        assertNotNull(mediaType);
        var schema = mediaType.getSchema();
        assertEquals("object", schema.getType());
        assertNull(schema.get$ref());
        @SuppressWarnings("unchecked")
        Schema<?> fileProperty = (Schema<?>) schema.getProperties().get("file");
        assertEquals("string", fileProperty.getType());
        assertEquals("binary", fileProperty.getFormat());
        assertEquals(1, schema.getRequired().size());
        assertEquals("file", schema.getRequired().get(0));
        assertFalse(schemaGenerator.getSchemas().containsKey("OpenApiBinary"),
                "OpenApiBinary must not be registered as a component schema");
    }

    @Test
    void jsonRequestBodyUsesComponentSchemaRef() {
        EndpointMetadata.Endpoint endpoint = new EndpointMetadata.Endpoint(
                "POST",
                "/openai/deployments/{deployment_name}/chat/completions",
                "createChatCompletion",
                ApiSchemaBuilder.forImplementation(ResourceLink.class),
                new String[]{"Applications"},
                "application/json",
                new ApiParameter[0],
                new ApiResponse[0],
                ResponseProfile.NONE,
                new ApiExtension[0]
        );
        DtoSchemaGenerator schemaGenerator = new DtoSchemaGenerator();
        OpenApiRequestBodyBuilder.registerRequestBodySchemas(endpoint, schemaGenerator);

        RequestBody requestBody = OpenApiRequestBodyBuilder.build(endpoint, schemaGenerator);

        assertNotNull(requestBody);
        assertEquals("#/components/schemas/ResourceLink",
                requestBody.getContent().get("application/json").getSchema().get$ref());
        assertTrue(schemaGenerator.getSchemas().containsKey("ResourceLink"));
    }

    @Test
    void voidRequestBodyReturnsNull() {
        EndpointMetadata.Endpoint endpoint = new EndpointMetadata.Endpoint(
                "GET",
                "/v1/example",
                "getExample",
                ApiSchemaBuilder.forImplementation(Void.class),
                new String[]{"Examples"},
                "application/json",
                new ApiParameter[0],
                new ApiResponse[0],
                ResponseProfile.NONE,
                new ApiExtension[0]
        );

        assertNull(OpenApiRequestBodyBuilder.build(endpoint, new DtoSchemaGenerator()));
    }

    @Test
    void requestBodySerializesToYaml() throws Exception {
        EndpointMetadata.Endpoint endpoint = new EndpointMetadata.Endpoint(
                "PUT",
                "/v1/files/{bucket}/{file_path}",
                "uploadFile",
                ApiSchemaBuilder.forImplementation(byte[].class),
                new String[]{"Files"},
                "multipart/form-data",
                new ApiParameter[0],
                new ApiResponse[0],
                ResponseProfile.CONDITIONAL_WRITE,
                new ApiExtension[0]
        );

        Operation operation = new Operation();
        operation.setRequestBody(OpenApiRequestBodyBuilder.build(endpoint, new DtoSchemaGenerator()));
        String yaml = Yaml.pretty(operation);

        assertTrue(yaml.contains("multipart/form-data"));
        assertTrue(yaml.contains("format: binary"));
        assertTrue(yaml.contains("file:"));
        assertTrue(yaml.contains("required: true"));
        assertFalse(yaml.contains("OpenApiBinary"));
    }

    @Test
    void primitiveRequestBodyUsesInlineSchemaWithoutComponent() {
        EndpointMetadata.Endpoint endpoint = new EndpointMetadata.Endpoint(
                "POST",
                "/v1/example",
                "postFlag",
                ApiSchemaBuilder.forImplementation(Boolean.class),
                new String[]{"Examples"},
                "application/json",
                new ApiParameter[0],
                new ApiResponse[0],
                ResponseProfile.NONE,
                new ApiExtension[0]
        );
        DtoSchemaGenerator schemaGenerator = new DtoSchemaGenerator();
        OpenApiRequestBodyBuilder.registerRequestBodySchemas(endpoint, schemaGenerator);

        RequestBody requestBody = OpenApiRequestBodyBuilder.build(endpoint, schemaGenerator);
        Schema<?> schema = requestBody.getContent().get("application/json").getSchema();

        assertEquals("boolean", schema.getType());
        assertNull(schema.get$ref());
        assertFalse(schemaGenerator.getSchemas().containsKey("Boolean"));
    }

    @Test
    void openApiBinaryWithJsonContentTypeUsesBinaryStringSchema() {
        EndpointMetadata.Endpoint endpoint = new EndpointMetadata.Endpoint(
                "POST",
                "/v1/binary",
                "postBinary",
                ApiSchemaBuilder.forImplementation(byte[].class),
                new String[]{"Test"},
                "application/json",
                new ApiParameter[0],
                new ApiResponse[0],
                ResponseProfile.NONE,
                new ApiExtension[0]
        );

        RequestBody requestBody = OpenApiRequestBodyBuilder.build(endpoint, new DtoSchemaGenerator());
        var schema = requestBody.getContent().get("application/json").getSchema();

        assertEquals("string", schema.getType());
        assertEquals("binary", schema.getFormat());
        assertNull(schema.get$ref());
    }

    @Test
    void trulyExternalSchemaRefIsPreservedAsIs() {
        EndpointMetadata.Endpoint endpoint = new EndpointMetadata.Endpoint(
                "POST",
                "/v1/example",
                "postExample",
                ApiSchemaBuilder.forSchemaRef("../external/ExternalSchema.yaml"),
                new String[]{"Test"},
                "application/json",
                new ApiParameter[0],
                new ApiResponse[0],
                ResponseProfile.NONE,
                new ApiExtension[0]
        );
        DtoSchemaGenerator schemaGenerator = new DtoSchemaGenerator();
        OpenApiRequestBodyBuilder.registerRequestBodySchemas(endpoint, schemaGenerator);

        RequestBody requestBody = OpenApiRequestBodyBuilder.build(endpoint, schemaGenerator);

        assertNotNull(requestBody);
        var schema = requestBody.getContent().get("application/json").getSchema();
        assertEquals("../external/ExternalSchema.yaml", schema.get$ref());
        assertFalse(schemaGenerator.getSchemas().containsKey("../external/ExternalSchema.yaml"),
                "Truly external schema ref should not be registered in components");
    }

    @Test
    void schemaNameIsConvertedToComponentRef() {
        EndpointMetadata.Endpoint endpoint = new EndpointMetadata.Endpoint(
                "POST",
                "/v1/example",
                "postExample",
                ApiSchemaBuilder.forSchemaRef("MySchema"),
                new String[]{"Test"},
                "application/json",
                new ApiParameter[0],
                new ApiResponse[0],
                ResponseProfile.NONE,
                new ApiExtension[0]
        );
        DtoSchemaGenerator schemaGenerator = new DtoSchemaGenerator();
        OpenApiRequestBodyBuilder.registerRequestBodySchemas(endpoint, schemaGenerator);

        RequestBody requestBody = OpenApiRequestBodyBuilder.build(endpoint, schemaGenerator);

        assertNotNull(requestBody);
        var schema = requestBody.getContent().get("application/json").getSchema();
        assertEquals("#/components/schemas/MySchema", schema.get$ref());
    }
}