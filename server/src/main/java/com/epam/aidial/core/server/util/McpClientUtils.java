package com.epam.aidial.core.server.util;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import lombok.experimental.UtilityClass;

import java.io.IOException;
import java.util.Map;

@UtilityClass
public class McpClientUtils {

    public static final JsonSchemaValidator NOOP_SCHEMA_VALIDATOR =
            (Map<String, Object> schema, Object content) ->
                    JsonSchemaValidator.ValidationResponse.asValid(null);

    public static final McpJsonMapper MCP_JSON_MAPPER = createLenientMcpJsonMapper();

    private static final String ADDITIONAL_PROPERTIES_FIELD = "additionalProperties";

    private static McpJsonMapper createLenientMcpJsonMapper() {
        ObjectMapper mapper = new ObjectMapper();
        // JSON Schema allows `additionalProperties` to be a boolean OR a schema object,
        // but the MCP SDK models it as Boolean. Coerce object/array values to null so a
        // single non-conforming field does not fail the whole tools/list response (#1561).
        mapper.addHandler(new DeserializationProblemHandler() {
            @Override
            public Object handleUnexpectedToken(DeserializationContext ctxt, JavaType targetType,
                    JsonToken t, JsonParser p, String failureMsg) throws IOException {
                if (targetType.hasRawClass(Boolean.class)
                        && (t == JsonToken.START_OBJECT || t == JsonToken.START_ARRAY)
                        && ADDITIONAL_PROPERTIES_FIELD.equals(p.currentName())) {
                    p.skipChildren();
                    return null;
                }
                return super.handleUnexpectedToken(ctxt, targetType, t, p, failureMsg);
            }
        });
        return new JacksonMcpJsonMapper(mapper);
    }
}
