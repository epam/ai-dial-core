package com.epam.aidial.core;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.security.ExtractedClaims;
import com.epam.aidial.core.token.TokenUsage;
import com.epam.aidial.core.upstream.UpstreamRoute;
import com.epam.aidial.core.util.BufferingReadStream;
import com.epam.aidial.core.util.HttpStatus;
import com.epam.aidial.core.util.ProxyUtil;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
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
    // Deployment which initiated request. It could be application, assistant or model
    private final Deployment originator;

    private Deployment deployment;
    private String userSub;
    private List<String> userRoles;
    private String userHash;
    private TokenUsage tokenUsage;
    private UpstreamRoute upstreamRoute;
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

    public ProxyContext(Config config, HttpServerRequest request, Key key, ExtractedClaims extractedClaims, Deployment originator) {
        this.config = config;
        this.key = key;
        this.request = request;
        this.originator = originator;
        this.response = request.response();
        this.requestTimestamp = System.currentTimeMillis();

        if (extractedClaims != null) {
            this.userRoles = extractedClaims.userRoles();
            this.userHash = extractedClaims.userHash();
            this.userSub = extractedClaims.sub();
        }
    }

    public Future<Void> respond(HttpStatus status) {
        return respond(status, null);
    }

    @SneakyThrows
    public Future<Void> respond(HttpStatus status, Object object) {
        String json = ProxyUtil.MAPPER.writeValueAsString(object);
        response.putHeader(HttpHeaders.CONTENT_TYPE, Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON);
        return respond(status, json);
    }

    public Future<Void> respond(HttpStatus status, String body) {
        return response.setStatusCode(status.getCode())
                .end(body == null ? "" : body);
    }

    public String getProject() {
        if (key != null) {
            return key.getProject();
        }
        if (originator != null) {
            return originator.getName();
        }
        throw new IllegalStateException("key and original can't be null");
    }
}