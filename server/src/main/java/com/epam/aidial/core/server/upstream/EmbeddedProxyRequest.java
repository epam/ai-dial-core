package com.epam.aidial.core.server.upstream;

import com.epam.aidial.core.plugin.OriginCaller;
import com.epam.aidial.core.plugin.OriginRequest;
import com.epam.aidial.core.server.ChildPass;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.config.EmbeddedOrigins.Lease;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.impl.ContextInternal;

import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;

/**
 * A {@link ProxyRequest} answered by a plugin running in Core. Its futures complete on the request's Vert.x context,
 * as the HTTP client's do, so {@link #send(Buffer)} is called on the request's event loop and calls the plugin there.
 * Not thread-safe: everything runs on that context.
 *
 * <p>ponytail: Core trusts the plugin to complete its future and emit its body on that context, as the
 * {@code OriginPlugin} contract says; nothing moves a callback back onto it. Put a forwarding adapter in front of the
 * plugin's stream once a plugin needs threads of its own.
 */
final class EmbeddedProxyRequest implements ProxyRequest {

    private final ContextInternal context;
    private final ProxyContext proxyContext;
    private final Supplier<Lease> lease;
    private final HttpMethod method;
    private final String baseUrl;
    private final String absoluteUri;
    private final MultiMap headers = MultiMap.caseInsensitiveMultiMap();
    private EmbeddedProxyResponse response;
    private ChildPass host;
    private boolean reset;
    // the plugin's future completed
    private boolean settled;
    // the client went away before the plugin answered
    private boolean clientGone;

    /**
     * @param proxyContext the pass the plugin answers
     * @param lease        takes the plugin for one attempt
     * @param baseUrl      the {@code plugin://<name>} url the interface resolves to
     * @param absoluteUri  that url with the path and query, the plugin getting the path and query
     */
    EmbeddedProxyRequest(ContextInternal context, ProxyContext proxyContext, Supplier<Lease> lease, HttpMethod method,
                         String baseUrl, String absoluteUri) {
        this.context = context;
        this.proxyContext = proxyContext;
        this.lease = lease;
        this.method = method;
        this.baseUrl = baseUrl;
        this.absoluteUri = absoluteUri;
    }

    @Override
    public MultiMap headers() {
        return headers;
    }

    @Override
    public ProxyRequest putHeader(CharSequence name, CharSequence value) {
        headers.set(name, value);
        return this;
    }

    @Override
    public Future<ProxyResponse> send() {
        return send(Buffer.buffer());
    }

    @Override
    public Future<ProxyResponse> send(Buffer body) {
        Promise<ProxyResponse> promise = context.promise();
        OriginRequest request = new OriginRequest(method, absoluteUri.substring(baseUrl.length()), headers, body, bodyTree(body), caller());
        ChildPass attempt = new ChildPass(proxyContext.getProxy(), proxyContext, context);
        host = attempt;
        proxyContext.addChildPass(attempt);
        // the relay notices a client gone once it writes; while the plugin works, Core watches the connection itself
        HttpServerResponse client = proxyContext.getResponse();
        client.closeHandler(ignored -> onClientGone(attempt));
        if (client.closed()) {
            // a close handler set on a closed connection never fires: a client gone during the parse or a retry
            onClientGone(attempt);
        }
        Lease taken;
        try {
            taken = lease.get();
        } catch (RuntimeException e) {
            // Core is shutting down, the plugin closed twice while the attempt started, or creating it again failed:
            // the attempt fails as a plugin that throws does
            settled = true;
            promise.fail(e);
            return promise.future();
        }
        try {
            taken.plugin().handle(request, attempt).onComplete(result -> {
                settled = true;
                // given back once the attempt has closed too: the plugin is closed neither before its answer nor
                // under its calls
                attempt.whenClosed(taken.onDone());
                if (result.failed()) {
                    if (clientGone) {
                        // ponytail: the pass ends here, as after a client gone from an application: no retry, no
                        // row, no finalize, the per-request key expiring by its TTL. Its attempts close, so that an
                        // earlier one gives its plugin back too
                        proxyContext.closeChildPasses();
                        return;
                    }
                    promise.fail(result.cause());
                    return;
                }
                EmbeddedProxyResponse answer = new EmbeddedProxyResponse(result.result(), attempt::cancel);
                response = answer;
                if (reset) {
                    answer.discard();
                    promise.fail(new IllegalStateException("The request was reset before the plugin answered"));
                    return;
                }
                promise.complete(answer);
            });
        } catch (RuntimeException | LinkageError e) {
            // plugin code: a jar missing a class throws NoClassDefFoundError on first use
            settled = true;
            attempt.whenClosed(taken.onDone());
            promise.fail(e);
        }
        return promise.future();
    }

    /**
     * The tree the chain serialised into {@code body}, taken from the context so that a later attempt does not get a
     * tree an earlier call has changed. A retried attempt finds none and parses the body.
     */
    private ObjectNode bodyTree(Buffer body) {
        ObjectNode tree = proxyContext.getRequestTree();
        proxyContext.setRequestTree(null);
        if (tree != null || body.length() == 0) {
            return tree;
        }
        try {
            return ProxyUtil.parseObject(body);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void onClientGone(ChildPass attempt) {
        // runs on the request's context, as the plugin's callbacks do
        if (!settled && !reset) {
            clientGone = true;
            attempt.cancel();
        }
    }

    private OriginCaller caller() {
        List<String> roles = proxyContext.getUserRoles();
        return new OriginCaller(proxyContext.getUserId(), roles == null ? List.of() : List.copyOf(roles), proxyContext.getProject());
    }

    @Override
    public boolean reset() {
        reset = true;
        if (response != null) {
            // first: the answer is cancelled before its attempt closes and may give the plugin back
            response.discard();
        }
        if (host != null) {
            host.cancel();
        }
        return true;
    }

    @Override
    public String remoteAddress() {
        return baseUrl;
    }

    @Override
    public String absoluteUri() {
        return absoluteUri;
    }
}
