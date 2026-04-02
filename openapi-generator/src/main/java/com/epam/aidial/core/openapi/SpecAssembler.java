package com.epam.aidial.core.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SpecAssembler {

    private static final String COMPONENTS_SCHEMAS_PREFIX = "#/components/schemas/";

    private final DtoSchemaGenerator schemaGenerator;
    private final String apiVersion;

    public SpecAssembler(String apiVersion) {
        this.schemaGenerator = new DtoSchemaGenerator();
        this.apiVersion = apiVersion;
    }

    public String assemble() {
        List<EndpointMetadata.Endpoint> endpoints = AnnotationEndpointCollector.collect();

        // Collect all DTO types for schema generation
        for (EndpointMetadata.Endpoint endpoint : endpoints) {
            schemaGenerator.processType(endpoint.requestBody());
            schemaGenerator.processType(endpoint.responseBody());
        }

        OpenAPI openApi = new OpenAPI();
        openApi.setOpenapi("3.0.0");
        openApi.setInfo(buildInfo());
        openApi.setComponents(buildComponents());
        openApi.setPaths(buildPaths(endpoints));
        openApi.setSecurity(List.of(new SecurityRequirement().addList("ApiKeyAuth")));

        return Yaml.pretty(openApi);
    }

    private Info buildInfo() {
        Info info = new Info();
        info.setTitle("AI DIAL Core API");
        info.setVersion(apiVersion);
        info.setDescription("AI DIAL Core API - auto-generated skeleton");
        return info;
    }

    private Components buildComponents() {
        Components components = new Components();

        // Add security scheme
        SecurityScheme apiKeyScheme = new SecurityScheme();
        apiKeyScheme.setType(SecurityScheme.Type.APIKEY);
        apiKeyScheme.setIn(SecurityScheme.In.HEADER);
        apiKeyScheme.setName("Api-Key");
        components.addSecuritySchemes("ApiKeyAuth", apiKeyScheme);

        // Add schemas from DtoSchemaGenerator
        Map<String, ObjectNode> generatedSchemas = schemaGenerator.getSchemas();
        Map<String, Schema> swaggerSchemas = new LinkedHashMap<>();
        for (Map.Entry<String, ObjectNode> entry : generatedSchemas.entrySet()) {
            swaggerSchemas.put(entry.getKey(), convertToSwaggerSchema(entry.getValue()));
        }
        if (!swaggerSchemas.isEmpty()) {
            components.setSchemas(swaggerSchemas);
        }

        return components;
    }

    private Paths buildPaths(List<EndpointMetadata.Endpoint> endpoints) {
        // Group endpoints by path
        Map<String, List<EndpointMetadata.Endpoint>> byPath = new LinkedHashMap<>();
        for (EndpointMetadata.Endpoint ep : endpoints) {
            byPath.computeIfAbsent(ep.path(), k -> new ArrayList<>()).add(ep);
        }

        Paths paths = new Paths();
        for (Map.Entry<String, List<EndpointMetadata.Endpoint>> entry : byPath.entrySet()) {
            String path = entry.getKey();
            List<EndpointMetadata.Endpoint> pathEndpoints = entry.getValue();
            PathItem pathItem = new PathItem();

            for (EndpointMetadata.Endpoint ep : pathEndpoints) {
                Operation operation = buildOperation(ep);
                setOperationOnPathItem(pathItem, ep.method(), operation);
            }

            paths.addPathItem(path, pathItem);
        }

        return paths;
    }

    private Operation buildOperation(EndpointMetadata.Endpoint endpoint) {
        Operation operation = new Operation();
        operation.setOperationId(endpoint.operationId());
        operation.setSummary(endpoint.path());
        if (endpoint.tags().length > 0) {
            operation.setTags(List.of(endpoint.tags()));
        }

        // Path parameters
        List<String> pathParams = RouteExtractor.extractPathParams(endpoint.path());
        if (!pathParams.isEmpty()) {
            List<Parameter> parameters = new ArrayList<>();
            for (String paramName : pathParams) {
                Parameter param = new Parameter();
                param.setName(paramName);
                param.setIn("path");
                param.setRequired(true);
                Schema<String> paramSchema = new Schema<>();
                paramSchema.setType("string");
                param.setSchema(paramSchema);
                parameters.add(param);
            }
            operation.setParameters(parameters);
        }

        // Request body
        if (endpoint.requestBody() != null && endpoint.requestBody() != Void.class) {
            RequestBody requestBody = new RequestBody();
            requestBody.setRequired(true);
            Content content = new Content();
            MediaType mediaType = new MediaType();
            String schemaName = schemaGenerator.resolveTypeName(endpoint.requestBody());
            Schema<?> refSchema = new Schema<>();
            refSchema.set$ref(COMPONENTS_SCHEMAS_PREFIX + schemaName);
            mediaType.setSchema(refSchema);
            content.addMediaType(endpoint.contentType(), mediaType);
            requestBody.setContent(content);
            operation.setRequestBody(requestBody);
        }

        // Responses
        ApiResponses responses = new ApiResponses();
        ApiResponse successResponse = new ApiResponse();
        successResponse.setDescription("Successful response");

        if (endpoint.responseBody() != null && endpoint.responseBody() != Void.class) {
            Content responseContent = new Content();
            MediaType responseMediaType = new MediaType();
            String schemaName = schemaGenerator.resolveTypeName(endpoint.responseBody());
            Schema<?> refSchema = new Schema<>();
            refSchema.set$ref(COMPONENTS_SCHEMAS_PREFIX + schemaName);
            responseMediaType.setSchema(refSchema);
            responseContent.addMediaType(endpoint.contentType(), responseMediaType);
            successResponse.setContent(responseContent);
        }

        responses.addApiResponse("200", successResponse);

        ApiResponse unauthorized = new ApiResponse();
        unauthorized.setDescription("Unauthorized - missing or invalid API key");
        responses.addApiResponse("401", unauthorized);

        ApiResponse forbidden = new ApiResponse();
        forbidden.setDescription("Forbidden - insufficient permissions");
        responses.addApiResponse("403", forbidden);

        ApiResponse serverError = new ApiResponse();
        serverError.setDescription("Internal server error");
        responses.addApiResponse("500", serverError);

        operation.setResponses(responses);

        return operation;
    }

    @SuppressWarnings("unchecked")
    private Schema<?> convertToSwaggerSchema(ObjectNode jsonSchemaNode) {
        Schema<Object> schema = new Schema<>();

        if (jsonSchemaNode.has("$ref")) {
            schema.set$ref(jsonSchemaNode.get("$ref").asText());
            return schema;
        }

        // Handle Draft 2020-12 type arrays: type: ["string", "null"] -> type: "string" + nullable: true
        if (jsonSchemaNode.has("type")) {
            JsonNode typeNode = jsonSchemaNode.get("type");
            if (typeNode.isArray()) {
                List<String> types = new ArrayList<>();
                boolean hasNull = false;
                for (JsonNode t : typeNode) {
                    if ("null".equals(t.asText())) {
                        hasNull = true;
                    } else {
                        types.add(t.asText());
                    }
                }
                if (types.size() == 1) {
                    schema.setType(types.get(0));
                }
                if (hasNull) {
                    schema.setNullable(true);
                }
            } else {
                schema.setType(typeNode.asText());
            }
        }

        if (jsonSchemaNode.has("format")) {
            schema.setFormat(jsonSchemaNode.get("format").asText());
        }

        if (jsonSchemaNode.has("description")) {
            schema.setDescription(jsonSchemaNode.get("description").asText());
        }

        if (jsonSchemaNode.has("title")) {
            schema.setTitle(jsonSchemaNode.get("title").asText());
        }

        // Handle Draft 2020-12 const -> single-value enum for OAS 3.0 compat
        if (jsonSchemaNode.has("const")) {
            JsonNode constNode = jsonSchemaNode.get("const");
            if (constNode.isBoolean()) {
                schema.setEnum(List.of(constNode.asBoolean()));
            } else if (constNode.isNumber()) {
                schema.setEnum(List.of(constNode.numberValue()));
            } else {
                schema.setEnum(List.of(constNode.asText()));
            }
        }

        if (jsonSchemaNode.has("enum")) {
            List<Object> enumValues = new ArrayList<>();
            jsonSchemaNode.get("enum").forEach(nd -> enumValues.add(nd.asText()));
            schema.setEnum(enumValues);
        }

        if (jsonSchemaNode.has("nullable") && jsonSchemaNode.get("nullable").asBoolean()) {
            schema.setNullable(true);
        }

        if (jsonSchemaNode.has("default")) {
            JsonNode defaultNode = jsonSchemaNode.get("default");
            if (defaultNode.isBoolean()) {
                schema.setDefault(defaultNode.asBoolean());
            } else if (defaultNode.isNumber()) {
                schema.setDefault(defaultNode.numberValue());
            } else {
                schema.setDefault(defaultNode.asText());
            }
        }

        if (jsonSchemaNode.has("minimum")) {
            schema.setMinimum(jsonSchemaNode.get("minimum").decimalValue());
        }
        if (jsonSchemaNode.has("maximum")) {
            schema.setMaximum(jsonSchemaNode.get("maximum").decimalValue());
        }
        if (jsonSchemaNode.has("minLength")) {
            schema.setMinLength(jsonSchemaNode.get("minLength").intValue());
        }
        if (jsonSchemaNode.has("maxLength")) {
            schema.setMaxLength(jsonSchemaNode.get("maxLength").intValue());
        }
        if (jsonSchemaNode.has("pattern")) {
            schema.setPattern(jsonSchemaNode.get("pattern").asText());
        }
        if (jsonSchemaNode.has("deprecated") && jsonSchemaNode.get("deprecated").asBoolean()) {
            schema.setDeprecated(true);
        }
        if (jsonSchemaNode.has("readOnly") && jsonSchemaNode.get("readOnly").asBoolean()) {
            schema.setReadOnly(true);
        }
        if (jsonSchemaNode.has("writeOnly") && jsonSchemaNode.get("writeOnly").asBoolean()) {
            schema.setWriteOnly(true);
        }
        if (jsonSchemaNode.has("minItems")) {
            schema.setMinItems(jsonSchemaNode.get("minItems").intValue());
        }
        if (jsonSchemaNode.has("maxItems")) {
            schema.setMaxItems(jsonSchemaNode.get("maxItems").intValue());
        }
        if (jsonSchemaNode.has("uniqueItems") && jsonSchemaNode.get("uniqueItems").asBoolean()) {
            schema.setUniqueItems(true);
        }

        if (jsonSchemaNode.has("properties")) {
            Map<String, Schema> properties = new LinkedHashMap<>();
            ObjectNode propsNode = (ObjectNode) jsonSchemaNode.get("properties");
            Iterator<Map.Entry<String, JsonNode>> fields = propsNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (field.getValue() instanceof ObjectNode propNode) {
                    properties.put(field.getKey(), convertToSwaggerSchema(propNode));
                }
            }
            schema.setProperties(properties);
        }

        if (jsonSchemaNode.has("required")) {
            List<String> required = new ArrayList<>();
            jsonSchemaNode.get("required").forEach(nd -> required.add(nd.asText()));
            schema.setRequired(required);
        }

        if (jsonSchemaNode.has("items")) {
            if (jsonSchemaNode.get("items") instanceof ObjectNode itemsNode) {
                schema.setItems(convertToSwaggerSchema(itemsNode));
            }
        }

        if (jsonSchemaNode.has("allOf")) {
            List<Schema> allOfSchemas = new ArrayList<>();
            jsonSchemaNode.get("allOf").forEach(nd -> {
                if (nd instanceof ObjectNode on) {
                    allOfSchemas.add(convertToSwaggerSchema(on));
                }
            });
            schema.setAllOf(allOfSchemas);
        }

        if (jsonSchemaNode.has("oneOf")) {
            List<Schema> oneOfSchemas = new ArrayList<>();
            jsonSchemaNode.get("oneOf").forEach(nd -> {
                if (nd instanceof ObjectNode on) {
                    oneOfSchemas.add(convertToSwaggerSchema(on));
                }
            });
            schema.setOneOf(oneOfSchemas);
        }

        if (jsonSchemaNode.has("anyOf")) {
            List<Schema> anyOfSchemas = new ArrayList<>();
            jsonSchemaNode.get("anyOf").forEach(nd -> {
                if (nd instanceof ObjectNode on) {
                    anyOfSchemas.add(convertToSwaggerSchema(on));
                }
            });
            schema.setAnyOf(anyOfSchemas);
        }

        if (jsonSchemaNode.has("additionalProperties")) {
            JsonNode addPropsNode = jsonSchemaNode.get("additionalProperties");
            if (addPropsNode.isBoolean()) {
                schema.setAdditionalProperties(addPropsNode.asBoolean());
            } else if (addPropsNode instanceof ObjectNode on) {
                schema.setAdditionalProperties(convertToSwaggerSchema(on));
            }
        }

        return schema;
    }

    private void setOperationOnPathItem(PathItem pathItem, String method, Operation operation) {
        switch (method.toUpperCase()) {
            case "GET" -> pathItem.setGet(operation);
            case "POST" -> pathItem.setPost(operation);
            case "PUT" -> pathItem.setPut(operation);
            case "DELETE" -> pathItem.setDelete(operation);
            case "PATCH" -> pathItem.setPatch(operation);
            case "HEAD" -> pathItem.setHead(operation);
            case "OPTIONS" -> pathItem.setOptions(operation);
            default -> throw new IllegalArgumentException("Unsupported HTTP method: " + method);
        }
    }
}
