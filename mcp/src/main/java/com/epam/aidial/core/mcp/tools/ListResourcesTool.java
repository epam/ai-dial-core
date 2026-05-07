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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code dial_list_resources(path, recursive?, filter?, format?, cursor?)} — spec 09 §6.1 tool 2,
 * §6.3 (two-array envelope), §6.4 (summary projection). Covers all 12 spec types; {@code settings}
 * 405-short-circuits to {@code dial_get_resource(id='settings/platform/global')}.
 *
 * <p>Two upstream list shapes are unified into the agent-facing envelope:
 * <ul>
 *     <li>Config types (config-resource controller, M9 §M9): {@code {entityType, bucket, items[],
 *         hasMore}} — flat, single page, no folders. Items contain entity body fields directly.</li>
 *     <li>Metadata types ({@code applications, toolsets, files, prompts, conversations} via
 *         {@code /v1/metadata/...}): {@code ResourceFolderMetadata} {@code {nodeType: FOLDER,
 *         items[<MetadataBase>], nextToken}} — items discriminated by {@code nodeType: ITEM|FOLDER}
 *         carry only metadata fields ({@code name, parentPath, bucket, url, ...}); body-derived
 *         summary fields (e.g. {@code displayName}) are absent and silently no-op the projection.</li>
 * </ul>
 */
public final class ListResourcesTool {

