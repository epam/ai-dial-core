package com.epam.aidial.core.mcp.client;

import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.multipart.MultipartForm;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Loopback HTTP wrapper for Core's REST surface. Per spec 09 §7.1, MCP talks to Core only via the
 * public REST API even when in-process; this class is the single swap point if MCP is later extracted.
 *
 * <p>Threading: tool handlers run on Reactor scheduler threads (SDK contract), but {@code WebClient}
 * requires an active Vert.x context. The {@code Mono.create + runOnContext + onComplete} shape
 * re-enters the captured verticle context before the HTTP call, so the bridge is encapsulated here
 * and tool handlers never touch {@code runOnContext} directly. See spec 09 §7.2 (option a).
 */
@Slf4j
public class DialClient {

    private final Context vertxContext;
    private final String targetUrl;
    private final WebClient webClient;

    public DialClient(Vertx vertx, Context vertxContext, String targetUrl) {
        this.vertxContext = vertxContext;
        this.targetUrl = stripTrailingSlash(targetUrl);
        this.webClient = WebClient.create(vertx);
    }

    public Mono<DialResponse> request(HttpMethod method,
                                      String path,
                                      Map<String, String> authHeaders,
                                      Map<String, String> correlationHeaders,
                                      String body) {
        String fullUrl = targetUrl + path;
        Buffer bodyBuffer = body != null ? Buffer.buffer(body) : null;
        return Mono.create(sink -> vertxContext.runOnContext(v -> {
            HttpRequest<Buffer> req = buildRequest(method, fullUrl, authHeaders, correlationHeaders);
            (bodyBuffer != null ? req.sendBuffer(bodyBuffer) : req.send())
                    .onSuccess(resp -> {
                        String responseBody = resp.bodyAsString() != null ? resp.bodyAsString() : "";
                        sink.success(new DialResponse(resp.statusCode(), responseBody, resp.headers()));
                    })
                    .onFailure(sink::error);
        }));
    }

    /**
     * Multipart variant — Core's file-upload controller requires {@code multipart/form-data}; the
     * part's own {@code Content-Type} (set on the {@link MultipartForm}) carries the blob's MIME.
     * Response body is captured as a String — the upload controller returns JSON metadata.
     */
    public Mono<DialResponse> requestMultipart(HttpMethod method,
                                               String path,
                                               Map<String, String> authHeaders,
                                               Map<String, String> correlationHeaders,
                                               MultipartForm form) {
        String fullUrl = targetUrl + path;
        return Mono.create(sink -> vertxContext.runOnContext(v -> {
            HttpRequest<Buffer> req = buildRequest(method, fullUrl, authHeaders, correlationHeaders);
            req.sendMultipartForm(form)
                    .onSuccess(resp -> {
                        String responseBody = resp.bodyAsString() != null ? resp.bodyAsString() : "";
                        sink.success(new DialResponse(resp.statusCode(), responseBody, resp.headers()));
                    })
                    .onFailure(sink::error);
        }));
    }

    /**
     * Binary-response variant — the file-download controller streams raw bytes; capturing as
     * {@code String} would mangle non-UTF-8 content. {@code body} may be {@code null} for GET.
     */
    public Mono<DialBinaryResponse> requestBinary(HttpMethod method,
                                                  String path,
                                                  Map<String, String> authHeaders,
                                                  Map<String, String> correlationHeaders,
                                                  String body) {
        String fullUrl = targetUrl + path;
        Buffer bodyBuffer = body != null ? Buffer.buffer(body) : null;
        return Mono.create(sink -> vertxContext.runOnContext(v -> {
            HttpRequest<Buffer> req = buildRequest(method, fullUrl, authHeaders, correlationHeaders);
            (bodyBuffer != null ? req.sendBuffer(bodyBuffer) : req.send())
                    .onSuccess(resp -> {
                        Buffer buf = resp.body();
                        byte[] bytes = buf != null ? buf.getBytes() : new byte[0];
                        sink.success(new DialBinaryResponse(resp.statusCode(), bytes, resp.headers()));
                    })
                    .onFailure(sink::error);
        }));
    }

    private HttpRequest<Buffer> buildRequest(HttpMethod method,
                                             String fullUrl,
                                             Map<String, String> authHeaders,
                                             Map<String, String> correlationHeaders) {
        HttpRequest<Buffer> req = webClient.requestAbs(method, fullUrl);
        if (authHeaders != null) {
            authHeaders.forEach(req::putHeader);
        }
        if (correlationHeaders != null) {
            correlationHeaders.forEach(req::putHeader);
        }
        return req;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
