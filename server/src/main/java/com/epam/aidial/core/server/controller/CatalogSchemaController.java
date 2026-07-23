package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.metaschemas.CatalogMetaSchemaHolder;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.OpenApiDescriptions;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.service.CatalogSchemaService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServerRequest;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
public class CatalogSchemaController {

    private static final String FAILED_READ_SCHEMA_MESSAGE = "Failed to read schema from resources";
    private static final String ID_PARAM = "id";

    private final ProxyContext context;
    private final AsyncTaskExecutor taskExecutor;
    private final CatalogSchemaService catalogSchemaService;

    public CatalogSchemaController(ProxyContext context) {
        this.context = context;
        this.taskExecutor = context.getProxy().getTaskExecutor();
        this.catalogSchemaService = context.getProxy().getCatalogSchemaService();
    }

    @ApiOperation(
            method = "GET",
            path = "/v1/catalog_schemas/meta_schema",
            operationId = "getMetaSchemaOfCatalogSchema",
            tags = {"Catalog"},
            responses = {
                    @ApiResponse(code = 200, description = "Success"),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 401),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 500)
            }
    )
    public Future<?> handleGetMetaSchema() {
        return taskExecutor.submit(CatalogMetaSchemaHolder::getCatalogMetaSchema)
                .onSuccess(metaSchema -> context.respond(HttpStatus.OK, metaSchema))
                .onFailure(throwable -> context.respond(throwable, FAILED_READ_SCHEMA_MESSAGE));
    }

    String getSchema() {
        HttpServerRequest request = context.getRequest();
        String schemaIdParam = request.getParam(ID_PARAM);

        if (schemaIdParam == null) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Schema ID is required");
        }

        URI schemaId;
        try {
            schemaId = URI.create(schemaIdParam);
        } catch (IllegalArgumentException e) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Bad Schema ID");
        }

        String schema = catalogSchemaService.getSchema(schemaId);
        if (schema == null) {
            throw new HttpException(HttpStatus.NOT_FOUND, "Schema not found");
        }
        return schema;
    }

    @ApiOperation(
            method = "GET",
            path = "/v1/catalog_schemas/schema",
            operationId = "getCatalogSchema",
            tags = {"Catalog"},
            parameters = {
                    @ApiParameter(name = "id", in = ParameterIn.QUERY, required = true, description = OpenApiDescriptions.SCHEMA_ID)
            },
            responses = {
                    @ApiResponse(code = 200, description = "Success"),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 401),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 500)
            }
    )
    public Future<?> handleGetSchema() {
        return taskExecutor.submit(this::getSchema)
                .onSuccess(schema -> context.respond(HttpStatus.OK, schema))
                .onFailure(throwable -> context.respond(throwable, FAILED_READ_SCHEMA_MESSAGE));
    }

    private List<JsonNode> listSchemas() throws JsonProcessingException {
        Config config = context.getConfig();
        List<JsonNode> filteredSchemas = new ArrayList<>();

        for (Map.Entry<String, String> entry : config.getCatalogSchemas().entrySet()) {
            JsonNode schemaNode = ProxyUtil.MAPPER.readTree(entry.getValue());

            if (schemaNode.has(CatalogMetaSchemaHolder.CATALOG_SCHEMA_ID_FIELD)
                    && schemaNode.has(CatalogMetaSchemaHolder.CATALOG_DISPLAY_NAME)) {
                ObjectNode filteredNode = ProxyUtil.MAPPER.createObjectNode();
                filteredNode.set(CatalogMetaSchemaHolder.CATALOG_SCHEMA_ID_FIELD, schemaNode.get(CatalogMetaSchemaHolder.CATALOG_SCHEMA_ID_FIELD));
                filteredNode.set(CatalogMetaSchemaHolder.CATALOG_ENTITY_TYPE, schemaNode.get(CatalogMetaSchemaHolder.CATALOG_ENTITY_TYPE));
                filteredNode.set(CatalogMetaSchemaHolder.CATALOG_DISPLAY_NAME, schemaNode.get(CatalogMetaSchemaHolder.CATALOG_DISPLAY_NAME));
                filteredSchemas.add(filteredNode);
            }
        }
        return filteredSchemas;
    }

    @ApiOperation(
            method = "GET",
            path = "/v1/catalog_schemas/schemas",
            operationId = "listCatalogSchemas",
            tags = {"Catalog"},
            responses = {
                    @ApiResponse(code = 200, description = "Success"),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 401),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 500)
            }
    )
    public Future<?> handleListSchemas() {
        return taskExecutor.submit(this::listSchemas)
                .onSuccess(schemas -> context.respond(HttpStatus.OK, schemas))
                .onFailure(throwable -> context.respond(throwable, FAILED_READ_SCHEMA_MESSAGE));
    }
}
