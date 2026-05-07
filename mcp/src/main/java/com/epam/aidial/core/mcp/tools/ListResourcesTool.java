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
import java.util.Set;

/**
 * {@code dial_list_resources(path, recursive?, filter?, format?, cursor?)} — spec 09 §6.1 tool 2,
 * §6.3 (two-array envelope), §6.4 (summary projection). M.1.0 pilot covers {@code models},
 * {@code roles}, {@code settings} (settings list short-circuits with a 405-style envelope).
 */
public final class ListResourcesTool {

    private static final Map<String, List<String>> SUMMARY_FIELDS = Map.of(
            "models", List.of("displayName", "displayVersion", "status", "description"),
            "roles", List.of("status", "description"));

    private static final Set<String> RESERVED_KEYS = Set.of("kind", "id", "name", "etag");

    private final DialClient dialClient;
    private final SessionBucketCache bucketCache;

    public ListResourcesTool(DialClient dialClient, SessionBucketCache bucketCache) {
        this.dialClient = dialClient;
        this.bucketCache = bucketCache;
    }

    public McpServerFeatures.AsyncToolSpecification spec() {
        Map<String, Object> stringProp = Map.of("type", "string");
        Map<String, Object> boolProp = Map.of("type", "boolean", "default", false);
        Map<String, Object> formatProp = Map.of(
                "type", "string",
                "enum", List.of("summary", "detailed"),
                "default", "summary");
        McpSchema.JsonSchema input = new McpSchema.JsonSchema(
                "object",
                Map.of(
                        "path", Map.of("type", "string",
                                "description", "{type}/{bucket}/[subpath/]. M.1.0 pilot: 'models/public/', 'roles/platform/'."),
                        "recursive", boolProp,
                        "filter", stringProp,
                        "format", formatProp,
                        "cursor", stringProp),
                List.of("path"),
                false, null, null);
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("dial_list_resources")
                .description("Lists DIAL resources under the given path. "
                        + "Returns a two-array envelope (items + folders). "
                        + "Example: {\"path\":\"models/public/\"}.")
                .inputSchema(input)
                .build();
        return McpServerFeatures.AsyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handle)
                .build();
    }

    private Mono<McpSchema.CallToolResult> handle(McpAsyncServerExchange exchange, McpSchema.CallToolRequest request) {
        Map<String, Object> args = request.arguments();
        Object pathArg = args == null ? null : args.get("path");
        if (!(pathArg instanceof String path) || path.isBlank()) {
            return Mono.just(McpErrors.message("'path' argument is required."));
        }

        ResourceId parsed;
        try {
            parsed = ResourceId.parseListPath(path);
        } catch (IllegalArgumentException e) {
            return Mono.just(McpErrors.message(e.getMessage()));
        }
        if ("settings".equals(parsed.type())) {
            return Mono.just(McpErrors.settingsListNotAllowed());
        }
        if (!ResourceId.PILOT_TYPES.contains(parsed.type())) {
            return Mono.just(McpErrors.unknownType(parsed.type()));
        }

        String format = args != null && args.get("format") instanceof String s ? s : "summary";
        Map<String, String> auth = ToolContext.authHeaders(exchange);

        Mono<String> resolvedBucket = "private".equals(parsed.bucket())
                ? bucketCache.resolvePrivate(ToolContext.sessionId(exchange), auth)
                : Mono.just(parsed.bucket());

        return resolvedBucket
                .flatMap(bucket -> dialClient.request(HttpMethod.GET,
                        parsed.toListCorePath(bucket), auth, Map.of(), null)
                        .map(resp -> shape(resp, parsed.type(), parsed.bucket(), bucket, format)))
                .onErrorResume(t -> Mono.just(McpErrors.upstreamError(t)));
    }

    static McpSchema.CallToolResult shape(DialResponse resp, String type, String pathBucket,
                                          String resolvedBucket, String format) {
        if (resp.statusCode() != 200) {
            return McpErrors.httpError(resp.statusCode(), resp.body(),
                    "Verify path '" + type + "/" + pathBucket + "/' is reachable for the caller.");
        }
        try {
            JsonNode root = McpJson.MAPPER.readTree(resp.body());
            ArrayNode items = McpJson.MAPPER.createArrayNode();
            JsonNode coreItems = root.get("items");
            if (coreItems != null && coreItems.isArray()) {
                for (JsonNode item : coreItems) {
                    items.add(projectItem(item, type, resolvedBucket, format));
                }
            }
            boolean hasMore = root.has("hasMore") && root.get("hasMore").asBoolean(false);

            ObjectNode envelope = McpJson.MAPPER.createObjectNode();
            envelope.put("path", type + "/" + pathBucket + "/");
            envelope.set("items", items);
            envelope.set("folders", McpJson.MAPPER.createArrayNode());
            envelope.putNull("nextCursor");
            envelope.put("hasMore", hasMore);
            envelope.put("truncated", false);
            envelope.putNull("truncation_reason");

            return McpSchema.CallToolResult.builder()
                    .content(List.of(new McpSchema.TextContent(envelope.toString())))
                    .isError(false)
                    .build();
        } catch (Exception e) {
            return McpErrors.upstreamError(e);
        }
    }

    private static ObjectNode projectItem(JsonNode item, String type, String bucket, String format) {
        ObjectNode result = McpJson.MAPPER.createObjectNode();
        String name = item.has("name") ? item.get("name").asText() : "";
        result.put("kind", "resource");
        result.put("id", type + "/" + bucket + "/" + name);
        result.put("name", name);
        result.putNull("etag");
        if ("detailed".equals(format)) {
            item.properties().forEach(entry -> {
                if (!RESERVED_KEYS.contains(entry.getKey())) {
                    result.set(entry.getKey(), entry.getValue());
                }
            });
        } else {
            List<String> fields = SUMMARY_FIELDS.getOrDefault(type, List.of());
            for (String field : fields) {
                if (item.has(field)) {
                    result.set(field, item.get(field));
                }
            }
        }
        return result;
    }
}
