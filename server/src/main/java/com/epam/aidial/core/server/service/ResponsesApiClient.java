package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.proxy.ProxyClient;
import com.epam.aidial.core.server.proxy.ProxyRequest;
import com.epam.aidial.core.server.proxy.ProxyResponse;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.util.DeploymentEndpointUtil;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.UpstreamExtraDataMerger;
import com.epam.aidial.core.server.util.UpstreamInterfaceUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;

@RequiredArgsConstructor
public class ResponsesApiClient {
    private final ProxyClient proxyClient;
    private final HttpClientOptions clientOptions;

    /**
     * Sends an item operation to the origin of the context's deployment: to its plugin when its Responses interface
     * names one, over HTTP otherwise.
     */
    public Future<ProxyResponse> send(String url, HttpMethod method, String deploymentId, Upstream upstream, String apiKey,
                                      ProxyContext context) {
        String plugin = DeploymentEndpointUtil.resolvePlugin(context.getDeployment(), InterfaceType.OPENAI_RESPONSES,
                context.getConfig().getTranslators());
        Future<ProxyRequest> proxyRequest = plugin != null
                ? proxyClient.embedded(context, method, url, plugin)
                : proxyClient.http(new RequestOptions()
                        .setAbsoluteURI(url)
                        .setMethod(method)
                        .setConnectTimeout(clientOptions.getConnectTimeout())
                        .setIdleTimeout(clientOptions.getIdleTimeout()));
        return proxyRequest
                .compose(request -> request.putHeader(Proxy.HEADER_API_KEY, apiKey)
                        .putHeader(Proxy.HEADER_DEPLOYMENT_ID, deploymentId)
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
        JsonNode statusNode = tree.path("status");
        if (!statusNode.isTextual() || !isTerminal(statusNode.asText())) {
            return null;
        }
        JsonNode usageNode = tree.path("usage");
        TokenUsage usage = usageNode.isObject()
                ? ProxyUtil.MAPPER.treeToValue(usageNode, TokenUsage.class) : null;
        return new TerminalResult(body, usage);
    }

    public record TerminalResult(Buffer body, TokenUsage usage) {
    }
}