    private static final Map<String, List<String>> SUMMARY_FIELDS = Map.ofEntries(
            Map.entry("models", List.of("displayName", "displayVersion", "status", "description")),
            Map.entry("applications", List.of("displayName", "status", "description")),
            Map.entry("toolsets", List.of("displayName", "status", "description")),
            Map.entry("interceptors", List.of("displayName", "status", "description")),
            Map.entry("roles", List.of("status", "description")),
            Map.entry("keys", List.of("role", "status", "description")),
            Map.entry("routes", List.of("paths", "methods", "status", "description")),
            Map.entry("schemas", List.of("displayName", "status", "description")),
            Map.entry("files", List.of("contentType", "size", "description")),
            Map.entry("prompts", List.of("displayName", "description")),
            Map.entry("conversations", List.of("displayName", "description")));

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
                                "description", "{type}/{bucket}/[subpath/]. Examples: 'models/public/', "
                                        + "'roles/platform/', 'files/<bucket>/photos/'."),
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
                        + "Hierarchical types (files, prompts, conversations) populate folders[] with sub-prefixes; "
                        + "flat types return folders=[]. "
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
        if (!ResourceId.KNOWN_TYPES.contains(parsed.type())) {
            return Mono.just(McpErrors.unknownType(parsed.type()));
        }

        boolean recursive = args != null && Boolean.TRUE.equals(args.get("recursive"));
        if (recursive && !parsed.supportsRecursive()) {
            return Mono.just(McpErrors.recursiveNotSupported(parsed.type()));
        }
        String cursor = args != null && args.get("cursor") instanceof String c && !c.isBlank() ? c : null;
        if (cursor != null && !parsed.supportsRecursive()) {
            return Mono.just(McpErrors.cursorNotSupported(parsed.type()));
        }
        String format = args != null && args.get("format") instanceof String s ? s : "summary";
        Map<String, String> auth = ToolContext.authHeaders(exchange);

        Mono<String> resolvedBucket = "private".equals(parsed.bucket())
                ? bucketCache.resolvePrivate(ToolContext.sessionId(exchange), auth)
                : Mono.just(parsed.bucket());

        return resolvedBucket
                .flatMap(bucket -> dialClient.request(HttpMethod.GET,
                        appendQuery(parsed.toListCorePath(bucket), parsed.supportsRecursive(), recursive, cursor),
                        auth, Map.of(), null)
                        .map(resp -> shape(resp, parsed, bucket, format)))
                .onErrorResume(t -> Mono.just(McpErrors.upstreamError(t)));
    }

    private static String appendQuery(String basePath, boolean metadataList, boolean recursive, String cursor) {
        if (!metadataList || (!recursive && cursor == null)) {
            return basePath;
        }
        StringBuilder sb = new StringBuilder(basePath);
        char sep = '?';
        if (recursive) {
            sb.append(sep).append("recursive=true");
            sep = '&';
        }
        if (cursor != null) {
            sb.append(sep).append("token=").append(URLEncoder.encode(cursor, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    static McpSchema.CallToolResult shape(DialResponse resp, ResourceId parsed, String resolvedBucket, String format) {
        String type = parsed.type();
        String subPath = parsed.name();
        if (resp.statusCode() != 200) {
            return McpErrors.httpError(resp.statusCode(), resp.body(),
                    "Verify path '" + type + "/" + parsed.bucket() + "/" + subPath + "' is reachable for the caller.");
        }
        try {
            JsonNode root = McpJson.MAPPER.readTree(resp.body());
            ArrayNode items = McpJson.MAPPER.createArrayNode();
            ArrayNode folders = McpJson.MAPPER.createArrayNode();
            JsonNode coreItems = root.get("items");
            if (coreItems != null && coreItems.isArray()) {
                for (JsonNode item : coreItems) {
                    if (isFolderNode(item)) {
                        folders.add(projectFolder(item, type, resolvedBucket));
                    } else {
                        items.add(projectItem(item, type, resolvedBucket, format));
                    }
                }
            }
            String nextCursor = root.path("nextToken").isMissingNode() || root.path("nextToken").isNull()
                    ? null
                    : root.path("nextToken").asText();
            boolean hasMore = root.has("hasMore")
                    ? root.get("hasMore").asBoolean(false)
                    : nextCursor != null;

            ObjectNode envelope = McpJson.MAPPER.createObjectNode();
            envelope.put("path", type + "/" + resolvedBucket + "/" + subPath);
            envelope.set("items", items);
            envelope.set("folders", folders);
            if (nextCursor == null) {
                envelope.putNull("nextCursor");
            } else {
                envelope.put("nextCursor", nextCursor);
            }
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

    private static boolean isFolderNode(JsonNode item) {
        return "FOLDER".equals(item.path("nodeType").asText());
    }

    private static String parentPath(JsonNode item) {
        return item.path("parentPath").asText("");
    }

    private static ObjectNode projectFolder(JsonNode item, String type, String bucket) {
        ObjectNode folder = McpJson.MAPPER.createObjectNode();
        String name = item.path("name").asText("");
        String parent = parentPath(item);
        StringBuilder path = new StringBuilder(type).append('/').append(bucket).append('/');
        if (!parent.isEmpty()) {
            path.append(parent).append('/');
        }
        path.append(name).append('/');
        folder.put("kind", "folder");
        folder.put("path", path.toString());
        folder.put("name", name);
        return folder;
    }

    private static ObjectNode projectItem(JsonNode item, String type, String bucket, String format) {
        ObjectNode result = McpJson.MAPPER.createObjectNode();
        String name = item.path("name").asText("");
        String parent = parentPath(item);
        String idPath = parent.isEmpty() ? name : parent + "/" + name;
        result.put("kind", "resource");
        result.put("id", type + "/" + bucket + "/" + idPath);
        result.put("name", name);
        result.putNull("etag");
        if ("detailed".equals(format)) {
            item.properties().forEach(entry -> {
                if (!RESERVED_KEYS.contains(entry.getKey())) {
                    result.set(entry.getKey(), entry.getValue());
                }
            });
        } else {
            for (String field : SUMMARY_FIELDS.getOrDefault(type, List.of())) {
                if (item.has(field)) {
                    result.set(field, item.get(field));
                }
            }
        }
        return result;
    }

    /** Test seam — read-only view of the spec §6.4 projection table. */
    static List<String> summaryFields(String type) {
        return SUMMARY_FIELDS.getOrDefault(type, List.of());
    }
}
