package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.util.UpstreamExtraDataMerger;
import io.vertx.core.Future;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import lombok.RequiredArgsConstructor;

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
}
