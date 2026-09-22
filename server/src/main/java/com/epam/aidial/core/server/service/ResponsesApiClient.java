package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.UpstreamExtraDataMerger;
import com.epam.aidial.core.server.util.UpstreamInterfaceUtil;
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

@RequiredArgsConstructor
public class ResponsesApiClient {
    private final HttpClient httpClient;
    private final HttpClientOptions clientOptions;

    public Future<HttpClientResponse> send(String url, HttpMethod method, Upstream upstream, String apiKey) {
        RequestOptions options = new RequestOptions()
                .setAbsoluteURI(url)
                .setMethod(method)
                .setConnectTimeout(clientOptions.getConnectTimeout())
                .setIdleTimeout(clientOptions.getIdleTimeout());
        return httpClient.request(options)
                .compose(request -> request.putHeader(Proxy.HEADER_API_KEY, apiKey)
                            .putHeader(Proxy.HEADER_UPSTREAM_KEY,
                                    UpstreamInterfaceUtil.resolveKey(upstream, InterfaceType.OPENAI_RESPONSES))
                            .putHeader(Proxy.HEADER_UPSTREAM_ENDPOINT,
                                    UpstreamInterfaceUtil.resolveEndpoint(upstream, InterfaceType.OPENAI_RESPONSES))
                            .putHeader(Proxy.HEADER_UPSTREAM_EXTRA_DATA,
                                    UpstreamExtraDataMerger.merge(upstream, InterfaceType.OPENAI_RESPONSES))
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
        if (!isTerminalStatus(tree)) {
            return null;
        }
        return new TerminalResult(body, extractUsage(tree));
    }

    /**
     * Whether an already-parsed Responses API body reports a terminal status. For a caller that already
     * parsed the body into a tree for its own purposes (e.g. id rewriting) - reads the tree directly instead
     * of reparsing the same bytes {@link #parseTerminalBody(Buffer)} would, and never needs the raw body
     * to answer this.
     */
    public static boolean isTerminalStatus(JsonNode tree) {
        JsonNode statusNode = tree.path("status");
        return statusNode.isTextual() && isTerminal(statusNode.asText());
    }

    /**
     * Same as the {@code usage} extraction inside {@link #parseTerminalBody(Buffer)}, for a caller that
     * already has the tree - never needs the raw body to answer this either.
     */
    @SneakyThrows
    public static TokenUsage extractUsage(JsonNode tree) {
        JsonNode usageNode = tree.path("usage");
        return usageNode.isObject() ? ProxyUtil.MAPPER.treeToValue(usageNode, TokenUsage.class) : null;
    }

    public record TerminalResult(Buffer body, TokenUsage usage) {
    }
}
