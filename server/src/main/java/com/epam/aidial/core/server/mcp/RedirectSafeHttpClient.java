package com.epam.aidial.core.server.mcp;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * Wraps a real {@code HttpClient} and manually follows same-origin 307/308 redirects itself,
 * instead of relying on the JDK's built-in {@code Redirect.NORMAL}. The JDK's own cross-origin
 * protection on redirect only strips a fixed set of header names ({@code Authorization},
 * {@code Cookie}, {@code Origin}, {@code Referer}, {@code Host}) - it knows nothing about this
 * codebase's own custom MCP auth headers (a per-request {@code API-KEY} header, and an
 * admin-configured API-key header name), which would otherwise still be forwarded to a different
 * origin on redirect. Refusing to follow any redirect that isn't same-origin - mirroring
 * {@code McpProxyController}'s guard for the Vert.x-based MCP proxy path - protects every header
 * regardless of name.
 *
 * <p>The wrapped delegate is left at the JDK default {@code Redirect.NEVER}: this class is the
 * only thing that ever follows a redirect for requests sent through it.
 */
final class RedirectSafeHttpClient extends HttpClient {

    private final HttpClient delegate;

    RedirectSafeHttpClient(HttpClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return delegate.cookieHandler();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return delegate.connectTimeout();
    }

    @Override
    public HttpClient.Redirect followRedirects() {
        // the effective policy this class provides, even though it's implemented manually below
        // rather than via the JDK's own redirect-following
        return HttpClient.Redirect.NORMAL;
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return delegate.proxy();
    }

    @Override
    public SSLContext sslContext() {
        return delegate.sslContext();
    }

    @Override
    public SSLParameters sslParameters() {
        return delegate.sslParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return delegate.authenticator();
    }

    @Override
    public HttpClient.Version version() {
        return delegate.version();
    }

    @Override
    public Optional<Executor> executor() {
        return delegate.executor();
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
            throws IOException, InterruptedException {
        try {
            return sendAsync(request, responseBodyHandler).get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException(cause);
        }
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler) {
        return sendWithRedirects(request, bodyHandler, 0);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler,
            HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
        // unused by the MCP SDK (it never calls this overload) - no redirect-safety logic applied here
        return delegate.sendAsync(request, bodyHandler, pushPromiseHandler);
    }

    private <T> CompletableFuture<HttpResponse<T>> sendWithRedirects(HttpRequest request,
            HttpResponse.BodyHandler<T> bodyHandler, int redirectCount) {
        HttpResponse.BodyHandler<T> guardedHandler = responseInfo -> {
            boolean willFollow = resolveFollowTarget(
                    responseInfo.statusCode(), responseInfo.headers(), request.uri(), redirectCount).isPresent();
            if (willFollow) {
                // this response is discarded wholesale in the thenCompose below - only .statusCode()/.headers()
                // are ever read from it, never .body() - so faking the body's type here is safe
                @SuppressWarnings("unchecked")
                HttpResponse.BodySubscriber<T> discarding = (HttpResponse.BodySubscriber<T>) HttpResponse.BodySubscribers.discarding();
                return discarding;
            }
            return bodyHandler.apply(responseInfo);
        };
        return delegate.sendAsync(request, guardedHandler).thenCompose(response -> {
            Optional<URI> target = resolveFollowTarget(response.statusCode(), response.headers(), request.uri(), redirectCount);
            if (target.isEmpty()) {
                return CompletableFuture.completedFuture(response);
            }
            return sendWithRedirects(rebuild(request, target.get()), bodyHandler, redirectCount + 1);
        });
    }

    /**
     * Decides whether a response should be followed as a redirect, and to where. Used both to pick the body
     * subscriber (a followed redirect's body must never reach the caller's real handler) and to actually follow -
     * the two decisions must share this exact predicate, or a redirect whose body was discarded but which then
     * turns out NOT to be followed would leave the caller's handler without a response.
     */
    private static Optional<URI> resolveFollowTarget(int statusCode, HttpHeaders headers, URI requestUri, int redirectCount) {
        if ((statusCode != 307 && statusCode != 308) || redirectCount >= McpClientUtils.MAX_MCP_REDIRECTS) {
            return Optional.empty();
        }
        Optional<String> location = headers.firstValue("Location");
        if (location.isEmpty()) {
            return Optional.empty();
        }
        URI target;
        try {
            // resolve via URI.resolve(URI), not resolve(String) - the latter delegates to URI.create(String),
            // which throws an unchecked IllegalArgumentException on a malformed location
            target = requestUri.resolve(new URI(location.get()));
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        return McpClientUtils.isSameOrigin(requestUri, target) ? Optional.of(target) : Optional.empty();
    }

    private static HttpRequest rebuild(HttpRequest request, URI target) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .method(request.method(), request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()))
                .expectContinue(request.expectContinue());
        request.headers().map().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
        request.timeout().ifPresent(builder::timeout);
        return builder.build();
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public void shutdownNow() {
        delegate.shutdownNow();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(Duration duration) throws InterruptedException {
        return delegate.awaitTermination(duration);
    }
}
