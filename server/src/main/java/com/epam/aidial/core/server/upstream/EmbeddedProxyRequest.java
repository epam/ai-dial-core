package com.epam.aidial.core.server.upstream;

import com.epam.aidial.core.plugin.OriginPlugin;
import com.epam.aidial.core.plugin.OriginRequest;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.impl.ContextInternal;

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
    private final OriginPlugin plugin;
    private final HttpMethod method;
    private final String baseUrl;
    private final String absoluteUri;
    private final MultiMap headers = MultiMap.caseInsensitiveMultiMap();
    private EmbeddedProxyResponse response;
    private boolean reset;

    /**
     * @param baseUrl     the {@code plugin://<name>} url the interface resolves to
     * @param absoluteUri that url with the path and query, the plugin getting the path and query
     */
    EmbeddedProxyRequest(ContextInternal context, OriginPlugin plugin, HttpMethod method, String baseUrl, String absoluteUri) {
        this.context = context;
        this.plugin = plugin;
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
        OriginRequest request = new OriginRequest(method, absoluteUri.substring(baseUrl.length()), headers, body);
        try {
            plugin.handle(request).onComplete(result -> {
                if (result.failed()) {
                    promise.fail(result.cause());
                    return;
                }
                EmbeddedProxyResponse answer = new EmbeddedProxyResponse(result.result());
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
            promise.fail(e);
        }
        return promise.future();
    }

    @Override
    public boolean reset() {
        reset = true;
        if (response != null) {
            response.discard();
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
