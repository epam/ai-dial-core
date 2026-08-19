package com.epam.aidial.core.server.mcp;

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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;

/**
 * Shared helpers for talking to MCP servers, used by both the Vert.x-based MCP proxy path
 * ({@code McpProxyController}) and the MCP-SDK/java.net.http-based toolset paths
 * ({@code RedirectSafeHttpClient}).
 */
@UtilityClass
public class McpClientUtils {

    public static final JsonSchemaValidator NOOP_SCHEMA_VALIDATOR =
            (Map<String, Object> schema, Object content) ->
                    JsonSchemaValidator.ValidationResponse.asValid(null);

    public static final McpJsonMapper MCP_JSON_MAPPER = createLenientMcpJsonMapper();

    /**
     * Cap on the number of same-origin redirects to follow for a single MCP request, matching the
     * JDK HttpClient's own default ({@code jdk.httpclient.redirects.retrylimit}).
     */
    public static final int MAX_MCP_REDIRECTS = 5;

    private static final String ADDITIONAL_PROPERTIES_FIELD = "additionalProperties";

    /**
     * The SDK's transport builder defaults to endpoint "/mcp", and since it resolves as an absolute-path
     * reference, it replaces (rather than appends to) the base URI's path - silently dropping any path
     * configured on the endpoint. Splitting origin/path here and always setting endpoint(...) explicitly
     * ensures the configured endpoint is what actually gets requested.
     */
    public static HttpClientStreamableHttpTransport.Builder transportBuilder(String endpoint) {
        URI uri = URI.create(endpoint);
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        if (uri.getRawQuery() != null) {
            path = path + "?" + uri.getRawQuery();
        }
        String origin = uri.getScheme() + "://" + uri.getRawAuthority();
        return HttpClientStreamableHttpTransport.builder(origin).endpoint(path);
    }

    /**
     * True if {@code a} and {@code b} share the same scheme, host, and (resolved) port - used to
     * decide whether a redirect may be followed with the original request's headers intact, since
     * auth headers (Authorization, API keys) must never be forwarded to a different origin.
     */
    public static boolean isSameOrigin(URI a, URI b) {
        return a.getHost() != null && b.getHost() != null
                && a.getScheme().equalsIgnoreCase(b.getScheme())
                && a.getHost().equalsIgnoreCase(b.getHost())
                && resolvePort(a) == resolvePort(b);
    }

    public static int resolvePort(URI uri) {
        int port = uri.getPort();
        return port != -1 ? port : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
    }

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
            RequestCustomizer requestCustomizer, McpAction<T> action) throws Exception {
        HttpClientStreamableHttpTransport transport = transportBuilder(endpoint)
                .clientBuilder(clientBuilder)
                .jsonMapper(MCP_JSON_MAPPER)
                .httpRequestCustomizer((builder, method, ep, body, ctx) -> requestCustomizer.customize(builder, body))
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

    @FunctionalInterface
    public interface RequestCustomizer {
        void customize(HttpRequest.Builder builder, String body);
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
