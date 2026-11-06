package com.epam.aidial.core.server.mcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executor;
import javax.annotation.Nullable;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * Owns a single, process-lifetime {@code java.net.http.HttpClient}, so that code which insists on
 * building its own client per call (e.g. the MCP SDK's {@code HttpClientStreamableHttpTransport}) can
 * be made to reuse this one instance instead - avoiding a fresh {@code SelectorManager}/worker-thread
 * pool per request (see issue #1754). Callers plug {@link #httpClientBuilder()} into whatever
 * {@code HttpClient.Builder} slot the third-party code exposes; every {@code build()} call on it
 * returns this shared client unchanged.
 */
public class McpHttpClientBuilder implements AutoCloseable {

    private final HttpClient httpClient;
    private final HttpClient redirectSafeHttpClient;

    public McpHttpClientBuilder(Settings settings) {
        this(settings, null);
    }

    /**
     * @param proxySelector routes the shared client through an outbound proxy; {@code null} connects directly
     */
    public McpHttpClientBuilder(Settings settings, @Nullable ProxySelector proxySelector) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(settings.getConnectTimeout()))
                // the SDK's own default client builder requests HTTP/1.1; that preference is otherwise
                // lost because callers replace this builder's clientBuilder field wholesale rather than
                // layering onto it, so pin it here instead
                .version(HttpClient.Version.HTTP_1_1);
        if (proxySelector != null) {
            builder.proxy(proxySelector);
        }
        this.httpClient = builder.build();
        this.redirectSafeHttpClient = new RedirectSafeHttpClient(httpClient);
    }

    public HttpClient.Builder httpClientBuilder() {
        return new HttpClientBuilder(redirectSafeHttpClient);
    }

    /**
     * Aborts whatever is still in flight instead of waiting for it: {@code HttpClient.close()} blocks
     * until every exchange completes, so one request a server never answers would hang shutdown.
     */
    @Override
    public void close() {
        httpClient.shutdownNow();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Settings {
        /**
         * Connect timeout in milliseconds for the shared HttpClient.
         */
        private long connectTimeout;
    }

    /**
     * A {@code HttpClient.Builder} whose {@link #build()} always returns the same, already-built
     * {@code HttpClient}. Most configuration methods are a no-op that returns {@code this}, so callers
     * that chain configuration calls onto the builder before calling {@code build()} are unaffected.
     * {@link #followRedirects} and {@link #version} instead throw when the requested value actually
     * differs from what the shared client is already configured with: unlike the other settings,
     * silently discarding a caller's requested redirect policy or HTTP version would be a
     * correctness/security trap rather than a harmless no-op (see issue #1768) - a future caller
     * relying on either taking effect deserves a loud failure on a real conflict, not silent
     * divergence from the shared client's fixed behavior. A matching request (e.g. the MCP SDK's own
     * default builder asking for the same HTTP/1.1 preference already baked into the shared client)
     * is accepted as the no-op it already is.
     */
    private static final class HttpClientBuilder implements HttpClient.Builder {

        private final HttpClient httpClient;

        private HttpClientBuilder(HttpClient httpClient) {
            this.httpClient = httpClient;
        }

        @Override
        public HttpClient.Builder cookieHandler(CookieHandler cookieHandler) {
            return this;
        }

        @Override
        public HttpClient.Builder connectTimeout(Duration duration) {
            return this;
        }

        @Override
        public HttpClient.Builder sslContext(SSLContext sslContext) {
            return this;
        }

        @Override
        public HttpClient.Builder sslParameters(SSLParameters sslParameters) {
            return this;
        }

        @Override
        public HttpClient.Builder executor(Executor executor) {
            return this;
        }

        @Override
        public HttpClient.Builder followRedirects(HttpClient.Redirect policy) {
            if (policy != httpClient.followRedirects()) {
                throw new UnsupportedOperationException(
                        "the shared HttpClient's redirect policy is fixed and cannot be overridden per caller");
            }
            return this;
        }

        @Override
        public HttpClient.Builder version(HttpClient.Version version) {
            if (version != httpClient.version()) {
                throw new UnsupportedOperationException(
                        "the shared HttpClient's HTTP version is fixed and cannot be overridden per caller");
            }
            return this;
        }

        @Override
        public HttpClient.Builder priority(int priority) {
            return this;
        }

        @Override
        public HttpClient.Builder proxy(ProxySelector proxySelector) {
            return this;
        }

        @Override
        public HttpClient.Builder authenticator(Authenticator authenticator) {
            return this;
        }

        @Override
        public HttpClient build() {
            return httpClient;
        }
    }
}
