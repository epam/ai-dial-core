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
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.annotation.Nullable;
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
        Instant deadline = request.timeout().map(Instant.now()::plus).orElse(null);
        return sendWithRedirects(request, bodyHandler, 0, deadline);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler,
            HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
        // unused by the MCP SDK (it never calls this overload) - no redirect-safety logic applied here
        return delegate.sendAsync(request, bodyHandler, pushPromiseHandler);
    }

    /**
     * @param deadline when the caller's request timeout runs out, or {@code null} if it set none. The timeout
     *                 bounds the request as a whole, so each hop gets only what is left of it - otherwise every
     *                 redirect would restart the clock, and a server could stretch one request far past it.
     */
    private <T> CompletableFuture<HttpResponse<T>> sendWithRedirects(HttpRequest request,
            HttpResponse.BodyHandler<T> bodyHandler, int redirectCount, @Nullable Instant deadline) {
        AtomicReference<HttpResponse.ResponseInfo> abandonedRedirect = new AtomicReference<>();
        HttpResponse.BodyHandler<T> guardedHandler = responseInfo -> {
            boolean willFollow = resolveFollowTarget(
                    responseInfo.statusCode(), responseInfo.headers(), request.uri(), redirectCount).isPresent();
            if (willFollow) {
                abandonedRedirect.set(responseInfo);
                return abandonBody();
            }
            return bodyHandler.apply(responseInfo);
        };
        return delegate.sendAsync(request, guardedHandler).handle((response, error) -> {
            if (error == null) {
                return resolveFollowTarget(response.statusCode(), response.headers(), request.uri(), redirectCount)
                        .map(target -> follow(request, target, bodyHandler, redirectCount, deadline))
                        .orElseGet(() -> CompletableFuture.completedFuture(response));
            }
            // abandoning a redirect's body cancels its exchange, which the client may report as a failure even
            // though the redirect itself was received - that failure is this class's own doing, so follow anyway
            HttpResponse.ResponseInfo redirect = abandonedRedirect.get();
            return Optional.ofNullable(redirect)
                    .flatMap(info -> resolveFollowTarget(info.statusCode(), info.headers(), request.uri(), redirectCount))
                    .map(target -> follow(request, target, bodyHandler, redirectCount, deadline))
                    .orElseGet(() -> CompletableFuture.failedFuture(error));
        }).thenCompose(Function.identity());
    }

    private <T> CompletableFuture<HttpResponse<T>> follow(HttpRequest request, URI target,
            HttpResponse.BodyHandler<T> bodyHandler, int redirectCount, @Nullable Instant deadline) {
        Duration remaining = deadline == null ? null : Duration.between(Instant.now(), deadline);
        if (remaining != null && (remaining.isZero() || remaining.isNegative())) {
            return CompletableFuture.failedFuture(new HttpTimeoutException("request timed out"));
        }
        return sendWithRedirects(rebuild(request, target, remaining), bodyHandler, redirectCount + 1, deadline);
    }

    /**
     * A followed redirect's body is never read - only its status and headers matter - so it is abandoned as soon
     * as the headers are in. Waiting for it to be discarded instead would hang on a server that never sends it, and
     * no request timeout covers a body. The body completes before the subscription is cancelled, because on
     * HTTP/2 cancelling reports the reset through {@code onError} synchronously.
     */
    @SuppressWarnings("unchecked")
    private static <T> HttpResponse.BodySubscriber<T> abandonBody() {
        return (HttpResponse.BodySubscriber<T>) new HttpResponse.BodySubscriber<Object>() {
            private final CompletableFuture<Object> body = new CompletableFuture<>();

            @Override
            public CompletionStage<Object> getBody() {
                return body;
            }

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                body.complete(null);
                subscription.cancel();
            }

            @Override
            public void onNext(List<ByteBuffer> item) {
                // never requested
            }

            @Override
            public void onError(Throwable throwable) {
                body.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                body.complete(null);
            }
        };
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

    private static HttpRequest rebuild(HttpRequest request, URI target, @Nullable Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .method(request.method(), request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()))
                .expectContinue(request.expectContinue());
        request.headers().map().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
        if (timeout != null) {
            builder.timeout(timeout);
        }
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
