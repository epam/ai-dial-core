package com.epam.aidial.core.server.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executor;
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
public class McpHttpClientBuilderService implements AutoCloseable {

    private final HttpClient httpClient;

    public McpHttpClientBuilderService(Settings settings) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(settings.getConnectTimeout()))
                .build();
    }

    public HttpClient.Builder httpClientBuilder() {
        return new HttpClientBuilder(httpClient);
    }

    @Override
    public void close() {
        httpClient.close();
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
     * {@code HttpClient}; every other method is a no-op that returns {@code this}, so callers that
     * chain configuration calls onto the builder before calling {@code build()} are unaffected.
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
            return this;
        }

        @Override
        public HttpClient.Builder version(HttpClient.Version version) {
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
