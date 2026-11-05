package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.DeploymentInterface;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Router;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.databind.JsonNode;
import io.netty.channel.ConnectTimeoutException;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;

import static com.epam.aidial.core.server.Proxy.HEADER_APPLICATION_ID;
import static com.epam.aidial.core.server.Proxy.HEADER_APPLICATION_PROPERTIES;
import static com.epam.aidial.core.server.Proxy.HEADER_USER_INFO;

/**
 * Asks a router in decision mode which deployments may serve a request. The decide request is the client's
 * request as Core forwards it to any origin, without its body unless the entry asks for it, and with no
 * per-request key: nothing is minted for the call.
 */
@Slf4j
@UtilityClass
public class RouterDecisionClient {

    static final long DEFAULT_TIMEOUT_MS = 5000;
    // a decide key outlives its call only when Core dies before invalidating it: then it expires soon after
    private static final long KEY_TTL_SLACK_MS = 60_000;

    /**
     * The TTL of the decide request's key: the decision budget plus a minute.
     */
    public Duration keyTtl(DeploymentInterface entry) {
        return Duration.ofMillis(timeoutMs(entry) + KEY_TTL_SLACK_MS);
    }

    private long timeoutMs(DeploymentInterface entry) {
        return entry.getDecisionTimeoutMs() == null ? DEFAULT_TIMEOUT_MS : entry.getDecisionTimeoutMs();
    }

    /**
     * The candidates in order of preference. Fails with the {@link HttpException} Core answers the client:
     * 504 when the router takes longer than the entry's {@code decisionTimeoutMs}, connecting included, 502
     * when it is unreachable or answers 5xx or an invalid verdict, and the router's own status, body and
     * content type for a 4xx.
     *
     * @param body the client's body bytes to send, or null to send an empty body
     * @param apiKey the decide request's per-request key, sent as {@code API-KEY}
     */
    public Future<List<String>> decide(ProxyContext context, Router router, DeploymentInterface entry, InterfaceType type,
                                       @Nullable Buffer body, String apiKey) {
        Proxy proxy = context.getProxy();
        long timeoutMs = timeoutMs(entry);
        RequestOptions options = new RequestOptions()
                .setAbsoluteURI(entry.getBaseUrl())
                .setMethod(HttpMethod.POST)
                .setTraceOperation("Ask %s router for a decision".formatted(router.getName()))
                .setConnectTimeout(Math.min(proxy.getClientOptions().getConnectTimeout(), timeoutMs))
                .setIdleTimeout(timeoutMs);

        Promise<List<String>> verdict = Promise.promise();
        // one deadline for the whole call: the connect and idle timeouts alone would allow twice the budget
        long timer = proxy.getVertx().setTimer(timeoutMs, ignore -> verdict.tryFail(
                new HttpException(HttpStatus.GATEWAY_TIMEOUT, "Router %s did not decide in time".formatted(router.getName()))));
        proxy.getClient().request(options)
                .compose(request -> {
                    // past the deadline the client has its 504: the router's connection is not kept open
                    verdict.future().onFailure(ignore -> request.reset());
                    return send(context, router, type, request, body, apiKey);
                })
                .compose(response -> response.body().map(answer -> parseVerdict(response, answer)))
                .onComplete(result -> {
                    proxy.getVertx().cancelTimer(timer);
                    if (result.succeeded()) {
                        verdict.tryComplete(result.result());
                    } else {
                        log.warn("Decide request failed. Router: {}. Error: {}", router.getName(), result.cause().getMessage());
                        verdict.tryFail(toHttpException(router, result.cause()));
                    }
                });
        return verdict.future();
    }

    private Future<HttpClientResponse> send(ProxyContext context, Router router, InterfaceType type, HttpClientRequest request,
                                            @Nullable Buffer body, String apiKey) {
        // the exclusions sendProxyRequest makes for every origin
        MultiMap excludeHeaders = MultiMap.caseInsensitiveMultiMap();
        if (!router.isForwardAuthToken()) {
            excludeHeaders.add(HttpHeaders.AUTHORIZATION, "whatever");
        }
        excludeHeaders.add(HEADER_APPLICATION_PROPERTIES, "whatever");
        excludeHeaders.add(HEADER_APPLICATION_ID, "whatever");
        excludeHeaders.add(HEADER_USER_INFO, "whatever");

        MultiMap headers = request.headers();
        ProxyUtil.copyHeaders(context.getRequest().headers(), headers, excludeHeaders);
        // on the decide request only: the chosen deployment gets its own defaults, as on a direct call
        router.resolveDefaultHeaders(type).forEach((name, value) -> {
            if (!headers.contains(name)) {
                headers.set(name, value);
            }
        });
        if (context.getRouterPropertiesHeader() != null) {
            headers.set(HEADER_APPLICATION_PROPERTIES, context.getRouterPropertiesHeader());
        }
        headers.set(Proxy.HEADER_API_KEY, apiKey);
        headers.set(Proxy.HEADER_ROUTER, router.getName());
        headers.set(Proxy.HEADER_API, type.getValue());
        if (body == null) {
            return request.send();
        }
        headers.set(HttpHeaders.CONTENT_TYPE, Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON);
        headers.set(HttpHeaders.CONTENT_LENGTH, Integer.toString(body.length()));
        return request.send(body);
    }

    private List<String> parseVerdict(HttpClientResponse response, Buffer answer) {
        int status = response.statusCode();
        if (status >= 400 && status < 500) {
            String contentType = response.getHeader(HttpHeaders.CONTENT_TYPE);
            Map<String, String> headers = contentType == null ? Map.of() : Map.of(HttpHeaders.CONTENT_TYPE.toString(), contentType);
            // HttpStatus lists only some 4xx codes: an unlisted one stays a client error rather than becoming 500
            throw new HttpException(HttpStatus.fromStatusCode(status, HttpStatus.BAD_REQUEST), answer.toString(), headers);
        }
        if (status != 200) {
            throw new HttpException(HttpStatus.BAD_GATEWAY, "Router failed to decide: status " + status);
        }
        return parseCandidates(answer);
    }

    /**
     * {@code candidates}, a non-empty array of names, or {@code deployment}, one name. Unknown fields are ignored.
     */
    static List<String> parseCandidates(Buffer answer) {
        JsonNode tree;
        try {
            tree = ProxyUtil.MAPPER.readTree(answer.getBytes());
        } catch (IOException e) {
            throw invalidDecision();
        }
        List<String> candidates = new ArrayList<>();
        JsonNode list = tree.path("candidates");
        if (list.isArray()) {
            list.forEach(candidate -> candidates.add(candidate.isTextual() ? candidate.asText() : ""));
        }
        if (candidates.isEmpty() || candidates.stream().anyMatch(String::isBlank)) {
            throw invalidDecision();
        }
        return candidates;
    }

    private HttpException invalidDecision() {
        return new HttpException(HttpStatus.BAD_GATEWAY, "Router answered an invalid decision");
    }

    private HttpException toHttpException(Router router, Throwable error) {
        if (error instanceof HttpException httpException) {
            return httpException;
        }
        if (error instanceof TimeoutException || error instanceof ConnectTimeoutException) {
            return new HttpException(HttpStatus.GATEWAY_TIMEOUT, "Router %s did not decide in time".formatted(router.getName()));
        }
        return new HttpException(HttpStatus.BAD_GATEWAY, "Router %s is unreachable".formatted(router.getName()));
    }
}
