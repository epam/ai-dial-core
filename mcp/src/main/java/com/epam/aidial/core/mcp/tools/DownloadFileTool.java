package com.epam.aidial.core.mcp.tools;

import com.epam.aidial.core.mcp.client.DialBinaryResponse;
import com.epam.aidial.core.mcp.client.DialClient;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpMethod;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code dial_download_file(id, max_bytes?, format?)} tool. Routes to {@code GET /v1/files/...}
 * via {@link DialClient#requestBinary} (the metadata-GET route returns JSON, not bytes). Default
 * response is a base64-encoded JSON envelope; passing {@code format=image} on an {@code image/*}
 * MIME wraps the bytes in an {@link McpSchema.ImageContent} block.
 */
public final class DownloadFileTool {

    private final DialClient dialClient;
    private final SessionBucketCache bucketCache;
    private final long defaultMaxBytes;

    public DownloadFileTool(DialClient dialClient, SessionBucketCache bucketCache, long defaultMaxBytes) {
        this.dialClient = dialClient;
        this.bucketCache = bucketCache;
        this.defaultMaxBytes = defaultMaxBytes;
    }

    public McpServerFeatures.AsyncToolSpecification spec() {
        McpSchema.JsonSchema input = new McpSchema.JsonSchema(
                "object",
                Map.of(
                        "id", Map.of("type", "string",
                                "description", "Canonical id 'files/{bucket}/{path}'."),
                        "max_bytes", Map.of("type", "integer", "minimum", 1,
                                "description", "Upper bound on response size (bytes). Default 10 MB."),
                        "format", Map.of("type", "string", "enum", List.of("bytes", "image"),
                                "description", "Response shape; 'image' returns an MCP image-content block "
                                        + "when the file's MIME is image/*.")),
                List.of("id"),
                false, null, null);
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("dial_download_file")
                .description("Download a file from a DIAL bucket. Default returns a base64-encoded JSON "
                        + "envelope; format='image' wraps image/* bytes in an MCP image-content block. "
                        + "Use dial_get_resource for metadata only.")
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
        if (!"files".equals(parsed.type())) {
            return Mono.just(McpErrors.message("dial_download_file only accepts type 'files'. "
                    + "Use dial_get_resource for other types."));
        }
        long maxBytes = parseMaxBytes(args.get("max_bytes"));
        String format = args.get("format") instanceof String s ? s : "bytes";

        Map<String, String> auth = ToolContext.authHeaders(exchange);
        Mono<String> resolvedBucket = "private".equals(parsed.bucket())
                ? bucketCache.resolvePrivate(ToolContext.sessionId(exchange), auth)
                : Mono.just(parsed.bucket());

        return resolvedBucket
                .flatMap(bucket -> dialClient.requestBinary(HttpMethod.GET, parsed.toMutationCorePath(bucket),
                                auth, Map.of(), null)
                        .map(resp -> shape(resp, parsed, bucket, maxBytes, format)))
                .onErrorResume(t -> Mono.just(McpErrors.upstreamError(t)));
    }

    private long parseMaxBytes(Object raw) {
        if (raw instanceof Number n) {
            long v = n.longValue();
            if (v > 0) {
                return Math.min(v, defaultMaxBytes);
            }
        }
        return defaultMaxBytes;
    }

    private static McpSchema.CallToolResult shape(DialBinaryResponse resp, ResourceId id, String resolvedBucket,
                                                  long maxBytes, String format) {
        String canonical = id.type() + "/" + resolvedBucket + "/" + id.name();
        if (resp.statusCode() == 404) {
            return McpErrors.notFoundError(canonical);
        }
        byte[] bytes = resp.body() != null ? resp.body() : new byte[0];
        if (resp.statusCode() != 200) {
            return McpErrors.httpError(resp.statusCode(), new String(bytes, StandardCharsets.UTF_8),
                    "Verify '" + canonical + "' exists and the caller has read access.");
        }
        if (bytes.length > maxBytes) {
            return McpErrors.message("File '" + canonical + "' (" + bytes.length + " bytes) exceeds max_bytes ("
                    + maxBytes + "). Increase max_bytes, or call dial_get_resource for metadata only.");
        }
        MultiMap headers = resp.headers();
        String mime = headers != null ? headers.get("Content-Type") : null;
        if (mime == null || mime.isBlank()) {
            mime = "application/octet-stream";
        }
        String etag = headers != null ? headers.get("ETag") : null;
        String base64 = Base64.getEncoder().encodeToString(bytes);

        if ("image".equals(format) && mime.toLowerCase(Locale.ROOT).startsWith("image/")) {
            return McpSchema.CallToolResult.builder()
                    .content(List.of(new McpSchema.ImageContent(null, base64, mime)))
                    .isError(false)
                    .build();
        }

        ObjectNode envelope = McpJson.MAPPER.createObjectNode();
        envelope.put("downloaded", true);
        envelope.put("id", canonical);
        envelope.put("name", id.name());
        envelope.put("content_type", mime);
        envelope.put("size", bytes.length);
        if (etag == null) {
            envelope.putNull("etag");
        } else {
            envelope.put("etag", etag);
        }
        envelope.put("content_base64", base64);
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(envelope.toString())))
                .isError(false)
                .build();
    }
}
