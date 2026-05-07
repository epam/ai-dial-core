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
 * {@code dial_publish_resource(id, target)} — spec 09 §6.1 tool 9, §3.2 illustrative composition.
 * Wraps a single-resource ADD manifest and POSTs to {@code /v1/ops/publication/create}; the
 * publication enters PENDING and requires admin approval before the resource appears publicly.
 */
public final class PublishResourceTool {

    private static final String PUBLIC_PREFIX = "public/";

    private final DialClient dialClient;
    private final SessionBucketCache bucketCache;

    public PublishResourceTool(DialClient dialClient, SessionBucketCache bucketCache) {
        this.dialClient = dialClient;
        this.bucketCache = bucketCache;
    }

    public McpServerFeatures.AsyncToolSpecification spec() {
        McpSchema.JsonSchema input = new McpSchema.JsonSchema(
                "object",
                Map.of(
                        "id", Map.of("type", "string",
                                "description",
                                "Source canonical id '{type}/{bucket}/{name}'."
                                        + " Bucket aliases ('private', 'public', 'platform') accepted."),
                        "target", Map.of("type", "string",
                                "description",
                                "Target folder under public/, trailing slash required (e.g. 'public/conversations/').")),
                List.of("id", "target"),
                false, null, null);
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("dial_publish_resource")
                .description("Initiate a publication request for a resource. Returns PENDING; an admin must approve "
                        + "before it appears publicly. Example: dial_publish_resource(id='conversations/private/folder/c1', "
                        + "target='public/conversations/').")
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
        Object targetArg = args.get("target");
        if (!(targetArg instanceof String target) || target.isBlank()) {
            return Mono.just(McpErrors.message("'target' argument is required (e.g. 'public/conversations/')."));
        }
        // Preflight matches Core's PublicationService validation. Pre-checking yields a cheaper,
        // MCP-shaped error than the post-network IllegalArgumentException Core would throw.
        if (!target.startsWith(PUBLIC_PREFIX)) {
            return Mono.just(McpErrors.message("'target' must start with 'public/' (got '" + target + "')."));
        }
        if (!target.endsWith("/")) {
            return Mono.just(McpErrors.message("'target' must end with '/' (got '" + target + "')."));
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
                .flatMap(bucket -> dialClient
                        .request(HttpMethod.POST, "/v1/ops/publication/create", auth, Map.of(),
                                buildPublicationBody(parsed, bucket, target))
                        .map(resp -> shape(resp, parsed, bucket, target)))
                .onErrorResume(t -> Mono.just(McpErrors.upstreamError(t)));
    }

    static String buildPublicationBody(ResourceId parsed, String resolvedBucket, String target) {
        String sourceUrl = parsed.type() + "/" + resolvedBucket + "/" + parsed.name();
        String targetUrl = parsed.type() + "/" + target + parsed.leafName();
        ObjectNode body = McpJson.MAPPER.createObjectNode();
        body.put("targetFolder", target);
        ArrayNode resources = body.putArray("resources");
        ObjectNode entry = resources.addObject();
        entry.put("action", "ADD");
        entry.put("sourceUrl", sourceUrl);
        entry.put("targetUrl", targetUrl);
        return body.toString();
    }

    static McpSchema.CallToolResult shape(DialResponse resp, ResourceId id, String resolvedBucket, String target) {
        String canonical = id.type() + "/" + resolvedBucket + "/" + id.name();
        if (resp.statusCode() == 200) {
            ObjectNode envelope = McpJson.MAPPER.createObjectNode();
            envelope.put("published", true);
            envelope.put("id", canonical);
            try {
                JsonNode publication = McpJson.MAPPER.readTree(resp.body());
                envelope.set("publication", publication);
            } catch (Exception e) {
                return McpErrors.upstreamError(e);
            }
            return McpSchema.CallToolResult.builder()
                    .content(List.of(new McpSchema.TextContent(envelope.toString())))
                    .isError(false)
                    .build();
        }
        if (resp.statusCode() == 404) {
            return McpErrors.httpError(404, resp.body(),
                    "Source resource '" + canonical + "' not found. "
                            + "Call dial_get_resource to verify the id.");
        }
        if (resp.statusCode() == 403) {
            return McpErrors.httpError(403, resp.body(),
                    "Permission denied publishing '" + canonical + "' to '" + target + "'. "
                            + "Caller needs read access to the source and write access to the target prefix.");
        }
        if (resp.statusCode() == 400) {
            return McpErrors.httpError(400, resp.body(),
                    "Publication request rejected. Verify the source exists and target shape — "
                            + "type-prefix-matched folder under 'public/' with trailing slash.");
        }
        return McpErrors.httpError(resp.statusCode(), resp.body(),
                "Publication of '" + canonical + "' failed.");
    }
}
