package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.metaschemas.MetaSchemaHolder;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
public class ApplicationTypeSchemaController {

    private static final String FAILED_READ_SCHEMA_MESSAGE = "Failed to read schema from resources";
    private static final String ID_FIELD = "$id";
    private static final String ID_PARAM = "id";
    private static final String EDITOR_URL_FIELD = "dial:applicationTypeEditorUrl";
    private static final String VIEWER_URL_FIELD = "dial:applicationTypeViewerUrl";
    private static final String DISPLAY_NAME_FIELD = "dial:applicationTypeDisplayName";
    private static final String COMPLETION_ENDPOINT_FIELD = "dial:applicationTypeCompletionEndpoint";

    private final ProxyContext context;
    private final Vertx vertx;

    public ApplicationTypeSchemaController(ProxyContext context) {
        this.context = context;
        this.vertx = context.getProxy().getVertx();
    }

    public Future<?> handleGetMetaSchema() {
        return vertx.executeBlocking(MetaSchemaHolder::getCustomApplicationMetaSchema)
                .onSuccess(metaSchema -> context.respond(HttpStatus.OK, metaSchema))
                .onFailure(throwable -> context.respond(throwable, FAILED_READ_SCHEMA_MESSAGE));
    }

    ObjectNode getSchema() throws JsonProcessingException {
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

        String schema = context.getConfig().getApplicationTypeSchemas().get(schemaId.toString());
        if (schema == null) {
            throw new HttpException(HttpStatus.NOT_FOUND, "Schema not found");
        }
        ObjectNode schemaNode = (ObjectNode) ProxyUtil.MAPPER.readTree(schema);
        if (schemaNode.has(COMPLETION_ENDPOINT_FIELD)) {
            schemaNode.remove(COMPLETION_ENDPOINT_FIELD); //we need to remove completion endpoint from response to avoid disclosure
        }
        return schemaNode;
    }

    public Future<?> handleGetSchema() {
        return vertx.executeBlocking(this::getSchema)
                .onSuccess(schemaNode -> context.respond(HttpStatus.OK, schemaNode))
                .onFailure(throwable -> context.respond(throwable, FAILED_READ_SCHEMA_MESSAGE));
    }

    private List<JsonNode> listSchemas() throws JsonProcessingException {
        Config config = context.getConfig();
        List<JsonNode> filteredSchemas = new ArrayList<>();

        for (Map.Entry<String, String> entry : config.getApplicationTypeSchemas().entrySet()) {
            JsonNode schemaNode;
            schemaNode = ProxyUtil.MAPPER.readTree(entry.getValue());

            if (schemaNode.has(ID_FIELD)
                    && schemaNode.has(EDITOR_URL_FIELD)
                    && schemaNode.has(DISPLAY_NAME_FIELD)) {
                ObjectNode filteredNode = ProxyUtil.MAPPER.createObjectNode();
                filteredNode.set(ID_FIELD, schemaNode.get(ID_FIELD));
                filteredNode.set(EDITOR_URL_FIELD, schemaNode.get(EDITOR_URL_FIELD));
                filteredNode.set(DISPLAY_NAME_FIELD, schemaNode.get(DISPLAY_NAME_FIELD));

                if (schemaNode.has(VIEWER_URL_FIELD)) {
                    filteredNode.set(VIEWER_URL_FIELD, schemaNode.get(VIEWER_URL_FIELD));
                }

                filteredSchemas.add(filteredNode);
            }
        }
        return filteredSchemas;
    }

    public Future<?> handleListSchemas() {
        return vertx.executeBlocking(this::listSchemas)
                .onSuccess(schemas -> context.respond(HttpStatus.OK, schemas))
                .onFailure(throwable -> context.respond(throwable, FAILED_READ_SCHEMA_MESSAGE));
    }
}
