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
    private HttpClientResponse proxyResponse;
    private Buffer proxyRequestBody;
    private Buffer proxyResponseBody;
    private BufferingReadStream proxyResponseStream; // received from origin
    private long timestamp;

    public ProxyContext(Config config, HttpServerRequest request, Key key, List<String> userRoles) {
        this.config = config;
        this.key = key;
        this.request = request;
        this.response = request.response();
        this.timestamp = System.currentTimeMillis();
        this.userRoles = userRoles;
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