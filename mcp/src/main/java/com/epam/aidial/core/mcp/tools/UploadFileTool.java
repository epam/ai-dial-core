package com.epam.aidial.core.mcp.tools;

import com.epam.aidial.core.mcp.client.DialClient;
import com.epam.aidial.core.mcp.client.DialResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.vertx.core.Context;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.multipart.MultipartForm;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * {@code dial_upload_file(id, content | source_url, content_type?, max_bytes?)} tool. Routes
 * to multipart {@code PUT /v1/files/{bucket}/{name}} via {@link DialClient#requestMultipart}.
 * {@code content} XOR {@code source_url} is enforced in this handler — the typed
 * {@code JsonSchema} record has no {@code oneOf} slot, so the schema documents the constraint
 * and the handler rejects both/neither.
 *
 * <p>{@code source_url} is gated by {@link SourceUrlGuard} (default-deny, allow-list, CIDR
 * blocklist) and fetched through a separate {@link WebClient} configured to follow no redirects
 * — so a redirect chain cannot re-target a blocked IP after the guard's DNS check. The fetcher
 * is entered through {@link Context#runOnContext} because Vert.x {@code WebClient} requires the
 * verticle context, and the {@code source_url} validate step runs on
 * {@link Schedulers#boundedElastic()} because it does synchronous DNS resolution.
 */
public final class UploadFileTool {

    private static final long REQUEST_TIMEOUT_MS = 10_000L;

    private final DialClient dialClient;
    private final WebClient externalFetcher;
    private final Context vertxContext;
    private final SessionBucketCache bucketCache;
    private final SourceUrlGuard sourceUrlGuard;
    private final long defaultMaxBytes;

    public UploadFileTool(DialClient dialClient,
                          WebClient externalFetcher,
                          Context vertxContext,
                          SessionBucketCache bucketCache,
                          SourceUrlGuard sourceUrlGuard,
                          long defaultMaxBytes) {
        this.dialClient = dialClient;
        this.externalFetcher = externalFetcher;
        this.vertxContext = vertxContext;
        this.bucketCache = bucketCache;
        this.sourceUrlGuard = sourceUrlGuard;
        this.defaultMaxBytes = defaultMaxBytes;
    }

    public McpServerFeatures.AsyncToolSpecification spec() {
        McpSchema.JsonSchema input = new McpSchema.JsonSchema(
                "object",
                Map.of(
                        "id", Map.of("type", "string",
                                "description", "Canonical id 'files/{bucket}/{path}'."),
                        "content", Map.of("type", "string",
                                "description", "Base64-encoded file bytes. Mutually exclusive with source_url."),
                        "source_url", Map.of("type", "string",
                                "description", "URL fetched server-side. Mutually exclusive with content. "
                                        + "Disabled by default — operator must opt in via mcp.upload.sourceUrl.enabled."),
                        "content_type", Map.of("type", "string",
                                "description", "MIME type. Inferred from source_url response or filename if omitted."),
                        "max_bytes", Map.of("type", "integer", "minimum", 1,
                                "description", "Upper bound on accepted payload size; defaults to the operator-configured ceiling.")),
                List.of("id"),
                false, null, null);
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("dial_upload_file")
                .description("Upload a file to a DIAL bucket. Provide exactly one of 'content' (base64-encoded "
                        + "binary) or 'source_url' (URL fetched by the MCP server). Returns the persisted file "
                        + "metadata with its ETag.")
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
            return Mono.just(McpErrors.message("dial_upload_file only accepts type 'files'. "
                    + "Use dial_create_resource for other types."));
        }
        Object contentArg = args.get("content");
        Object sourceUrlArg = args.get("source_url");
        boolean hasContent = contentArg instanceof String s && !s.isBlank();
        boolean hasSourceUrl = sourceUrlArg instanceof String s && !s.isBlank();
        if (hasContent == hasSourceUrl) {
            return Mono.just(McpErrors.message("Provide exactly one of 'content' (base64) or 'source_url'. "
                    + (hasContent ? "Both were provided — drop one." : "Neither was provided — pass one.")));
        }
        long maxBytes = parseMaxBytes(args.get("max_bytes"));
        String contentTypeArg = args.get("content_type") instanceof String s && !s.isBlank() ? s : null;

        Map<String, String> auth = ToolContext.authHeaders(exchange);
        Mono<String> resolvedBucket = "private".equals(parsed.bucket())
                ? bucketCache.resolvePrivate(ToolContext.sessionId(exchange), auth)
                : Mono.just(parsed.bucket());

        Mono<FetchedPayload> payload = hasContent
                ? decodeBase64((String) contentArg, maxBytes, contentTypeArg)
                : fetchSourceUrl((String) sourceUrlArg, maxBytes, contentTypeArg);

        return Mono.zip(resolvedBucket, payload)
                .flatMap(tuple -> {
                    String bucket = tuple.getT1();
                    FetchedPayload p = tuple.getT2();
                    MultipartForm form = MultipartForm.create()
                            .binaryFileUpload("attachment", parsed.leafName(), Buffer.buffer(p.bytes), p.contentType);
                    return dialClient.requestMultipart(HttpMethod.PUT, parsed.toMutationCorePath(bucket),
                                    auth, Map.of(), form)
                            .map(resp -> shape(resp, parsed, bucket, p));
                })
                .onErrorResume(t -> Mono.just(toErrorResult(t)));
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

    private Mono<FetchedPayload> decodeBase64(String content, long maxBytes, String contentTypeArg) {
        return Mono.fromCallable(() -> {
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(content);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("'content' is not valid base64: " + e.getMessage());
            }
            if (bytes.length > maxBytes) {
                throw new IllegalArgumentException("'content' (" + bytes.length + " bytes) exceeds max_bytes (" + maxBytes + ").");
            }
            return new FetchedPayload(bytes, contentTypeArg != null ? contentTypeArg : "application/octet-stream");
        });
    }

    private Mono<FetchedPayload> fetchSourceUrl(String rawUrl, long maxBytes, String contentTypeArg) {
        return Mono.fromCallable(() -> sourceUrlGuard.validate(rawUrl))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(uri -> Mono.<FetchedPayload>create(sink -> vertxContext.runOnContext(v -> externalFetcher
                        .requestAbs(HttpMethod.GET, uri.toString())
                        .timeout(REQUEST_TIMEOUT_MS)
                        .send()
                        .onSuccess(resp -> {
                            if (resp.statusCode() / 100 != 2) {
                                sink.error(new IllegalArgumentException("source_url fetch returned HTTP "
                                        + resp.statusCode()));
                                return;
                            }
                            String declaredLen = resp.getHeader("Content-Length");
                            if (declaredLen != null) {
                                try {
                                    long len = Long.parseLong(declaredLen);
                                    if (len > maxBytes) {
                                        sink.error(new IllegalArgumentException("source_url Content-Length (" + len
                                                + " bytes) exceeds max_bytes (" + maxBytes + ")."));
                                        return;
                                    }
                                } catch (NumberFormatException ignored) {
                                    // unparseable header; rely on the post-buffer length check below
                                }
                            }
                            Buffer buf = resp.body();
                            byte[] bytes = buf != null ? buf.getBytes() : new byte[0];
                            if (bytes.length > maxBytes) {
                                sink.error(new IllegalArgumentException("source_url payload (" + bytes.length
                                        + " bytes) exceeds max_bytes (" + maxBytes + ")."));
                                return;
                            }
                            String mime = contentTypeArg != null ? contentTypeArg : resp.getHeader("Content-Type");
                            if (mime == null || mime.isBlank()) {
                                mime = "application/octet-stream";
                            }
                            sink.success(new FetchedPayload(bytes, mime));
                        })
                        .onFailure(sink::error))));
    }

    private static McpSchema.CallToolResult shape(DialResponse resp, ResourceId id, String resolvedBucket,
                                                  FetchedPayload payload) {
        String canonical = id.type() + "/" + resolvedBucket + "/" + id.name();
        if (resp.statusCode() == 200) {
            String etag = resp.headers() != null ? resp.headers().get("ETag") : null;
            ObjectNode result = McpJson.MAPPER.createObjectNode();
            result.put("uploaded", true);
            result.put("id", canonical);
            result.put("name", id.name());
            result.put("content_type", payload.contentType);
            result.put("size", payload.bytes.length);
            if (etag == null) {
                JsonNode body = parseOrNull(resp.body());
                if (body != null && body.hasNonNull("etag")) {
                    result.put("etag", body.get("etag").asText());
                } else {
                    result.putNull("etag");
                }
            } else {
                result.put("etag", etag);
            }
            return McpSchema.CallToolResult.builder()
                    .content(List.of(new McpSchema.TextContent(result.toString())))
                    .isError(false)
                    .build();
        }
        return McpErrors.httpError(resp.statusCode(), resp.body(),
                "Verify '" + canonical + "' write access and that the bucket exists.");
    }

    private static JsonNode parseOrNull(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return McpJson.MAPPER.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    private static McpSchema.CallToolResult toErrorResult(Throwable t) {
        if (t instanceof IllegalArgumentException) {
            return McpErrors.message(t.getMessage());
        }
        return McpErrors.upstreamError(t);
    }

    private record FetchedPayload(byte[] bytes, String contentType) {
    }
}
