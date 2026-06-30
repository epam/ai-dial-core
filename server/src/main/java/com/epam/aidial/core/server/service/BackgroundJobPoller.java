package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.config.ConfigStore;
import com.epam.aidial.core.server.data.ResponseMapping;
import com.epam.aidial.core.server.upstream.UpstreamRouteProvider;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;

@RequiredArgsConstructor
public class BackgroundJobPoller {
    private final ConfigStore configStore;
    private final UpstreamRouteProvider upstreamRouteProvider;
    private final ResponsesApiClient client;

    public Future<ResponsesApiClient.TerminalResult> poll(ResponseMapping mapping) {
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
                .compose(response -> {
                    int statusCode = response.statusCode();
                    if (statusCode != 200) {
                        return Future.failedFuture("Unexpected status " + statusCode + " from upstream for background job " + mapping.getUpstreamResponseId());
                    }
                    return response.body().map(BackgroundJobPoller::parseTerminalBody);
                });
    }

    @SneakyThrows
    private static ResponsesApiClient.TerminalResult parseTerminalBody(Buffer body) {
        return ResponsesApiClient.parseTerminalBody(body);
    }
}
