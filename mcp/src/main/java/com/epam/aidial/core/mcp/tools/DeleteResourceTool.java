package com.epam.aidial.core.mcp.tools;

import com.epam.aidial.core.mcp.client.DialClient;
import com.epam.aidial.core.mcp.client.DialResponse;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.vertx.core.http.HttpMethod;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * {@code dial_delete_resource(id, confirm, if_match?)} — spec 09 §6.1 tool 6. {@code confirm: true}
 * is gated MCP-side before any HTTP call. {@code files} and {@code settings} are rejected with a
 * remediation hint (binary file deletes and singleton resets are out of scope).
 */
public final class DeleteResourceTool {

    enum EtagIdiom { NONE, IF_MATCH_USER }

    private final DialClient dialClient;
    private final SessionBucketCache bucketCache;

    public DeleteResourceTool(DialClient dialClient, SessionBucketCache bucketCache) {
        this.dialClient = dialClient;
        this.bucketCache = bucketCache;
    }

    public McpServerFeatures.AsyncToolSpecification spec() {
        Map<String, Object> stringProp = Map.of("type", "string");
        Map<String, Object> boolProp = Map.of("type", "boolean");
        McpSchema.JsonSchema input = new McpSchema.JsonSchema(
                "object",
                Map.of(
                        "id", Map.of("type", "string",
                                "description", "Canonical id '{type}/{bucket}/{name}'."),
                        "confirm", boolProp,
                        "if_match", stringProp),
                List.of("id", "confirm"),
                false, null, null);
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("dial_delete_resource")
                .description("Delete a DIAL resource. Requires confirm=true to prevent accidents. "
                        + "Optional if_match for optimistic concurrency.")
                .inputSchema(input)
                .build();
        return McpServerFeatures.AsyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handle)
                .build();
    }

    private Mono<McpSchema.CallToolResult> handle(McpAsyncServerExchange exchange, McpSchema.CallToolRequest request) {
        Map<String, Object> args = request.arguments();
        Object idArg = args == null ? null : args.get("id");
        if (!(idArg instanceof String id) || id.isBlank()) {
            return Mono.just(McpErrors.message("'id' argument is required."));
        }
        ResourceId parsed;
        try {
            parsed = ResourceId.parse(id);
        } catch (IllegalArgumentException e) {
            return Mono.just(McpErrors.message(e.getMessage()));
        }
        if ("files".equals(parsed.type())) {
            return Mono.just(McpErrors.message("dial_delete_resource does not support 'files'. "
                    + "Use the REST API directly for file deletes."));
        }
        if ("settings".equals(parsed.type())) {
            return Mono.just(McpErrors.message("dial_delete_resource does not support the 'settings' singleton. "
                    + "Settings is a singleton and is not user-deletable."));
        }
        boolean confirm = Boolean.TRUE.equals(args.get("confirm"));
        if (!confirm) {
            return Mono.just(McpErrors.message("confirm must be true to proceed with deletion. "
                    + "Set confirm: true if you intend to permanently delete '" + id + "'."));
        }
        String userIfMatch = args.get("if_match") instanceof String s && !s.isBlank() ? s : null;

        Map<String, String> auth = ToolContext.authHeaders(exchange);
        Mono<String> resolvedBucket = "private".equals(parsed.bucket())
                ? bucketCache.resolvePrivate(ToolContext.sessionId(exchange), auth)
                : Mono.just(parsed.bucket());

        Map<String, String> correlation;
        EtagIdiom idiom;
        if (userIfMatch != null) {
            correlation = Map.of("If-Match", userIfMatch);
            idiom = EtagIdiom.IF_MATCH_USER;
        } else {
            correlation = Map.of();
            idiom = EtagIdiom.NONE;
        }

        return resolvedBucket
                .flatMap(bucket -> dialClient.request(HttpMethod.DELETE, parsed.toCorePath(bucket), auth, correlation, null)
                        .map(resp -> shape(resp, parsed, bucket, idiom, userIfMatch)))
                .onErrorResume(t -> Mono.just(McpErrors.upstreamError(t)));
    }

    static McpSchema.CallToolResult shape(DialResponse resp, ResourceId id, String resolvedBucket, EtagIdiom idiom, String userEtag) {
        String canonical = id.type() + "/" + resolvedBucket + "/" + id.name();
        if (resp.statusCode() == 200 || resp.statusCode() == 204) {
            ObjectNode result = McpJson.MAPPER.createObjectNode();
            result.put("deleted", true);
            result.put("id", canonical);
            return McpSchema.CallToolResult.builder()
                    .content(List.of(new McpSchema.TextContent(result.toString())))
                    .isError(false)
                    .build();
        }
        if (resp.statusCode() == 404) {
            return McpErrors.notFoundError(canonical);
        }
        if (resp.statusCode() == 412 && idiom == EtagIdiom.IF_MATCH_USER) {
            return McpErrors.preconditionFailedError(canonical, userEtag);
        }
        return McpErrors.httpError(resp.statusCode(), resp.body(),
                "Verify '" + canonical + "' exists and the caller has admin/write access.");
    }
}
