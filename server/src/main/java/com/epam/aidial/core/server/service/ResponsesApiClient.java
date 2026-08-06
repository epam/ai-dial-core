package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.UpstreamExtraDataMerger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;

import javax.annotation.Nullable;

@RequiredArgsConstructor
public class ResponsesApiClient {
    private final HttpClient httpClient;
    private final HttpClientOptions clientOptions;

    public Future<HttpClientResponse> send(String url, HttpMethod method, Upstream upstream) {
        RequestOptions options = new RequestOptions()
                .setAbsoluteURI(url)
                .setMethod(method)
                .setConnectTimeout(clientOptions.getConnectTimeout())
                .setIdleTimeout(clientOptions.getIdleTimeout());
        return httpClient.request(options)
                .compose(request -> request
                        .putHeader(Proxy.HEADER_UPSTREAM_KEY, upstream.getKey())
                        .putHeader(Proxy.HEADER_UPSTREAM_ENDPOINT, upstream.getResponsesEndpoint())
                        .putHeader(Proxy.HEADER_UPSTREAM_EXTRA_DATA, UpstreamExtraDataMerger.merge(upstream))
                        .send());
    }

    private static boolean isTerminal(String status) {
        return !("queued".equals(status) || "in_progress".equals(status));
    }

    @SneakyThrows
    public static TerminalResult parseTerminalBody(Buffer body) {
        JsonNode node = ProxyUtil.MAPPER.readTree(body.getBytes());
        if (!(node instanceof ObjectNode tree)) {
            throw new IllegalStateException("Response body is not a JSON object.");
        }
        JsonNode statusNode = tree.path("status");
        if (!statusNode.isTextual() || !isTerminal(statusNode.asText())) {
            return null;
        }
        JsonNode usageNode = tree.path("usage");
        TokenUsage usage = usageNode.isObject()
                ? ProxyUtil.MAPPER.treeToValue(usageNode, TokenUsage.class) : null;
        JsonNode completedAtNode = tree.path("completed_at");
        // the Responses API reports completion in unix seconds; not every upstream sends the field
        Long completedAtMs = completedAtNode.isNumber() ? completedAtNode.asLong() * 1000 : null;
        return new TerminalResult(body, usage, completedAtMs);
    }

    /**
     * @param completedAtMs when the upstream finished the job, in epoch millis, or {@code null} if it didn't report it.
     */
    public record TerminalResult(Buffer body, TokenUsage usage, @Nullable Long completedAtMs) {

        public TerminalResult(Buffer body, TokenUsage usage) {
            this(body, usage, null);
        }
    }
}
