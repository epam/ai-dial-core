package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.metaschemas.MetaSchemaHolder;
import com.epam.aidial.core.server.ProxyContext;
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
public class ApplicationTypeSchemaController {

    private static final String FAILED_READ_SCHEMA_MESSAGE = "Failed to read schema from resources";
    private static final String ID_PARAM = "id";

    private final ProxyContext context;
    private final AsyncTaskExecutor taskExecutor;

    public ApplicationTypeSchemaController(ProxyContext context) {
        this.context = context;
        this.taskExecutor = context.getProxy().getTaskExecutor();
    }

    public Future<?> handleGetMetaSchema() {
        return taskExecutor.submit(MetaSchemaHolder::getCustomApplicationMetaSchema)
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
        if (schemaNode.has(MetaSchemaHolder.APPLICATION_TYPE_COMPLETION_ENDPOINT)) {
            schemaNode.remove(MetaSchemaHolder.APPLICATION_TYPE_COMPLETION_ENDPOINT); //we need to remove endpoints from response to avoid disclosure
        }
        if (schemaNode.has(MetaSchemaHolder.APPLICATION_TYPE_CONFIGURATION_ENDPOINT)) {
            schemaNode.remove(MetaSchemaHolder.APPLICATION_TYPE_CONFIGURATION_ENDPOINT);
        }
        if (schemaNode.has(MetaSchemaHolder.APPLICATION_TYPE_RATE_ENDPOINT)) {
            schemaNode.remove(MetaSchemaHolder.APPLICATION_TYPE_RATE_ENDPOINT);
        }
        if (schemaNode.has(MetaSchemaHolder.APPLICATION_TYPE_TRUNCATE_PROMPT_ENDPOINT)) {
            schemaNode.remove(MetaSchemaHolder.APPLICATION_TYPE_TRUNCATE_PROMPT_ENDPOINT);
        }
        if (schemaNode.has(MetaSchemaHolder.APPLICATION_TYPE_TOKENIZE_ENDPOINT)) {
            schemaNode.remove(MetaSchemaHolder.APPLICATION_TYPE_TOKENIZE_ENDPOINT);
        }
        if (schemaNode.has(MetaSchemaHolder.APPLICATION_TYPE_APPEND_APPLICATION_PROPERTIES)) {
            schemaNode.remove(MetaSchemaHolder.APPLICATION_TYPE_APPEND_APPLICATION_PROPERTIES);
        }
        return schemaNode;
    }

    public Future<?> handleGetSchema() {
        return taskExecutor.submit(this::getSchema)
                .onSuccess(schemaNode -> context.respond(HttpStatus.OK, schemaNode))
                .onFailure(throwable -> context.respond(throwable, FAILED_READ_SCHEMA_MESSAGE));
    }

    private List<JsonNode> listSchemas() throws JsonProcessingException {
        Config config = context.getConfig();
        List<JsonNode> filteredSchemas = new ArrayList<>();

        for (Map.Entry<String, String> entry : config.getApplicationTypeSchemas().entrySet()) {
            JsonNode schemaNode;
            schemaNode = ProxyUtil.MAPPER.readTree(entry.getValue());

            if (schemaNode.has(MetaSchemaHolder.APPLICATION_TYPE_ID_FIELD)
                    && schemaNode.has(MetaSchemaHolder.APPLICATION_TYPE_DISPLAY_NAME)) {
                ObjectNode filteredNode = ProxyUtil.MAPPER.createObjectNode();
                filteredNode.set(MetaSchemaHolder.APPLICATION_TYPE_ID_FIELD, schemaNode.get(MetaSchemaHolder.APPLICATION_TYPE_ID_FIELD));
                filteredNode.set(MetaSchemaHolder.APPLICATION_TYPE_DISPLAY_NAME, schemaNode.get(MetaSchemaHolder.APPLICATION_TYPE_DISPLAY_NAME));

                if (schemaNode.has(MetaSchemaHolder.APPLICATION_TYPE_VIEWER_URL)) {
                    filteredNode.set(MetaSchemaHolder.APPLICATION_TYPE_VIEWER_URL, schemaNode.get(MetaSchemaHolder.APPLICATION_TYPE_VIEWER_URL));
                }
                if (schemaNode.has(MetaSchemaHolder.APPLICATION_TYPE_EDITOR_URL)) {
                    filteredNode.set(MetaSchemaHolder.APPLICATION_TYPE_EDITOR_URL, schemaNode.get(MetaSchemaHolder.APPLICATION_TYPE_EDITOR_URL));
                }
                if (schemaNode.has(MetaSchemaHolder.APPLICATION_TYPE_ICON_URL)) {
                    filteredNode.set(MetaSchemaHolder.APPLICATION_TYPE_ICON_URL, schemaNode.get(MetaSchemaHolder.APPLICATION_TYPE_ICON_URL));
                }
                if (schemaNode.has(MetaSchemaHolder.APPLICATION_TYPE_PLAYBACK_SUPPORT)) {
                    filteredNode.set(MetaSchemaHolder.APPLICATION_TYPE_PLAYBACK_SUPPORT, schemaNode.get(MetaSchemaHolder.APPLICATION_TYPE_PLAYBACK_SUPPORT));
                }
                if (schemaNode.has(MetaSchemaHolder.DIAL_APPLICATION_TYPE_BUCKET_COPY)) {
                    filteredNode.set(MetaSchemaHolder.DIAL_APPLICATION_TYPE_BUCKET_COPY, schemaNode.get(MetaSchemaHolder.DIAL_APPLICATION_TYPE_BUCKET_COPY));
                }
                if (schemaNode.has(MetaSchemaHolder.DIAL_APPLICATION_TYPE_SCHEMA_ENDPOINT)) {
                    filteredNode.set(MetaSchemaHolder.DIAL_APPLICATION_TYPE_SCHEMA_ENDPOINT, schemaNode.get(MetaSchemaHolder.DIAL_APPLICATION_TYPE_SCHEMA_ENDPOINT));
                }

                filteredSchemas.add(filteredNode);
            }
        }
        return filteredSchemas;
    }

    public Future<?> handleListSchemas() {
        return taskExecutor.submit(this::listSchemas)
                .onSuccess(schemas -> context.respond(HttpStatus.OK, schemas))
                .onFailure(throwable -> context.respond(throwable, FAILED_READ_SCHEMA_MESSAGE));
    }
}
