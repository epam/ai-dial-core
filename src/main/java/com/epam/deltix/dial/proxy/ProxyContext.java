package com.epam.deltix.dial.proxy;

import com.epam.deltix.dial.proxy.config.Config;
import com.epam.deltix.dial.proxy.config.Deployment;
import com.epam.deltix.dial.proxy.config.Key;
import com.epam.deltix.dial.proxy.endpoint.EndpointProvider;
import com.epam.deltix.dial.proxy.endpoint.EndpointRoute;
import com.epam.deltix.dial.proxy.token.TokenUsage;
import com.epam.deltix.dial.proxy.util.BufferingReadStream;
import com.epam.deltix.dial.proxy.util.HttpStatus;
import com.epam.deltix.dial.proxy.util.ProxyUtil;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;

import java.util.List;
import java.util.Map;

@Getter
@Setter
public class ProxyContext {

    private final Config config;
    private final Key key;
    private final HttpServerRequest request;
    private final HttpServerResponse response;

    private Deployment deployment;
    private List<String> userRoles;
    private TokenUsage tokenUsage;
    private EndpointProvider endpointProvider;
    private EndpointRoute endpointRoute;
    private HttpClientRequest proxyRequest;
    private Map<String, String> requestHeaders = Map.of();
    private HttpClientResponse proxyResponse;
    private Buffer requestBody;
    private Buffer responseBody;
    private BufferingReadStream responseStream; // received from origin
    private long requestTimestamp;
    private long requestBodyTimestamp;
    private long proxyConnectTimestamp;
    private long proxyResponseTimestamp;
    private long responseBodyTimestamp;

    public ProxyContext(Config config, HttpServerRequest request, Key key, List<String> userRoles) {
        this.config = config;
        this.key = key;
        this.request = request;
        this.response = request.response();
        this.userRoles = userRoles;
        this.requestTimestamp = System.currentTimeMillis();
    }

    public Future<Void> respond(HttpStatus status) {
        return respond(status, null);
    }

    @SneakyThrows
    public Future<Void> respond(HttpStatus status, Object object) {
        String json = ProxyUtil.MAPPER.writeValueAsString(object);
        return respond(status, json);
    }

    public Future<Void> respond(HttpStatus status, String body) {
        return response.setStatusCode(status.getCode())
                .end(body == null ? "" : body);
    }
}