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
 * {@code dial_update_resource(id, spec, if_match?, validate_only?)} — spec 09 §6.1 tool 5.
 * Routes to {@code PUT /v1/{type}/{bucket}/{name}}: ConfigResourceController types use plain PUT
 * (Core's explicit 404 path), ResourceController types ({@code applications, toolsets, prompts,
 * conversations}) synthesize {@code If-Match: *} so a missing entity yields 412, which the MCP
 * remaps to 404 via the request-side etag-idiom flag.
 */
public final class UpdateResourceTool {

    enum EtagIdiom { NONE, IF_MATCH_STAR_SYNTHETIC, IF_MATCH_USER }

    private final DialClient dialClient;
    private final SessionBucketCache bucketCache;

    public UpdateResourceTool(DialClient dialClient, SessionBucketCache bucketCache) {
        this.dialClient = dialClient;
        this.bucketCache = bucketCache;
    }

    public McpServerFeatures.AsyncToolSpecification spec() {
        Map<String, Object> stringProp = Map.of("type", "string");
        Map<String, Object> boolProp = Map.of("type", "boolean", "default", false);
        McpSchema.JsonSchema input = new McpSchema.JsonSchema(
                "object",
                Map.of(
                        "id", Map.of("type", "string",
                                "description", "Canonical id '{type}/{bucket}/{name}'."),
                        "spec", Map.of("type", "string",
                                "description", "JSON-encoded entity body matching the type's schema."),
                        "if_match", stringProp,
                        "validate_only", boolProp),
                List.of("id", "spec"),
                false, null, null);
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("dial_update_resource")
                .description("Replace an existing DIAL resource. Returns 404 if missing — "
                        + "call dial_create_resource instead. Optional if_match for optimistic concurrency.")
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
        Object specArg = args.get("spec");
        if (!(specArg instanceof String specStr) || specStr.isBlank()) {
            return Mono.just(McpErrors.message("'spec' argument is required (JSON-encoded entity body)."));
        }
        ResourceId parsed;
        try {
            parsed = ResourceId.parse(id);
        } catch (IllegalArgumentException e) {
            return Mono.just(McpErrors.message(e.getMessage()));
        }
        if ("files".equals(parsed.type())) {
            return Mono.just(McpErrors.message("dial_update_resource does not support 'files'. "
                    + "Use dial_upload_file for file content (when available)."));
        }
        if ("settings".equals(parsed.type())) {
            return Mono.just(McpErrors.message("dial_update_resource does not support the 'settings' singleton. "
                    + "Settings is upserted via the REST API or by dial-cli."));
        }
        boolean validateOnly = Boolean.TRUE.equals(args.get("validate_only"));
        if (validateOnly && !ResourceId.TYPE_TO_KIND.containsKey(parsed.type())) {
            return Mono.just(McpErrors.message("validate_only is not supported for type '" + parsed.type()
                    + "'. Drop the validate_only flag and try again, or omit validate_only to write the resource directly."));
        }
        String userIfMatch = args.get("if_match") instanceof String s && !s.isBlank() ? s : null;

        JsonNode specNode;
        try {
            specNode = McpJson.MAPPER.readTree(specStr);
        } catch (Exception e) {
            return Mono.just(McpErrors.message("'spec' must be valid JSON: " + e.getMessage()));
        }

        Map<String, String> auth = ToolContext.authHeaders(exchange);
        Mono<String> resolvedBucket = "private".equals(parsed.bucket())
                ? bucketCache.resolvePrivate(ToolContext.sessionId(exchange), auth)
                : Mono.just(parsed.bucket());

        if (validateOnly) {
            String envelope = CreateResourceTool.buildValidateEnvelope(parsed.type(), parsed.name(), specNode);
            return resolvedBucket
                    .flatMap(bucket -> dialClient.request(HttpMethod.POST, "/v1/admin/validate", auth, Map.of(), envelope)
                            .map(resp -> shapeValidate(resp, parsed, bucket)))
                    .onErrorResume(t -> Mono.just(McpErrors.upstreamError(t)));
        }

        Map<String, String> correlation;
        EtagIdiom idiom;
        if (userIfMatch != null) {
            correlation = Map.of("If-Match", userIfMatch);
            idiom = EtagIdiom.IF_MATCH_USER;
        } else if (parsed.isResourceControllerType()) {
            correlation = Map.of("If-Match", "*");
            idiom = EtagIdiom.IF_MATCH_STAR_SYNTHETIC;
        } else {
            correlation = Map.of();
            idiom = EtagIdiom.NONE;
        }

        return resolvedBucket
                .flatMap(bucket -> dialClient.request(HttpMethod.PUT, parsed.toCorePath(bucket), auth, correlation, specStr)
                        .map(resp -> shape(resp, parsed, bucket, idiom, userIfMatch)))
                .onErrorResume(t -> Mono.just(McpErrors.upstreamError(t)));
    }

    static McpSchema.CallToolResult shape(DialResponse resp, ResourceId id, String resolvedBucket, EtagIdiom idiom, String userEtag) {
        String canonical = id.type() + "/" + resolvedBucket + "/" + id.name();
        if (resp.statusCode() == 200) {
            String etag = resp.headers() != null ? resp.headers().get("ETag") : null;
            ObjectNode result = McpJson.MAPPER.createObjectNode();
            result.put("updated", true);
            result.put("id", canonical);
            result.put("name", id.name());
            if (etag == null) {
                result.putNull("etag");
            } else {
                result.put("etag", etag);
            }
            return McpSchema.CallToolResult.builder()
                    .content(List.of(new McpSchema.TextContent(result.toString())))
                    .isError(false)
                    .build();
        }
        if (resp.statusCode() == 404) {
            return McpErrors.notFoundError(canonical);
        }
        if (resp.statusCode() == 412) {
            if (idiom == EtagIdiom.IF_MATCH_STAR_SYNTHETIC) {
                return McpErrors.notFoundError(canonical);
            }
            if (idiom == EtagIdiom.IF_MATCH_USER) {
                return McpErrors.preconditionFailedError(canonical, userEtag);
            }
        }
        return McpErrors.httpError(resp.statusCode(), resp.body(),
                "Verify '" + canonical + "' exists and the caller has admin/write access.");
    }

    static McpSchema.CallToolResult shapeValidate(DialResponse resp, ResourceId id, String resolvedBucket) {
        return CreateResourceTool.shapeValidate(resp, id, resolvedBucket);
    }
}
