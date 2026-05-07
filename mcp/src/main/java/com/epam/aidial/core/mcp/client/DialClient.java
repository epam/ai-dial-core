package com.epam.aidial.core.mcp.client;

import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
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
            HttpRequest<Buffer> req = webClient.requestAbs(method, fullUrl);
            if (authHeaders != null) {
                authHeaders.forEach(req::putHeader);
            }
            if (correlationHeaders != null) {
                correlationHeaders.forEach(req::putHeader);
            }
            (bodyBuffer != null ? req.sendBuffer(bodyBuffer) : req.send())
                    .onSuccess(resp -> {
                        String responseBody = resp.bodyAsString() != null ? resp.bodyAsString() : "";
                        sink.success(new DialResponse(resp.statusCode(), responseBody, resp.headers()));
                    })
                    .onFailure(sink::error);
        }));
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
