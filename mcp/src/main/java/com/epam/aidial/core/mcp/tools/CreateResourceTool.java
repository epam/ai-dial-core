package com.epam.aidial.core.mcp.tools;

import com.epam.aidial.core.mcp.client.DialClient;
import com.epam.aidial.core.mcp.client.DialResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.vertx.core.http.HttpMethod;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * {@code dial_create_resource(id, spec, validate_only?)} — spec 09 §6.1 tool 4, §6.5, §6.6.
 * Routes to {@code POST /v1/{type}/{bucket}/{name}} for ConfigResourceController types and to
 * {@code PUT} with {@code If-None-Match: *} for ResourceController types ({@code applications,
 * toolsets, prompts, conversations}). Out of M.2.0 scope: {@code files} and {@code settings}.
 */
public final class CreateResourceTool {

    enum EtagIdiom { NONE, IF_NONE_MATCH_STAR }

    private final DialClient dialClient;
    private final SessionBucketCache bucketCache;

    public CreateResourceTool(DialClient dialClient, SessionBucketCache bucketCache) {
        this.dialClient = dialClient;
        this.bucketCache = bucketCache;
    }

    public McpServerFeatures.AsyncToolSpecification spec() {
        Map<String, Object> boolProp = Map.of("type", "boolean", "default", false);
        McpSchema.JsonSchema input = new McpSchema.JsonSchema(
                "object",
                Map.of(
                        "id", Map.of("type", "string",
                                "description", "Canonical id '{type}/{bucket}/{name}'."),
                        "spec", Map.of("type", "string",
                                "description", "JSON-encoded entity body matching the type's schema."),
                        "validate_only", boolProp),
                List.of("id", "spec"),
                false, null, null);
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("dial_create_resource")
                .description("Create a new DIAL resource. Returns 409 if it already exists — "
                        + "call dial_update_resource instead. Set validate_only=true to dry-run via /v1/admin/validate.")
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
            return Mono.just(McpErrors.message("dial_create_resource does not support 'files'. "
                    + "Use dial_upload_file for file content (when available)."));
        }
        if ("settings".equals(parsed.type())) {
            return Mono.just(McpErrors.message("dial_create_resource does not support the 'settings' singleton. "
                    + "Settings is upserted via the REST API or by dial-cli."));
        }
        boolean validateOnly = Boolean.TRUE.equals(args.get("validate_only"));
        if (validateOnly && !ResourceId.TYPE_TO_KIND.containsKey(parsed.type())) {
            return Mono.just(McpErrors.message("validate_only is not supported for type '" + parsed.type()
                    + "'. Drop the validate_only flag and try again, or omit validate_only to write the resource directly."));
        }

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
            String envelope = buildValidateEnvelope(parsed.type(), parsed.name(), specNode);
            return resolvedBucket
                    .flatMap(bucket -> dialClient.request(HttpMethod.POST, "/v1/admin/validate", auth, Map.of(), envelope)
                            .map(resp -> shapeValidate(resp, parsed, bucket)))
                    .onErrorResume(t -> Mono.just(McpErrors.upstreamError(t)));
        }

        boolean resourceController = parsed.isResourceControllerType();
        HttpMethod method = resourceController ? HttpMethod.PUT : HttpMethod.POST;
        Map<String, String> correlation = resourceController
                ? Map.of("If-None-Match", "*")
                : Map.of();
        EtagIdiom idiom = resourceController ? EtagIdiom.IF_NONE_MATCH_STAR : EtagIdiom.NONE;

        return resolvedBucket
                .flatMap(bucket -> dialClient.request(method, parsed.toCorePath(bucket), auth, correlation, specStr)
                        .map(resp -> shape(resp, parsed, bucket, idiom)))
                .onErrorResume(t -> Mono.just(McpErrors.upstreamError(t)));
    }

    static String buildValidateEnvelope(String type, String name, JsonNode specNode) {
        ObjectNode envelope = McpJson.MAPPER.createObjectNode();
        ArrayNode manifests = envelope.putArray("manifests");
        ObjectNode entry = manifests.addObject();
        entry.put("kind", ResourceId.TYPE_TO_KIND.get(type));
        entry.put("name", name);
        entry.set("spec", specNode);
        envelope.put("precheck", true);
        return envelope.toString();
    }

    static McpSchema.CallToolResult shape(DialResponse resp, ResourceId id, String resolvedBucket, EtagIdiom idiom) {
        String canonical = id.type() + "/" + resolvedBucket + "/" + id.name();
        if (resp.statusCode() == 200 || resp.statusCode() == 201) {
            String etag = resp.headers() != null ? resp.headers().get("ETag") : null;
            ObjectNode result = McpJson.MAPPER.createObjectNode();
            result.put("created", true);
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
        if (resp.statusCode() == 409) {
            return McpErrors.conflictError(canonical);
        }
        if (resp.statusCode() == 412 && idiom == EtagIdiom.IF_NONE_MATCH_STAR) {
            return McpErrors.conflictError(canonical);
        }
        return McpErrors.httpError(resp.statusCode(), resp.body(),
                "Verify the spec against dial_describe_schema('" + id.type() + "') and that the caller has admin/write access.");
    }

    static McpSchema.CallToolResult shapeValidate(DialResponse resp, ResourceId id, String resolvedBucket) {
        String canonical = id.type() + "/" + resolvedBucket + "/" + id.name();
        if (resp.statusCode() != 200 && resp.statusCode() != 422) {
            return McpErrors.httpError(resp.statusCode(), resp.body(),
                    "validate_only call to /v1/admin/validate failed for '" + canonical + "'.");
        }
        try {
            JsonNode body = McpJson.MAPPER.readTree(resp.body());
            JsonNode results = body.path("results");
            JsonNode entry = results.isArray() && !results.isEmpty() ? results.get(0) : McpJson.MAPPER.nullNode();
            String status = entry.path("status").asText("");
            boolean ok = "valid".equals(status);
            if (resp.statusCode() == 422 || !ok) {
                String error = entry.path("error").asText("validation failed");
                return McpErrors.httpError(422, error,
                        "validate_only rejected the spec for '" + canonical + "'. Adjust the spec and retry.");
            }
            ObjectNode envelope = McpJson.MAPPER.createObjectNode();
            envelope.put("validated", true);
            envelope.put("id", canonical);
            envelope.set("results", McpJson.MAPPER.createArrayNode().add(entry.deepCopy()));
            return McpSchema.CallToolResult.builder()
                    .content(List.of(new McpSchema.TextContent(envelope.toString())))
                    .isError(false)
                    .build();
        } catch (Exception e) {
            return McpErrors.upstreamError(e);
        }
    }
}
