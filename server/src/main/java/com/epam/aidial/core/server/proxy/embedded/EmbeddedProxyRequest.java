package com.epam.aidial.core.server.proxy.embedded;

import com.epam.aidial.core.plugin.Caller;
import com.epam.aidial.core.plugin.Plugin;
import com.epam.aidial.core.plugin.Request;
import com.epam.aidial.core.plugin.Response;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.proxy.ProxyRequest;
import com.epam.aidial.core.server.proxy.ProxyResponse;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.AsyncResult;
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
 * {@code Plugin} contract says; nothing moves a callback back onto it. Put a forwarding adapter in front of the
 * plugin's stream once a plugin needs threads of its own.
 */
public final class EmbeddedProxyRequest implements ProxyRequest {

    private final ContextInternal context;
    private final ProxyContext proxyContext;
    private final Supplier<Plugin> plugin;
    private final HttpMethod method;
    private final String baseUrl;
    private final String absoluteUri;
    private final MultiMap headers = MultiMap.caseInsensitiveMultiMap();
    // set for a router's decide or notice request, null for a request to a deployment's interface
    private final RouterRequest router;
    private EmbeddedProxyResponse response;
    // serves the calls the plugin makes while it answers this attempt; set when the attempt is sent
    private VertxHostDelegate host;
    private boolean reset;
    // the plugin's future completed
    private boolean settled;
    // the client went away before the plugin answered
    private boolean clientGone;

    /**
     * @param proxyContext the pass the plugin answers
     * @param plugin       the plugin, taken when the attempt is sent
     * @param baseUrl      the {@code plugin://<name>} url the interface resolves to
     * @param absoluteUri  that url with the path and query, the plugin getting the path and query
     */
    public EmbeddedProxyRequest(ContextInternal context, ProxyContext proxyContext, Supplier<Plugin> plugin, HttpMethod method,
                         String baseUrl, String absoluteUri) {
        this(context, proxyContext, plugin, method, baseUrl, absoluteUri, null);
    }

    /**
     * @param router for a router's decide or notice request: the key its calls carry and the body tree it gets
     */
    public EmbeddedProxyRequest(ContextInternal context, ProxyContext proxyContext, Supplier<Plugin> plugin, HttpMethod method,
                         String baseUrl, String absoluteUri, RouterRequest router) {
        this.context = context;
        this.proxyContext = proxyContext;
        this.plugin = plugin;
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
        host = router == null
                ? new VertxHostDelegate(proxyContext.getProxy(), proxyContext, context)
                : VertxHostDelegate.forRouter(proxyContext.getProxy(), proxyContext, context, router.callKey());
        // registered for a router request too: the pass's end closes an attempt nothing else closed
        proxyContext.addHostDelegate(host);
        if (router == null) {
            watchClient();
        }
        try {
            Request request = new Request(method, absoluteUri.substring(baseUrl.length()), headers, body, tree, caller());
            plugin.get().handle(request, host).onComplete(result -> onAnswer(result, promise));
        } catch (RuntimeException | LinkageError e) {
            // Core is shutting down, creating the plugin again failed, or plugin code threw: a jar missing a class
            // throws NoClassDefFoundError on first use
            fail(promise, e);
        }
        return promise.future();
    }

    /**
     * The relay notices a client gone once it writes; while the plugin works, Core watches the connection itself.
     */
    private void watchClient() {
        HttpServerResponse client = proxyContext.getResponse();
        client.closeHandler(ignored -> onClientGone());
        if (client.closed()) {
            // a close handler set on a closed connection never fires: a client gone during the parse or a retry
            onClientGone();
        }
    }

    private void onAnswer(AsyncResult<Response> result, Promise<ProxyResponse> promise) {
        settled = true;
        Throwable failure = result.failed() ? result.cause() : null;
        EmbeddedProxyResponse answer = null;
        if (failure == null) {
            try {
                answer = toProxyResponse(result.result());
            } catch (RuntimeException e) {
                // thrown here, on a context the plugin completed, it would be swallowed and the request left hanging
                failure = e;
            }
        }
        if (failure != null && clientGone) {
            // ponytail: the pass ends here, as after a client gone from an application: no retry, no row, no
            // finalize; the per-request key and the trace's usage record expire by their TTL. Its attempts close,
            // so that their calls end
            proxyContext.closeHostDelegates();
        } else if (failure != null) {
            fail(promise, failure);
        } else if (reset) {
            response = answer;
            answer.discard();
            promise.fail(new IllegalStateException("The request was reset before the plugin answered"));
        } else {
            response = answer;
            promise.complete(answer);
        }
    }

    /**
     * The plugin's answer as Core relays it. A null answer, or one without a body or headers, fails the attempt, as a
     * plugin that throws does.
     */
    private EmbeddedProxyResponse toProxyResponse(@Nullable Response answer) {
        if (answer == null) {
            throw new IllegalStateException("The plugin answered null");
        }
        return router == null
                ? new EmbeddedProxyResponse(answer, host::cancel)
                : new EmbeddedProxyResponse(answer, host::cancel, host::close);
    }

    private void fail(Promise<ProxyResponse> promise, Throwable error) {
        settled = true;
        if (router != null) {
            // a router request's attempt ends with its answer: its calls carry the decide key, which an in-process
            // call does not look up again, so invalidating the key alone would not stop them
            host.close();
        }
        promise.fail(error);
    }

    private static ObjectNode parseOrNull(Buffer body) {
        try {
            return ProxyUtil.parseObject(body);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void onClientGone() {
        // runs on the request's context, as the plugin's callbacks do
        if (!settled && !reset) {
            clientGone = true;
            host.cancel();
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
            // first: the plugin's answer is cancelled before the calls it may relay are
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
