package com.epam.aidial.core.mcp.tools;

import com.epam.aidial.core.mcp.client.DialClient;
import com.epam.aidial.core.mcp.client.DialResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.vertx.core.http.HttpMethod;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * {@code dial_get_resource(id, format?)} — spec 09 §6.1 tool 3, §6.4 (default {@code detailed}).
 * Surfaces the {@code ETag} response header verbatim ({@code null} for config-type GETs in
 * Phase 1 — pre-existing Core gap; file ETag becomes reachable here in M.1.1).
 */
public final class GetResourceTool {

    private final DialClient dialClient;
    private final SessionBucketCache bucketCache;

    public GetResourceTool(DialClient dialClient, SessionBucketCache bucketCache) {
        this.dialClient = dialClient;
        this.bucketCache = bucketCache;
    }

    public McpServerFeatures.AsyncToolSpecification spec() {
        Map<String, Object> formatProp = Map.of(
                "type", "string",
                "enum", List.of("summary", "detailed"),
                "default", "detailed");
        McpSchema.JsonSchema input = new McpSchema.JsonSchema(
                "object",
                Map.of(
                        "id", Map.of("type", "string",
                                "description", "Canonical id '{type}/{bucket}/{name}'. M.1.0 pilot: models/roles/settings."),
                        "format", formatProp),
                List.of("id"),
                false, null, null);
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("dial_get_resource")
                .description("Reads a single DIAL resource. Returns the entity body augmented with an etag field. "
                        + "Example: {\"id\":\"models/public/gpt-4\"}.")
                .inputSchema(input)
                .build();
        return McpServerFeatures.AsyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handle)
                .build();
    }

    private Mono<McpSchema.CallToolResult> handle(McpAsyncServerExchange exchange, McpSchema.CallToolRequest request) {
        Object idArg = request.arguments() == null ? null : request.arguments().get("id");
        if (!(idArg instanceof String id) || id.isBlank()) {
            return Mono.just(McpErrors.message("'id' argument is required."));
        }
        ResourceId parsed;
        try {
            parsed = ResourceId.parse(id);
        } catch (IllegalArgumentException e) {
            return Mono.just(McpErrors.message(e.getMessage()));
        }
        Map<String, String> auth = ToolContext.authHeaders(exchange);

        Mono<String> resolvedBucket = "private".equals(parsed.bucket())
                ? bucketCache.resolvePrivate(ToolContext.sessionId(exchange), auth)
                : Mono.just(parsed.bucket());

        return resolvedBucket
                .flatMap(bucket -> dialClient.request(HttpMethod.GET, parsed.toCorePath(bucket), auth, Map.of(), null)
                        .map(resp -> shape(resp, parsed)))
                .onErrorResume(t -> Mono.just(McpErrors.upstreamError(t)));
    }

    static McpSchema.CallToolResult shape(DialResponse resp, ResourceId id) {
        if (resp.statusCode() != 200) {
            return McpErrors.httpError(resp.statusCode(), resp.body(),
                    "Verify '" + id.type() + "/" + id.bucket() + "/" + id.name() + "' exists and the caller has access.");
        }
        try {
            JsonNode body = McpJson.MAPPER.readTree(resp.body());
            ObjectNode result = body.isObject() ? body.deepCopy() : McpJson.MAPPER.createObjectNode().set("value", body);
            String etag = resp.headers() != null ? resp.headers().get("ETag") : null;
            if (etag == null) {
                result.putNull("etag");
            } else {
                result.put("etag", etag);
            }
            return McpSchema.CallToolResult.builder()
                    .content(List.of(new McpSchema.TextContent(result.toString())))
                    .isError(false)
                    .build();
        } catch (Exception e) {
            return McpErrors.upstreamError(e);
        }
    }
}
