package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.config.ConfigStore;
import com.epam.aidial.core.server.data.ResponseMapping;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.upstream.UpstreamRouteProvider;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.http.HttpMethod;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class BackgroundJobPoller {

    private final ConfigStore configStore;
    private final UpstreamRouteProvider upstreamRouteProvider;
    private final ResponsesApiClient client;

    public Future<TokenUsage> poll(ResponseMapping mapping) {
        Config config = configStore.get();
        Deployment deployment = config.selectDeployment(mapping.getDeploymentName());
        if (deployment == null) {
            return Future.failedFuture("Deployment {} not found");
        }

        if (deployment.getResponsesEndpoint() == null) {
            return Future.failedFuture("Deployment " + deployment.getName() + " does not have a responses endpoint");
        }

        Upstream upstream;
        try {
            upstream = upstreamRouteProvider.get(deployment, null, mapping.getUpstreamKey()).next();
        } catch (Exception e) {
            return Future.failedFuture("Failed to get upstream for deployment " + deployment.getName()
                    + " and upstream key " + mapping.getUpstreamKey() + ": " + e.getMessage());
        }

        String targetUrl = deployment.getResponsesEndpoint() + "/" + mapping.getUpstreamResponseId();
        return client.send(targetUrl, HttpMethod.GET, upstream)
                .compose(response -> response.body().map(body -> {
                    try {
                        ObjectNode tree = (ObjectNode) ProxyUtil.MAPPER.readTree(body.getBytes());
                        if (isTerminal(tree.path("status").asText())) {
                            JsonNode usageNode = tree.path("usage");
                            if (usageNode.isNull()) {
                                throw new IllegalArgumentException("Missing usage field in response body");
                            }
                            return ProxyUtil.MAPPER.treeToValue(usageNode, TokenUsage.class);
                        } else {
                            return null;
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
    }

    private static boolean isTerminal(String status) {
        return !("queued".equals(status) || "in_progress".equals(status));
    }
}
