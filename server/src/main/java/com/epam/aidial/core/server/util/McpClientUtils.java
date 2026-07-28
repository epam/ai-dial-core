package com.epam.aidial.core.server.util;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.experimental.UtilityClass;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

@UtilityClass
public class McpClientUtils {

    public static final JsonSchemaValidator NOOP_SCHEMA_VALIDATOR =
            (Map<String, Object> schema, Object content) ->
                    JsonSchemaValidator.ValidationResponse.asValid(null);

    public static final McpJsonMapper MCP_JSON_MAPPER = createLenientMcpJsonMapper();

    private static final String ADDITIONAL_PROPERTIES_FIELD = "additionalProperties";

    /**
     * Opens an {@link McpSyncClient} against {@code endpoint}, runs the MCP initialize handshake,
     * executes {@code action}, then closes the client.
     *
     * <p>{@code clientBuilder} is passed to the transport so it reuses a shared
     * {@link java.net.http.HttpClient} instead of spawning a new one per call (issue #1754).
     *
     * <p>The caller is responsible for catching {@link Exception} and mapping it to an appropriate
     * HTTP error — e.g. detecting
     * {@link io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException}
     * for 401/403 passthrough.
     */
    public static <T> T withSyncClient(String endpoint, Duration timeout,
            HttpClient.Builder clientBuilder,
            Consumer<HttpRequest.Builder> requestCustomizer, McpAction<T> action) throws Exception {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder(endpoint)
                .clientBuilder(clientBuilder)
                .jsonMapper(MCP_JSON_MAPPER)
                .httpRequestCustomizer((builder, method, ep, body, ctx) -> requestCustomizer.accept(builder))
                .build();
        try (McpSyncClient client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("DIAL", "1.0"))
                .requestTimeout(timeout)
                .jsonSchemaValidator(NOOP_SCHEMA_VALIDATOR)
                .build()) {
            client.initialize();
            return action.apply(client);
        }
    }

    @FunctionalInterface
    public interface McpAction<T> {
        T apply(McpSyncClient client) throws Exception;
    }

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
