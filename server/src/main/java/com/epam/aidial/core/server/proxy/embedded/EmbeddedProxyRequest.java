package com.epam.aidial.core.server.proxy.embedded;

import com.epam.aidial.core.plugin.Caller;
import com.epam.aidial.core.plugin.Request;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.plugin.PluginManager.Lease;
import com.epam.aidial.core.server.proxy.ProxyRequest;
import com.epam.aidial.core.server.proxy.ProxyResponse;
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
import javax.annotation.Nullable;

/**
 * A {@link ProxyRequest} answered by a plugin running in Core. Its futures complete on the request's Vert.x context,
 * as the HTTP client's do, so {@link #send(Buffer)} is called on the request's event loop and calls the plugin there.
 * Not thread-safe: everything runs on that context.
 *
 * <p>ponytail: Core trusts the plugin to complete its future and emit its body on that context, as the
 * {@code OriginPlugin} contract says; nothing moves a callback back onto it. Put a forwarding adapter in front of the
 * plugin's stream once a plugin needs threads of its own.
 */
public final class EmbeddedProxyRequest implements ProxyRequest {

    private final ContextInternal context;
    private final ProxyContext proxyContext;
    private final Supplier<Lease> lease;
    private final HttpMethod method;
    private final String baseUrl;
    private final String absoluteUri;
    private final MultiMap headers = MultiMap.caseInsensitiveMultiMap();
    // set for a router's decide or notice request, null for a request to a deployment's interface
    private final RouterRequest router;
    private EmbeddedProxyResponse response;
    private VertxHostDelegate host;
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
    public EmbeddedProxyRequest(ContextInternal context, ProxyContext proxyContext, Supplier<Lease> lease, HttpMethod method,
                         String baseUrl, String absoluteUri) {
        this(context, proxyContext, lease, method, baseUrl, absoluteUri, null);
    }

    /**
     * @param router for a router's decide or notice request: the key its calls carry and the body tree it gets
     */
    public EmbeddedProxyRequest(ContextInternal context, ProxyContext proxyContext, Supplier<Lease> lease, HttpMethod method,
                         String baseUrl, String absoluteUri, RouterRequest router) {
        this.context = context;
        this.proxyContext = proxyContext;
        this.lease = lease;
        this.method = method;
        this.baseUrl = baseUrl;
        this.absoluteUri = absoluteUri;
        this.router = router;
    }

    /**
     * What sets a router's decide or notice request apart from a request to a deployment's interface. The calls of its
     * attempt carry {@code callKey}, none for a notice. Its plugin gets {@code bodyTree}, or no tree. The attempt does not
     * watch the client, whose leaving the decision handles. It closes once the plugin's answer has been read or has
     * failed.
     */
    public record RouterRequest(@Nullable ApiKeyData callKey, @Nullable ObjectNode bodyTree) {
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
        if (router != null) {
            return send(body, router.bodyTree());
        }
        // taken from the context, so that a later attempt does not get a tree an earlier call has changed
        ObjectNode tree = proxyContext.getRequestTree();
        proxyContext.setRequestTree(null);
        if (tree != null || body.length() == 0) {
            return send(body, tree);
        }
        // a retried attempt: the body is parsed again, off the event loop, and the plugin called back on this context
        return proxyContext.getProxy().getTaskExecutor().submit(() -> parseOrNull(body))
                .compose(parsed -> reset
                        ? Future.failedFuture(new IllegalStateException("The request was reset before the plugin was called"))
                        : send(body, parsed));
    }

    private Future<ProxyResponse> send(Buffer body, @Nullable ObjectNode tree) {
        Promise<ProxyResponse> promise = context.promise();
        Request request = new Request(method, absoluteUri.substring(baseUrl.length()), headers, body, tree, caller());
        VertxHostDelegate attempt = router != null
                ? VertxHostDelegate.forRouter(proxyContext.getProxy(), proxyContext, context, router.callKey())
                : new VertxHostDelegate(proxyContext.getProxy(), proxyContext, context);
        host = attempt;
        // registered for a router request too: the pass's end closes an attempt nothing else closed
        proxyContext.addHostDelegate(attempt);
        if (router == null) {
            // the relay notices a client gone once it writes; while the plugin works, Core watches the connection itself
            HttpServerResponse client = proxyContext.getResponse();
            client.closeHandler(ignored -> onClientGone(attempt));
            if (client.closed()) {
                // a close handler set on a closed connection never fires: a client gone during the parse or a retry
                onClientGone(attempt);
            }
        }
        Lease taken;
        try {
            taken = lease.get();
        } catch (RuntimeException e) {
            // Core is shutting down, the plugin closed twice while the attempt started, or creating it again failed:
            // the attempt fails as a plugin that throws does
            settled = true;
            closeRouterAttempt(attempt);
            promise.fail(e);
            return promise.future();
        }
        try {
            taken.plugin().handle(request, attempt).onComplete(result -> {
                settled = true;
                // given back once the attempt has closed too: the plugin is closed neither before its answer nor
                // under its calls
                attempt.whenClosed(taken.onDone());
                // a null answer, or one without a body or headers, fails the attempt, as a plugin that throws does
                Throwable failure = result.failed() ? result.cause()
                        : result.result() == null ? new IllegalStateException("The plugin answered null") : null;
                EmbeddedProxyResponse answer = null;
                if (failure == null) {
                    try {
                        answer = router == null
                                ? new EmbeddedProxyResponse(result.result(), attempt::cancel)
                                : new EmbeddedProxyResponse(result.result(), attempt::cancel, attempt::close);
                    } catch (RuntimeException e) {
                        // thrown here, on a context the plugin completed, it would be swallowed and the request left hanging
                        failure = e;
                    }
                }
                if (failure != null) {
                    if (clientGone) {
                        // ponytail: the pass ends here, as after a client gone from an application: no retry, no
                        // row, no finalize, the per-request key expiring by its TTL. Its attempts close, so that an
                        // earlier one gives its plugin back too
                        proxyContext.closeHostDelegates();
                        return;
                    }
                    closeRouterAttempt(attempt);
                    promise.fail(failure);
                    return;
                }
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
            closeRouterAttempt(attempt);
            promise.fail(e);
        }
        return promise.future();
    }

    /**
     * A router request's attempt ends with its answer: its calls carry the decide key, which an in-process call does
     * not look up again, so invalidating the key alone would not stop them.
     */
    private void closeRouterAttempt(VertxHostDelegate attempt) {
        if (router != null) {
            attempt.close();
        }
    }

    private static ObjectNode parseOrNull(Buffer body) {
        try {
            return ProxyUtil.parseObject(body);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void onClientGone(VertxHostDelegate attempt) {
        // runs on the request's context, as the plugin's callbacks do
        if (!settled && !reset) {
            clientGone = true;
            attempt.cancel();
        }
    }

    private Caller caller() {
        List<String> roles = proxyContext.getUserRoles();
        return new Caller(proxyContext.getUserId(), roles == null ? List.of() : List.copyOf(roles), proxyContext.getProject());
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
